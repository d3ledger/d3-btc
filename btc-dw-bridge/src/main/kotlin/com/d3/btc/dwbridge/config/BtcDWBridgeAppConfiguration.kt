/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.dwbridge.config

import com.d3.btc.config.BitcoinConfig.Companion.extractHosts
import com.d3.btc.deposit.config.BTC_DEPOSIT_SERVICE_NAME
import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.dwbridge.BTC_DW_BRIDGE_SERVICE_NAME
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.WalletInitializer
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.btc.wallet.loadAutoSaveWallet
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.commons.config.RMQConfig
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.notary.NotaryImpl
import com.d3.commons.provider.NotaryPeerListProviderImpl
import com.d3.commons.sidechain.SideChainEvent
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import io.grpc.ManagedChannelBuilder
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.wallet.Wallet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

val withdrawalConfig =
    loadLocalConfigs(
        "btc-withdrawal",
        BtcWithdrawalConfig::class.java,
        "withdrawal.properties"
    ).get()
val depositConfig =
    loadLocalConfigs("btc-deposit", BtcDepositConfig::class.java, "deposit.properties").get()
val dwBridgeConfig =
    loadLocalConfigs("btc-dw-bridge", BtcDWBridgeConfig::class.java, "dw-bridge.properties").get()

@Configuration
class BtcDWBridgeAppConfiguration {

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

    private val notaryCredential =
        IrohaCredential(depositConfig.notaryCredential.accountId, notaryKeypair)

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
    fun notary() =
        NotaryImpl(
            MultiSigIrohaConsumer(notaryCredential, irohaAPI()),
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
    fun registeredClientsListenerExecutor() =
        createPrettySingleThreadPool(BTC_DW_BRIDGE_SERVICE_NAME, "reg-clients-listener")

    @Bean
    fun btcRegisteredAddressesProvider(): BtcRegisteredAddressesProvider {
        return BtcRegisteredAddressesProvider(
            IrohaQueryHelperImpl(
                irohaAPI(),
                depositConfig.notaryCredential.accountId,
                notaryKeypair
            ),
            depositConfig.registrationAccount,
            depositConfig.notaryCredential.accountId
        )
    }

    @Bean
    fun signatureCollectorCredential() =
        IrohaCredential(
            withdrawalConfig.signatureCollectorCredential.accountId,
            signatureCollectorKeypair
        )

    @Bean
    fun signatureCollectorConsumer() = IrohaConsumerImpl(signatureCollectorCredential(), irohaAPI())

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
    fun withdrawalConsumer() = MultiSigIrohaConsumer(withdrawalCredential(), irohaAPI())

    @Bean
    fun withdrawalConfig() = withdrawalConfig

    @Bean
    fun depositIrohaChainListener() = IrohaChainListener(
        dwBridgeConfig.iroha.hostname,
        dwBridgeConfig.iroha.port,
        notaryCredential
    )

    @Bean
    fun withdrawalQueryHelper() =
        IrohaQueryHelperImpl(
            irohaAPI(),
            withdrawalCredential().accountId,
            withdrawalCredential().keyPair
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
    fun blockStoragePath() = dwBridgeConfig.bitcoin.blockStoragePath

    @Bean
    fun bitcoinConfig() = dwBridgeConfig.bitcoin

    @Bean
    fun btcHosts() = extractHosts(dwBridgeConfig.bitcoin)

    @Bean
    fun notaryPeerListProvider() =
        NotaryPeerListProviderImpl(
            withdrawalQueryHelper(),
            withdrawalConfig.notaryListStorageAccount,
            withdrawalConfig.notaryListSetterAccount
        )

    @Bean
    fun walletInitializer() =
        WalletInitializer(btcRegisteredAddressesProvider(), btcChangeAddressProvider())

    @Bean
    fun serviceExpansion() =
        ServiceExpansion(dwBridgeConfig.expansionTriggerAccount, irohaAPI())

    @Bean
    fun dnsSeed() = dwBridgeConfig.dnsSeedAddress

    @Bean
    fun txStorageAccount() = withdrawalConfig.txStorageAccount
}
