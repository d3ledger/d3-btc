/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.config

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.generation.handler.BtcAddressGenerationTriggerHandler
import com.d3.btc.generation.handler.BtcAddressRegisteredHandler
import com.d3.btc.generation.handler.NewKeyHandler
import com.d3.btc.generation.provider.BtcSessionProvider
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.address.BtcAddressesProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.provider.NotaryClientsProvider
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.iroha.util.impl.RobustIrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.wallet.Wallet
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

val btcAddressGenerationConfig =
    loadLocalConfigs(
        "btc-address-generation",
        BtcAddressGenerationConfig::class.java,
        "address_generation.properties"
    ).get()

private val rmqConfig =
    loadRawLocalConfigs("rmq", RMQConfig::class.java, "rmq.properties")

@Configuration
class BtcAddressGenerationAppConfiguration {

    private val registrationKeyPair =
        Utils.parseHexKeypair(
            btcAddressGenerationConfig.registrationAccount.pubkey,
            btcAddressGenerationConfig.registrationAccount.privkey
        )

    private val registrationCredential =
        IrohaCredential(
            btcAddressGenerationConfig.registrationAccount.accountId,
            registrationKeyPair
        )

    private val mstRegistrationKeyPair =
        Utils.parseHexKeypair(
            btcAddressGenerationConfig.mstRegistrationAccount.pubkey,
            btcAddressGenerationConfig.mstRegistrationAccount.privkey
        )

    private val mstRegistrationCredential =
        IrohaCredential(
            btcAddressGenerationConfig.mstRegistrationAccount.accountId,
            mstRegistrationKeyPair
        )

    @Bean
    fun mstRegistrationCredential() = mstRegistrationCredential

    @Bean
    fun generationIrohaAPI(): IrohaAPI {
        val irohaAPI = IrohaAPI(
            btcAddressGenerationConfig.iroha.hostname,
            btcAddressGenerationConfig.iroha.port
        )
        /**
         * It's essential to handle blocks in this service one-by-one.
         * This is why we explicitly set single threaded executor.
         */
        irohaAPI.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                btcAddressGenerationConfig.iroha.hostname,
                btcAddressGenerationConfig.iroha.port
            ).executor(
                createPrettySingleThreadPool(
                    BTC_ADDRESS_GENERATION_SERVICE_NAME,
                    "iroha-chain-listener"
                )
            ).usePlaintext().build()
        )
        return irohaAPI
    }

    @Bean
    fun healthCheckPort() = btcAddressGenerationConfig.healthCheckPort

    @Bean
    fun registrationQueryHelper() = RobustIrohaQueryHelperImpl(
        IrohaQueryHelperImpl(
            generationIrohaAPI(),
            registrationCredential.accountId,
            registrationCredential.keyPair
        ), btcAddressGenerationConfig().irohaQueryTimeoutMls
    )

    @Bean
    fun btcAddressGenerationConfig() = btcAddressGenerationConfig

    @Bean
    fun keysWallet(networkProvider: BtcNetworkConfigProvider): Wallet {
        val walletPath = btcAddressGenerationConfig.btcKeysWalletPath
        createWalletIfAbsent(walletPath, networkProvider)
        return Wallet.loadFromFile(File(walletPath))!!
    }

    @Bean
    fun addressGenerationConsumer() = IrohaConsumerImpl(registrationCredential, generationIrohaAPI())

    @Bean
    fun multiSigConsumer() = MultiSigIrohaConsumer(mstRegistrationCredential, generationIrohaAPI())

    @Bean
    fun notaryAccount() = btcAddressGenerationConfig.notaryAccount

    @Bean
    fun changeAddressStorageAccount() = btcAddressGenerationConfig.changeAddressesStorageAccount

    @Bean
    fun irohaChainListener() = ReliableIrohaChainListener(
        rmqConfig, btcAddressGenerationConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_ADDRESS_GENERATION_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = true
    )

    @Bean
    fun registrationCredential() = registrationCredential

    @Bean
    fun btcChangeAddressProvider(): BtcChangeAddressProvider {
        return BtcChangeAddressProvider(
            registrationQueryHelper(),
            btcAddressGenerationConfig.mstRegistrationAccount.accountId,
            btcAddressGenerationConfig.changeAddressesStorageAccount
        )
    }

    @Bean
    fun serviceExpansion() =
        ServiceExpansion(
            btcAddressGenerationConfig.expansionTriggerAccount,
            btcAddressGenerationConfig.expansionTriggerCreatorAccountId,
            generationIrohaAPI()
        )

    @Bean
    fun addressGenerationHandlers(
        newKeyHandler: NewKeyHandler,
        btcAddressRegisteredHandler: BtcAddressRegisteredHandler,
        btcAddressGenerationTriggerHandler: BtcAddressGenerationTriggerHandler
    ) = listOf(newKeyHandler, btcAddressRegisteredHandler, btcAddressGenerationTriggerHandler)

    @Bean
    fun notaryClientsProvider() =
        NotaryClientsProvider(
            registrationQueryHelper(),
            btcAddressGenerationConfig.clientStorageAccount,
            btcAddressGenerationConfig.registrationServiceAccountName
        )

    @Bean
    fun btcSessionProvider() = BtcSessionProvider(addressGenerationConsumer())

    @Bean
    fun btcAddressesProvider(registrationQueryHelper: IrohaQueryHelper) =
        BtcAddressesProvider(
            registrationQueryHelper,
            btcAddressGenerationConfig.mstRegistrationAccount.accountId,
            btcAddressGenerationConfig.notaryAccount
        )

    @Bean
    fun btcRegisteredAddressesProvider(registrationQueryHelper: IrohaQueryHelper) =
        BtcRegisteredAddressesProvider(
            registrationQueryHelper,
            registrationCredential.accountId,
            btcAddressGenerationConfig.notaryAccount
        )

    @Bean
    fun btcFreeAddressesProvider(
        btcAddressesProvider: BtcAddressesProvider,
        btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider
    ) = BtcFreeAddressesProvider(
        btcAddressGenerationConfig.nodeId,
        btcAddressesProvider,
        btcRegisteredAddressesProvider
    )
}
