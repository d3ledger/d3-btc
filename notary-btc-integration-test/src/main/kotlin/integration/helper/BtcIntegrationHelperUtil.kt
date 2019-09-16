/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.helper

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.config.BitcoinConfig
import com.d3.btc.helper.address.createMsAddress
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.peer.SharedPeerGroupConfig
import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.account.IrohaBtcAccountRegistrator
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.registration.strategy.BtcRegistrationStrategyImpl
import com.d3.btc.wallet.WalletInitializer
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.commons.expansion.ExpansionDetails
import com.d3.commons.expansion.ExpansionUtils
import com.d3.commons.notary.IrohaCommand
import com.d3.commons.notary.IrohaOrderedBatch
import com.d3.commons.notary.IrohaTransaction
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.IrohaConverter
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.irohaEscape
import com.d3.commons.util.toHexString
import com.github.jleskovar.btcrpc.BitcoinRpcClientFactory
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.Wallet
import java.io.File
import java.math.BigDecimal
import java.security.KeyPair
import kotlin.math.ceil

const val BTC_PRECISION = 8
// Default node id
const val NODE_ID = "any id"
// How many address may be generated in one batch
private const val GENERATED_ADDRESSES_PER_BATCH = 5
// How many blocks to generate to make Bitcoin regtest node able to send money
private const val BTC_INITIAL_BLOCKS = 101

class BtcIntegrationHelperUtil(peers: Int = 1) : IrohaIntegrationHelperUtil(peers) {

    override val configHelper by lazy { BtcConfigHelper(accountHelper) }

    private val registrationQueryHelper by lazy {
        IrohaQueryHelperImpl(irohaAPI, accountHelper.registrationAccount)
    }

    private val registrationIrohaConsumer by lazy {
        IrohaConsumerImpl(accountHelper.registrationAccount, irohaAPI)
    }

    private val mstRegistrationIrohaConsumer by lazy {
        IrohaConsumerImpl(accountHelper.mstRegistrationAccount, irohaAPI)
    }

    /**
     * Returns withdrawal fees
     */
    fun getWithdrawalFees() =
        BigDecimal(getIrohaAccountBalance("withdrawal_billing@$D3_DOMAIN", BTC_ASSET)).setScale(
            BTC_PRECISION
        )

    /**
     * Returns withdrawal account balance
     */
    fun getWithdrawalAccountBalance(btcWithdrawalConfig: BtcWithdrawalConfig) =
        BigDecimal(
            getIrohaAccountBalance(
                btcWithdrawalConfig.withdrawalCredential.accountId,
                BTC_ASSET
            )
        ).setScale(
            BTC_PRECISION
        )

    private val rpcClient by lazy {
        BitcoinRpcClientFactory.createClient(
            user = "test",
            password = "test",
            host = BitcoinConfig.extractHosts(configHelper.createBitcoinConfig())[0],
            port = 8332,
            secure = false
        )
    }

    private val btcRegisteredAddressesProvider = BtcRegisteredAddressesProvider(
        queryHelper,
        accountHelper.registrationAccount.accountId,
        accountHelper.notaryAccount.accountId
    )

    private val btcFreeAddress by lazy {
        BtcFreeAddressesProvider(
            NODE_ID,
            configHelper.freeAddressesStorageAccountCredential.accountId,
            registrationQueryHelper,
            registrationIrohaConsumer

        )
    }

    private val btcRegistrationStrategy by lazy {
        val irohaBtcAccountCreator = IrohaBtcAccountRegistrator(
            registrationConsumer,
            accountHelper.notaryAccount.accountId
        )
        BtcRegistrationStrategyImpl(
            btcRegisteredAddressesProvider,
            btcFreeAddress,
            irohaBtcAccountCreator
        )
    }

    /**
     * Pregenerates multiple BTC addresses that can be registered later
     * @param walletFilePath - path to wallet file
     * @param addressesToGenerate - number of addresses to generate
     * @param nodeId - if of node
     */
    fun preGenFreeBtcAddresses(walletFilePath: String, addressesToGenerate: Int, nodeId: String) {
        val totalBatches =
            ceil(addressesToGenerate.div(GENERATED_ADDRESSES_PER_BATCH.toDouble())).toInt()
        /*
         Iroha dies if it sees too much of transactions in a batch.
          */
        for (batch in 1..totalBatches) {
            if (batch == totalBatches) {
                preGenFreeBtcAddressesBatch(
                    walletFilePath,
                    addressesToGenerate - (totalBatches - 1) * GENERATED_ADDRESSES_PER_BATCH,
                    nodeId
                )
            } else {
                preGenFreeBtcAddressesBatch(walletFilePath, GENERATED_ADDRESSES_PER_BATCH, nodeId)
            }
        }
    }

    /**
     * Creates and executes a batch full of generated BTC addresses
     * @param walletFilePath - path to wallet file
     * @param addressesToGenerate - number of addresses to generate
     * @param nodeId - id of node
     */
    private fun preGenFreeBtcAddressesBatch(
        walletFilePath: String,
        addressesToGenerate: Int,
        nodeId: String
    ) {
        val irohaTxList = ArrayList<IrohaTransaction>()
        for (i in 1..addressesToGenerate) {
            val btcAddress = generateKeyAndAddress(walletFilePath, nodeId)
            val irohaTx = IrohaTransaction(
                registrationIrohaConsumer.creator,
                ModelUtil.getCurrentTime(),
                1,
                arrayListOf(
                    IrohaCommand.CommandSetAccountDetail(
                        configHelper.freeAddressesStorageAccountCredential.accountId,
                        btcAddress.address,
                        btcAddress.info.toJson().irohaEscape()
                    )
                )
            )
            irohaTxList.add(irohaTx)
        }
        val utx = IrohaConverter.convert(IrohaOrderedBatch(irohaTxList))
        registrationIrohaConsumer.send(utx).failure { ex -> throw ex }
    }

    /**
     * Returns group of peers
     */
    fun getPeerGroup(
        wallet: Wallet,
        btcNetworkConfigProvider: BtcNetworkConfigProvider,
        blockStoragePath: String,
        hosts: List<String>,
        walletInitializer: WalletInitializer
    ): SharedPeerGroup {
        return SharedPeerGroup(
            btcNetworkConfigProvider,
            wallet,
            SharedPeerGroupConfig(
                blockStoragePath = blockStoragePath,
                hosts = hosts,
                dnsSeeds = emptyList(),
                minBlockHeightForPeer = 0
            ),
            walletInitializer
        )
    }

    /**
     * Generates one BTC address that can be registered later
     * @param walletFilePath - path to wallet file
     * @param nodeId - node id. NODE_ID by default
     * @return randomly generated BTC address
     */
    fun genFreeBtcAddress(
        walletFilePath: String,
        nodeId: String = NODE_ID
    ): Result<Address, Exception> {
        val btcAddress = generateKeyAndAddress(walletFilePath, nodeId)
        return btcFreeAddress.createFreeAddress(btcAddress)
            .map { Address.fromBase58(RegTestParams.get(), btcAddress.address) }
    }

    /**
     * Generates one BTC address that can be registered later
     * @param walletFilePath - path to wallet file
     * @return randomly generated BTC address
     */
    fun genChangeBtcAddress(walletFilePath: String): Result<Address, Exception> {
        val btcAddress = generateKeyAndAddress(walletFilePath, "any_node")
        return ModelUtil.setAccountDetail(
            mstRegistrationIrohaConsumer,
            accountHelper.changeAddressesStorageAccount.accountId,
            btcAddress.address,
            btcAddress.info.toJson().irohaEscape()
        ).map { Address.fromBase58(RegTestParams.get(), btcAddress.address) }
    }

    // Generates key and key based address
    private fun generateKeyAndAddress(walletFilePath: String, nodeId: String): BtcAddress {
        val walletFile = File(walletFilePath)
        val wallet = Wallet.loadFromFile(walletFile)
        val key = wallet.freshReceiveKey()
        val address = createMsAddress(listOf(key.publicKeyAsHex), RegTestParams.get())
        wallet.addWatchedAddress(address)
        wallet.saveToFile(walletFile)
        logger.info { "generated address $address" }
        return BtcAddress(
            address.toBase58(),
            AddressInfo.createFreeAddressInfo(
                listOf(key.publicKeyAsHex),
                nodeId,
                System.currentTimeMillis()
            )
        )
    }

    /**
     * Registers BTC client
     * @param walletFilePath - path to wallet file
     * @param irohaAccountName - client account in Iroha
     * @param keypair - key pair for new client in Iroha
     * @return btc address related to client
     */
    fun registerBtcAddress(
        walletFilePath: String,
        irohaAccountName: String,
        domain: String,
        keypair: KeyPair = ModelUtil.generateKeypair()
    ): String {
        genFreeBtcAddress(walletFilePath).fold({
            return registerBtcAddressNoPreGen(irohaAccountName, domain, keypair)
        }, { ex -> throw ex })
    }

    /**
     * Creates random Bitcoin address
     */
    fun createBtcAddress() = Wallet(RegTestParams.get()).freshReceiveAddress().toBase58()

    /**
     * Registers BTC client with no generation
     * @param irohaAccountName - client account in Iroha
     * @param keypair - key pair of new client in Iroha
     * @return btc address related to client
     */
    fun registerBtcAddressNoPreGen(
        irohaAccountName: String,
        domain: String,
        keypair: KeyPair = ModelUtil.generateKeypair()
    ): String {
        btcRegistrationStrategy.register(
            irohaAccountName,
            domain,
            keypair.public.toHexString()
        )
            .fold({ btcAddress ->
                logger.info { "BTC address $btcAddress was registered for $irohaAccountName@$domain" }
                return btcAddress
            }, { ex -> throw ex })
    }

    /**
     * Sends btc to a given address
     */
    fun sendBtc(address: String, amount: BigDecimal, confirmations: Int = 6) {
        logger.info { "Send $amount BTC to $address" }
        rpcClient.sendToAddress(address = address, amount = amount)
        generateBtcBlocks(confirmations)
    }

    /**
     * Send sat to a given address
     */
    fun sendSat(address: String, amount: Int, confirmations: Int = 6) {
        logger.info { "Send $amount SAT to $address" }
        rpcClient.sendToAddress(address = address, amount = satToBtc(amount.toLong()))
        generateBtcBlocks(confirmations)
    }

    /**
     * Creates blocks in bitcoin blockchain. May be used as transaction confirmation mechanism.
     */
    fun generateBtcBlocks(blocks: Int) {
        if (blocks > 0) {
            rpcClient.generate(numberOfBlocks = blocks)
            logger.info { "New $blocks ${singularOrPluralBlocks(blocks)} generated in Bitcoin blockchain" }
        }
    }

    /**
     * Creates initial blocks in bitcoin blockchain if needed.
     * After calling this function you will be available to call 'sendToAddress' command
     */
    fun generateBtcInitialBlocks() {
        val currentBlockCount = rpcClient.getBlockCount()
        if (currentBlockCount >= BTC_INITIAL_BLOCKS) {
            logger.info { "No need to create initial blocks" }
        } else {
            generateBtcBlocks(BTC_INITIAL_BLOCKS - currentBlockCount)
            logger.info { "Initial blocks were generated" }
        }
    }

    private fun singularOrPluralBlocks(blocks: Int): String {
        if (blocks == 1) {
            return "block was"
        }
        return "blocks were"
    }

    /**
     * Triggers expansion process
     * @param accountId - account to expand
     * @param publicKey - new public key
     * @param quorum - new quorum
     */
    fun triggerExpansion(
        accountId: String,
        publicKey: String,
        quorum: Int
    ) {
        val expansionDetails = ExpansionDetails(accountId, publicKey, quorum)
        IrohaConsumerImpl(
            accountHelper.superuserAccount,
            irohaAPI
        ).send(
            ExpansionUtils.createExpansionTriggerTx(
                accountId,
                expansionDetails,
                accountHelper.expansionTriggerAccount.accountId
            )
        ).failure { ex -> throw ex }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
