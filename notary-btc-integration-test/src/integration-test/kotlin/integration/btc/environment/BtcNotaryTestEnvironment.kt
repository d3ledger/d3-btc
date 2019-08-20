/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc.environment

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.deposit.config.BTC_DEPOSIT_SERVICE_NAME
import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.deposit.expansion.DepositServiceExpansion
import com.d3.btc.deposit.handler.NewBtcChangeAddressDepositHandler
import com.d3.btc.deposit.init.BtcNotaryInitialization
import com.d3.btc.deposit.service.BtcWalletListenerRestartService
import com.d3.btc.dwbridge.config.depositConfig
import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.wallet.WalletInitializer
import com.d3.btc.wallet.loadAutoSaveWallet
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.notary.NotaryImpl
import com.d3.commons.sidechain.SideChainEvent
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import com.d3.reverse.adapter.ReverseChainAdapter
import com.d3.reverse.client.ReliableIrohaConsumerImpl
import com.d3.reverse.client.ReverseChainAdapterClientConfig
import com.d3.reverse.config.ReverseChainAdapterConfig
import integration.helper.BtcIntegrationHelperUtil
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import jp.co.soramitsu.bootstrap.changelog.ChangelogInterface
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.wallet.Wallet
import java.io.Closeable
import java.io.File

/**
 * Bitcoin notary service testing environment
 */
class BtcNotaryTestEnvironment(
    private val integrationHelper: BtcIntegrationHelperUtil,
    testName: String = "",
    val notaryConfig: BtcDepositConfig = integrationHelper.configHelper.createBtcDepositConfig(
        testName
    ),
    val bitcoinConfig: BitcoinConfig = integrationHelper.configHelper.createBitcoinConfig(testName),
    private val notaryCredential: IrohaCredential = IrohaCredential(
        notaryConfig.notaryCredential.accountId,
        Utils.parseHexKeypair(
            notaryConfig.notaryCredential.pubkey,
            notaryConfig.notaryCredential.privkey
        )
    )
) : Closeable {

    private val reverseAdapterConfig =
        loadRawLocalConfigs(
            "reverse-chain-adapter",
            ReverseChainAdapterConfig::class.java,
            "reverse-chain-adapter.properties"
        )

    private val reverseChainAdapterClientConfig = object : ReverseChainAdapterClientConfig {
        override val rmqHost = reverseAdapterConfig.rmqHost
        override val rmqPort = reverseAdapterConfig.rmqPort
        override val transactionQueueName = reverseAdapterConfig.transactionQueueName
    }

    private val irohaAPI = IrohaAPI(notaryConfig.iroha.hostname, notaryConfig.iroha.port)

    private val queryHelper =
        IrohaQueryHelperImpl(irohaAPI, notaryCredential.accountId, notaryCredential.keyPair)

    private val btcRegisteredAddressesProvider = BtcRegisteredAddressesProvider(
        queryHelper,
        notaryConfig.registrationAccount,
        notaryConfig.notaryCredential.accountId
    )

    val btcAddressGenerationConfig =
        integrationHelper.configHelper.createBtcAddressGenerationConfig(0)

    private val btcNetworkConfigProvider = BtcRegTestConfigProvider()

    private val transferWallet by lazy { loadAutoSaveWallet(notaryConfig.btcTransferWalletPath) }

    private val rmqConfig =
        loadRawLocalConfigs("rmq", RMQConfig::class.java, "rmq.properties")

    private val depositReliableIrohaChainListener = ReliableIrohaChainListener(
        rmqConfig, depositConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_DEPOSIT_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = true
    )

    val btcAddressStorage by lazy {
        BtcAddressStorage(btcRegisteredAddressesProvider, btcChangeAddressProvider)
    }

    private val depositHandlers by lazy {
        listOf(
            NewBtcClientRegistrationHandler(btcNetworkConfigProvider, transferWallet, btcAddressStorage),
            NewBtcChangeAddressDepositHandler(btcAddressStorage, depositConfig)
        )
    }

    private val peerGroup by lazy {
        createPeerGroup(transferWallet)
    }

    private val btcChangeAddressProvider = BtcChangeAddressProvider(
        queryHelper,
        notaryConfig.mstRegistrationAccount,
        notaryConfig.changeAddressesStorageAccount
    )

    private val walletInitializer by lazy {
        WalletInitializer(btcRegisteredAddressesProvider, btcChangeAddressProvider)
    }

    fun createPeerGroup(transferWallet: Wallet): SharedPeerGroup {
        return integrationHelper.getPeerGroup(
            transferWallet,
            btcNetworkConfigProvider,
            bitcoinConfig.blockStoragePath,
            BitcoinConfig.extractHosts(bitcoinConfig),
            walletInitializer
        )
    }

    private val confidenceExecutorService =
        createPrettySingleThreadPool(BTC_DEPOSIT_SERVICE_NAME, "tx-confidence-listener")

    private val btcEventsSource = PublishSubject.create<SideChainEvent.PrimaryBlockChainEvent>()

    private val btcEventsObservable: Observable<SideChainEvent.PrimaryBlockChainEvent> =
        btcEventsSource

    private val reverseChainAdapterDelegate = lazy { ReverseChainAdapter(reverseAdapterConfig, irohaAPI) }

    val reverseChainAdapter by reverseChainAdapterDelegate

    private val reliableIrohaNotaryConsumer =
        ReliableIrohaConsumerImpl(reverseChainAdapterClientConfig, notaryCredential, irohaAPI, fireAndForget = true)

    private val notary = NotaryImpl(
        reliableIrohaNotaryConsumer,
        notaryCredential,
        btcEventsObservable
    )

    private val btcWalletListenerRestartService by lazy {
        BtcWalletListenerRestartService(
            btcAddressStorage,
            bitcoinConfig,
            confidenceExecutorService,
            peerGroup,
            btcEventsSource
        )
    }

    val btcNotaryInitialization by lazy {
        BtcNotaryInitialization(
            peerGroup,
            transferWallet,
            notaryConfig,
            bitcoinConfig,
            notary,
            btcEventsSource,
            btcWalletListenerRestartService,
            confidenceExecutorService,
            btcNetworkConfigProvider,
            DepositServiceExpansion(
                irohaAPI,
                ServiceExpansion(
                    integrationHelper.accountHelper.expansionTriggerAccount.accountId,
                    ChangelogInterface.superuserAccountId,
                    irohaAPI
                ), notaryCredential
            ),
            depositReliableIrohaChainListener,
            btcAddressStorage, depositHandlers
        )
    }

    override fun close() {
        if (reverseChainAdapterDelegate.isInitialized()) {
            reverseChainAdapter.close()
        }
        integrationHelper.close()
        irohaAPI.close()
        confidenceExecutorService.shutdownNow()
        depositReliableIrohaChainListener.close()
        //Clear bitcoin blockchain folder
        File(bitcoinConfig.blockStoragePath).deleteRecursively()
        btcNotaryInitialization.close()
    }
}
