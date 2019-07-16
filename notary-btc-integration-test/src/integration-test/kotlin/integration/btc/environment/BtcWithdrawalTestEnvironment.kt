/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc.environment

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.btc.wallet.WalletInitializer
import com.d3.btc.wallet.loadAutoSaveWallet
import com.d3.btc.withdrawal.config.BTC_WITHDRAWAL_SERVICE_NAME
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.expansion.WithdrawalServiceExpansion
import com.d3.btc.withdrawal.handler.*
import com.d3.btc.withdrawal.init.BtcWithdrawalInitialization
import com.d3.btc.withdrawal.provider.BroadcastsProvider
import com.d3.btc.withdrawal.provider.UTXOProvider
import com.d3.btc.withdrawal.provider.UsedUTXOProvider
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.service.FeeService
import com.d3.btc.withdrawal.service.WithdrawalTransferService
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.*
import com.d3.commons.config.RMQConfig
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.provider.NotaryPeerListProviderImpl
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.util.createPrettySingleThreadPool
import com.rabbitmq.client.ConnectionFactory
import integration.btc.FAILED_WITHDRAW_AMOUNT
import integration.helper.BtcIntegrationHelperUtil
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.bootstrap.changelog.ChangelogInterface
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.Wallet
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitcoin withdrawal service testing environment
 */
class BtcWithdrawalTestEnvironment(
    private val integrationHelper: BtcIntegrationHelperUtil,
    testName: String = "",
    val btcWithdrawalConfig: BtcWithdrawalConfig = integrationHelper.configHelper.createBtcWithdrawalConfig(
        testName
    ),
    val bitcoinConfig: BitcoinConfig = integrationHelper.configHelper.createBitcoinConfig(testName),
    private val withdrawalCredential: IrohaCredential =
        IrohaCredential(
            btcWithdrawalConfig.withdrawalCredential.accountId,
            Utils.parseHexKeypair(
                btcWithdrawalConfig.withdrawalCredential.pubkey,
                btcWithdrawalConfig.withdrawalCredential.privkey
            )
        )
) : Closeable {

    val createdTransactions = ConcurrentHashMap<String, Pair<Long, Transaction>>()

    /**
     * It's essential to handle blocks in this service one-by-one.
     * This is why we explicitly set single threaded executor.
     */
    private val executor =
        createPrettySingleThreadPool(BTC_WITHDRAWAL_SERVICE_NAME, "iroha-chain-listener")

    val rmqConfig =
        loadRawLocalConfigs("rmq", RMQConfig::class.java, "rmq.properties")

    /**
     * Binds RabbitMQ queue with exchange.
     * @param queue - queue to bind with exchange
     * @param exchange - exchange to bind with queue
     */
    fun bindQueueWithExchange(queue: String, exchange: String) {
        val factory = ConnectionFactory()
        factory.host = rmqConfig.host
        factory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.exchangeDeclare(rmqConfig.irohaExchange, "fanout", true)
                channel.queueDeclare(queue, true, false, false, null)
                channel.queueBind(queue, exchange, "")
            }
        }
    }

    val transferWallet by lazy {
        loadAutoSaveWallet(btcWithdrawalConfig.btcTransfersWalletPath)
    }

    private val irohaApi by lazy {
        val irohaAPI = IrohaAPI(
            btcWithdrawalConfig.iroha.hostname,
            btcWithdrawalConfig.iroha.port
        )
        irohaAPI.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                btcWithdrawalConfig.iroha.hostname,
                btcWithdrawalConfig.iroha.port
            ).executor(executor).usePlaintext().build()
        )
        irohaAPI
    }

    private val signaturesCollectorKeypair = Utils.parseHexKeypair(
        btcWithdrawalConfig.signatureCollectorCredential.pubkey,
        btcWithdrawalConfig.signatureCollectorCredential.privkey
    )

    private val signaturesCollectorCredential =
        IrohaCredential(
            btcWithdrawalConfig.signatureCollectorCredential.accountId,
            signaturesCollectorKeypair
        )

    private val btcConsensusKeyPair = Utils.parseHexKeypair(
        btcWithdrawalConfig.btcConsensusCredential.pubkey,
        btcWithdrawalConfig.btcConsensusCredential.privkey
    )

    private val broadcastKeyPair = Utils.parseHexKeypair(
        btcWithdrawalConfig.broadcastsCredential.pubkey,
        btcWithdrawalConfig.broadcastsCredential.privkey
    )

    private val btcConsensusCredential =
        IrohaCredential(btcWithdrawalConfig.btcConsensusCredential.accountId, btcConsensusKeyPair)

    private val broadcastCredential =
        IrohaCredential(btcWithdrawalConfig.broadcastsCredential.accountId, broadcastKeyPair)

    private val broadcastIrohaConsumer = IrohaConsumerImpl(
        broadcastCredential,
        irohaApi
    )

    private val broadcastsProvider = BroadcastsProvider(broadcastIrohaConsumer, integrationHelper.queryHelper)

    private val withdrawalIrohaConsumer = IrohaConsumerImpl(
        withdrawalCredential,
        irohaApi
    )

    private val withdrawalIrohaConsumerMultiSig = MultiSigIrohaConsumer(
        withdrawalCredential,
        irohaApi
    )

    private val feeService = FeeService(btcWithdrawalConfig, withdrawalIrohaConsumerMultiSig)

    private val signaturesCollectorIrohaConsumer = IrohaConsumerImpl(
        signaturesCollectorCredential,
        irohaApi
    )

    private val btcConsensusIrohaConsumer = IrohaConsumerImpl(btcConsensusCredential, irohaApi)

    val btcRegisteredAddressesProvider = BtcRegisteredAddressesProvider(
        integrationHelper.queryHelper,
        btcWithdrawalConfig.registrationCredential.accountId,
        integrationHelper.accountHelper.notaryAccount.accountId
    )

    private val btcNetworkConfigProvider = BtcRegTestConfigProvider()

    val btcChangeAddressProvider = BtcChangeAddressProvider(
        integrationHelper.queryHelper,
        btcWithdrawalConfig.mstRegistrationAccount,
        btcWithdrawalConfig.changeAddressesStorageAccount
    )

    private val walletInitializer by lazy {
        WalletInitializer(btcRegisteredAddressesProvider, btcChangeAddressProvider)
    }

    private val peerGroup by lazy {
        integrationHelper.getPeerGroup(
            transferWallet,
            btcNetworkConfigProvider,
            bitcoinConfig.blockStoragePath,
            BitcoinConfig.extractHosts(bitcoinConfig),
            walletInitializer
        )
    }

    val usedUTXOProvider =
        UsedUTXOProvider(integrationHelper.queryHelper, withdrawalIrohaConsumer, btcWithdrawalConfig.utxoStorageAccount)

    val utxoProvider =
        BlackListableBitcoinUTXOProvider(
            transferWallet,
            peerGroup,
            btcNetworkConfigProvider,
            btcRegisteredAddressesProvider,
            btcChangeAddressProvider,
            usedUTXOProvider
        )

    val transactionsStorage =
        TransactionsStorage(
            BtcRegTestConfigProvider(),
            integrationHelper.queryHelper,
            withdrawalIrohaConsumer,
            btcWithdrawalConfig.txStorageAccount,
            btcWithdrawalConfig.utxoStorageAccount,
            btcConsensusCredential
        )
    private val transactionCreator =
        TransactionCreator(btcChangeAddressProvider, btcNetworkConfigProvider, utxoProvider, transactionsStorage)
    private val transactionSigner =
        TransactionSigner(btcRegisteredAddressesProvider, btcChangeAddressProvider, transferWallet)
    val signCollector =
        SignCollector(
            signaturesCollectorCredential,
            signaturesCollectorIrohaConsumer,
            irohaApi,
            transactionSigner,
            transferWallet
        )

    private val withdrawalStatistics = WithdrawalStatistics.create()
    private val notaryPeerListProvider = NotaryPeerListProviderImpl(
        integrationHelper.queryHelper,
        btcWithdrawalConfig.notaryListStorageAccount,
        btcWithdrawalConfig.notaryListSetterAccount
    )
    private val btcRollbackService =
        BtcRollbackService(withdrawalIrohaConsumerMultiSig)
    private val withdrawalConsensusProvider = WithdrawalConsensusProvider(
        withdrawalCredential,
        btcConsensusIrohaConsumer,
        integrationHelper.queryHelper,
        notaryPeerListProvider,
        utxoProvider,
        bitcoinConfig
    )
    private val newChangeAddressHandler
            by lazy { NewChangeAddressHandler(transferWallet, btcNetworkConfigProvider) }
    private val newTransferHandler =
        NewTransferHandler(
            withdrawalStatistics,
            btcWithdrawalConfig,
            withdrawalConsensusProvider,
            btcRollbackService,
            broadcastsProvider
        )
    private val withdrawalTransferService = object : WithdrawalTransferService(
        withdrawalStatistics,
        bitcoinConfig,
        transactionCreator,
        btcRollbackService
    ) {
        override fun registerWithdrawal(withdrawalDetails: WithdrawalDetails) {
            if (withdrawalDetails.amountSat == FAILED_WITHDRAW_AMOUNT) {
                throw Exception("Failed withdraw test")
            }
            super.registerWithdrawal(withdrawalDetails)
        }

        override fun registerTx(tx: Transaction) {
            super.registerTx(tx)
            createdTransactions[tx.hashAsString] = Pair(System.currentTimeMillis(), tx)
        }
    }

    val newSignatureEventHandler =
        NewSignatureEventHandler(
            withdrawalStatistics,
            signCollector,
            transactionsStorage,
            utxoProvider,
            btcRollbackService,
            peerGroup,
            broadcastsProvider
        )

    private val broadcastTransactionHandler = BroadcastTransactionHandler(feeService)

    private val newConsensusDataHandler =
        NewConsensusDataHandler(withdrawalTransferService, withdrawalConsensusProvider, btcRollbackService)

    private val newTransactionCreatedHandler =
        NewTransactionCreatedHandler(
            signCollector,
            transactionsStorage,
            btcWithdrawalConfig,
            btcRollbackService,
            utxoProvider,
            broadcastsProvider
        )

    val btcWithdrawalInitialization by lazy {
        BtcWithdrawalInitialization(
            btcWithdrawalConfig,
            peerGroup,
            transferWallet,
            btcChangeAddressProvider,
            btcNetworkConfigProvider,
            newSignatureEventHandler,
            NewBtcClientRegistrationHandler(btcNetworkConfigProvider),
            newTransferHandler,
            newChangeAddressHandler,
            newConsensusDataHandler,
            newTransactionCreatedHandler,
            broadcastTransactionHandler,
            WithdrawalServiceExpansion(
                irohaApi,
                ServiceExpansion(
                    integrationHelper.accountHelper.expansionTriggerAccount.accountId,
                    ChangelogInterface.superuserAccountId,
                    irohaApi
                ),
                withdrawalCredential
            ),
            rmqConfig
        )
    }

    fun getLastCreatedTxHash() =
        createdTransactions.maxBy { createdTransactionEntry -> createdTransactionEntry.value.first }!!.key

    fun getLastCreatedTx() =
        createdTransactions.maxBy { createdTransactionEntry -> createdTransactionEntry.value.first }!!.value.second

    class BlackListableBitcoinUTXOProvider(
        transferWallet: Wallet,
        peerGroup: SharedPeerGroup,
        btcNetworkConfigProvider: BtcNetworkConfigProvider,
        btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
        btcChangeAddressProvider: BtcChangeAddressProvider,
        usedUTXOProvider: UsedUTXOProvider
    ) : UTXOProvider(
        transferWallet,
        peerGroup,
        btcNetworkConfigProvider,
        btcRegisteredAddressesProvider,
        btcChangeAddressProvider,
        usedUTXOProvider

    ) {
        //Collection of "blacklisted" addresses. For testing purposes only
        private val btcAddressBlackList = HashSet<String>()

        /**
         * Adds address to black list. It makes given address money unable to spend
         * @param btcAddress - address to add in black list
         */
        fun addToBlackList(btcAddress: String) {
            btcAddressBlackList.add(btcAddress)
        }

        // Checks if transaction output was addressed to available address
        override fun isAvailableOutput(
            availableAddresses: Set<String>,
            output: TransactionOutput
        ): Boolean {
            val btcAddress = outPutToBase58Address(output)
            return availableAddresses.contains(btcAddress) && !btcAddressBlackList.contains(
                btcAddress
            )
        }
    }

    override fun close() {
        irohaApi.close()
        integrationHelper.close()
        executor.shutdownNow()
        File(bitcoinConfig.blockStoragePath).deleteRecursively()
        btcWithdrawalInitialization.close()
    }
}
