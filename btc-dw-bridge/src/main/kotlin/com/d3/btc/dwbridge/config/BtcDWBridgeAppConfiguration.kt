/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.dwbridge.config

import com.d3.btc.config.BitcoinConfig.Companion.extractHosts
import com.d3.btc.deposit.config.BTC_DEPOSIT_SERVICE_NAME
import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.deposit.handler.NewBtcChangeAddressDepositHandler
import com.d3.btc.dwbridge.BTC_DW_BRIDGE_SERVICE_NAME
import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.btc.peer.SharedPeerGroupConfig
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.wallet.WalletInitializer
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.btc.wallet.loadAutoSaveWallet
import com.d3.btc.withdrawal.config.BTC_WITHDRAWAL_SERVICE_NAME
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.handler.*
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.notary.NotaryImpl
import com.d3.commons.service.WithdrawalFinalizer
import com.d3.commons.sidechain.SideChainEvent
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.iroha.util.impl.RobustIrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import com.d3.reverse.adapter.ReverseChainAdapter
import com.d3.reverse.client.ReliableIrohaConsumerImpl
import com.d3.reverse.client.ReverseChainAdapterClientConfig
import com.d3.reverse.config.ReverseChainAdapterConfig
import io.grpc.ManagedChannelBuilder
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val withdrawalConfig =
    loadLocalConfigs(
        "btc-withdrawal",
        BtcWithdrawalConfig::class.java,
        "withdrawal.properties"
    ).get()

private val depositConfig =
    loadLocalConfigs("btc-deposit", BtcDepositConfig::class.java, "deposit.properties").get()
val dwBridgeConfig =
    loadLocalConfigs("btc-dw-bridge", BtcDWBridgeConfig::class.java, "dw-bridge.properties").get()

@Configuration
class BtcDWBridgeAppConfiguration {

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

    private val rmqConfig =
        loadRawLocalConfigs("rmq", RMQConfig::class.java, "rmq.properties")

    private val withdrawalKeypair = Utils.parseHexKeypair(
        withdrawalConfig.withdrawalCredential.pubkey,
        withdrawalConfig.withdrawalCredential.privkey
    )
    private val notaryKeypair = Utils.parseHexKeypair(
        depositConfig.notaryCredential.pubkey,
        depositConfig.notaryCredential.privkey
    )

    private val signatureCollectorKeypair = Utils.parseHexKeypair(
        withdrawalConfig.signatureCollectorCredential.pubkey,
        withdrawalConfig.signatureCollectorCredential.privkey
    )

    private val btcConsensusCredential =
        IrohaCredential(
            withdrawalConfig.btcConsensusCredential.accountId, Utils.parseHexKeypair(
                withdrawalConfig.btcConsensusCredential.pubkey,
                withdrawalConfig.btcConsensusCredential.privkey
            )
        )

    private val broadcastCredential =
        IrohaCredential(
            withdrawalConfig.broadcastsCredential.accountId, Utils.parseHexKeypair(
                withdrawalConfig.broadcastsCredential.pubkey,
                withdrawalConfig.broadcastsCredential.privkey
            )
        )

    private val notaryCredential =
        IrohaCredential(depositConfig.notaryCredential.accountId, notaryKeypair)

    private val signatureCollectorCredential = IrohaCredential(
        withdrawalConfig.signatureCollectorCredential.accountId,
        signatureCollectorKeypair
    )

    @Bean
    fun notaryCredential() = notaryCredential

    @Bean
    fun consensusIrohaCredential() = btcConsensusCredential

    @Bean
    fun consensusIrohaConsumer() = IrohaConsumerImpl(consensusIrohaCredential(), irohaAPI())

    @Bean
    fun confidenceListenerExecutorService() =
        createPrettySingleThreadPool(BTC_DEPOSIT_SERVICE_NAME, "tx-confidence-listener")

    @Bean
    fun btcEventsSource(): PublishSubject<SideChainEvent.PrimaryBlockChainEvent> {
        return PublishSubject.create<SideChainEvent.PrimaryBlockChainEvent>()
    }

    @Bean
    fun btcEventsObservable(): Observable<SideChainEvent.PrimaryBlockChainEvent> {
        return btcEventsSource()
    }

    @Bean
    fun reliableNotaryIrohaConsumer() =
        ReliableIrohaConsumerImpl(reverseChainAdapterClientConfig, notaryCredential, irohaAPI(), fireAndForget = true)

    @Bean
    fun notary() =
        NotaryImpl(
            reliableNotaryIrohaConsumer(),
            notaryCredential,
            btcEventsObservable()
        )

    @Bean
    fun rmqConfig() = rmqConfig

    @Bean
    fun notaryConfig() = depositConfig

    @Bean
    fun healthCheckPort() = dwBridgeConfig.healthCheckPort

    @Bean
    fun webPort() = dwBridgeConfig.webPort

    @Bean
    fun irohaAPI(): IrohaAPI {
        val irohaAPI = IrohaAPI(dwBridgeConfig.iroha.hostname, dwBridgeConfig.iroha.port)
        /**
         * It's essential to handle blocks in this service one-by-one.
         * This is why we explicitly set single threaded executor.
         */
        irohaAPI.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                dwBridgeConfig.iroha.hostname,
                dwBridgeConfig.iroha.port
            ).executor(
                createPrettySingleThreadPool(
                    BTC_DW_BRIDGE_SERVICE_NAME,
                    "iroha-chain-listener"
                )
            ).usePlaintext().build()
        )
        return irohaAPI
    }

    @Bean
    fun signatureCollectorQueryHelper() = RobustIrohaQueryHelperImpl(
        IrohaQueryHelperImpl(irohaAPI(), signatureCollectorCredential),
        dwBridgeConfig.irohaQueryTimeoutMls
    )

    @Bean
    fun notaryQueryHelper() = RobustIrohaQueryHelperImpl(
        IrohaQueryHelperImpl(
            irohaAPI(),
            depositConfig.notaryCredential.accountId,
            notaryKeypair
        ), dwBridgeConfig.irohaQueryTimeoutMls
    )

    @Bean
    fun btcRegisteredAddressesProvider(): BtcRegisteredAddressesProvider {
        return BtcRegisteredAddressesProvider(
            notaryQueryHelper(),
            depositConfig.registrationAccount,
            depositConfig.notaryCredential.accountId
        )
    }

    @Bean
    fun signatureCollectorConsumer() = IrohaConsumerImpl(signatureCollectorCredential, irohaAPI())

    @Bean
    fun transferWallet(networkProvider: BtcNetworkConfigProvider): Wallet {
        val walletPath = depositConfig.btcTransferWalletPath
        createWalletIfAbsent(walletPath, networkProvider)
        return loadAutoSaveWallet(walletPath)
    }

    @Bean
    fun withdrawalStatistics() = WithdrawalStatistics.create()

    @Bean
    fun withdrawalCredential() =
        IrohaCredential(withdrawalConfig.withdrawalCredential.accountId, withdrawalKeypair)

    @Bean
    fun withdrawalConsumer() = IrohaConsumerImpl(withdrawalCredential(), irohaAPI())

    @Bean
    fun reliableWithdrawalConsumer() =
        ReliableIrohaConsumerImpl(
            reverseChainAdapterClientConfig,
            withdrawalCredential(),
            irohaAPI(),
            fireAndForget = true
        )

    @Bean
    fun withdrawalConsumerMultiSig() = MultiSigIrohaConsumer(withdrawalCredential(), irohaAPI())

    @Bean
    fun withdrawalConfig() = withdrawalConfig

    @Bean
    fun broadcastsIrohaConsumer() = IrohaConsumerImpl(broadcastCredential, irohaAPI())

    @Bean
    fun withdrawalQueryHelper() = RobustIrohaQueryHelperImpl(
        IrohaQueryHelperImpl(
            irohaAPI(),
            withdrawalCredential().accountId,
            withdrawalCredential().keyPair
        ), dwBridgeConfig.irohaQueryTimeoutMls
    )

    @Bean
    fun btcChangeAddressProvider(): BtcChangeAddressProvider {
        return BtcChangeAddressProvider(
            withdrawalQueryHelper(),
            withdrawalConfig.mstRegistrationAccount,
            withdrawalConfig.changeAddressesStorageAccount
        )
    }


    @Bean
    fun bitcoinConfig() = dwBridgeConfig.bitcoin


    @Bean
    fun btcAddressStorage() = BtcAddressStorage(btcRegisteredAddressesProvider(), btcChangeAddressProvider())

    @Bean
    fun walletInitializer() =
        WalletInitializer(btcRegisteredAddressesProvider(), btcChangeAddressProvider())

    @Bean
    fun serviceExpansion() =
        ServiceExpansion(
            dwBridgeConfig.expansionTriggerAccount,
            dwBridgeConfig.expansionTriggerCreatorAccountId,
            irohaAPI()
        )

    @Bean
    fun sharedPeerGroupConfig() = SharedPeerGroupConfig(
        blockStoragePath = dwBridgeConfig.bitcoin.blockStoragePath,
        minBlockHeightForPeer = dwBridgeConfig.minBlockHeightForPeer,
        hosts = extractHosts(dwBridgeConfig.bitcoin),
        dnsSeeds = BtcDWBridgeConfig.extractSeeds(dwBridgeConfig)
    )

    @Bean
    fun txStorageAccount() = withdrawalConfig.txStorageAccount

    @Bean
    fun utxoStorageAccount() = withdrawalConfig.utxoStorageAccount

    /*
       I could have made it simpler by using `@Autowired handler:List<SetAccountDetailHandler>`,
       but I'm really worry that there will be unwanted handlers in IoC.
       This is why I explicitly define handlers that I want to run.
     */
    @Bean
    fun withdrawalHandlers(
        broadcastTransactionHandler: BroadcastTransactionHandler,
        newTransactionCreatedHandler: NewTransactionCreatedHandler,
        newSignatureEventHandler: NewSignatureEventHandler,
        newConsensusDataHandler: ConsensusDataCreatedHandler,
        newBtcClientRegistrationHandler: NewBtcClientRegistrationHandler,
        newChangeAddressHandler: NewBtcChangeAddressWithdrawalHandler,
        rollbackHandler: RollbackHandler
    ) = listOf(
        broadcastTransactionHandler,
        newTransactionCreatedHandler,
        newSignatureEventHandler,
        newConsensusDataHandler,
        newBtcClientRegistrationHandler,
        newChangeAddressHandler,
        rollbackHandler
    )

    @Bean
    fun depositHandlers(
        newBtcClientRegistrationHandler: NewBtcClientRegistrationHandler,
        newBtcChangeAddressHandler: NewBtcChangeAddressDepositHandler
    ) = listOf(newBtcClientRegistrationHandler, newBtcChangeAddressHandler)

    @Bean
    fun withdrawalFinalizer() =
        WithdrawalFinalizer(withdrawalConsumerMultiSig(), withdrawalConfig.withdrawalBillingAccount)

    @Bean
    fun reverseChainAdapter() = ReverseChainAdapter(reverseAdapterConfig, irohaAPI())

    @Bean
    fun depositReliableIrohaChainListener() = ReliableIrohaChainListener(
        rmqConfig, depositConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_DEPOSIT_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = true
    )

    @Bean
    fun withdrawalReliableIrohaChainListener() = ReliableIrohaChainListener(
        rmqConfig, withdrawalConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_WITHDRAWAL_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = false
    )

    @Bean
    fun newBtcClientRegistrationHandler(
        btcNetworkConfigProvider: BtcNetworkConfigProvider,
        @Qualifier("transferWallet")
        transferWallet: Wallet,
        btcAddressStorage: BtcAddressStorage
    ) =
        NewBtcClientRegistrationHandler(
            btcNetworkConfigProvider,
            transferWallet,
            btcAddressStorage,
            withdrawalConfig.registrationCredential.accountId
        )
}
