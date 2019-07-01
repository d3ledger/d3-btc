/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.config

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.provider.NotaryPeerListProvider
import com.d3.commons.provider.NotaryPeerListProviderImpl
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

val btcAddressGenerationConfig =
    loadLocalConfigs(
        "btc-address-generation",
        BtcAddressGenerationConfig::class.java,
        "address_generation.properties"
    ).get()

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
    fun registrationQueryHelper() = IrohaQueryHelperImpl(
        generationIrohaAPI(),
        registrationCredential.accountId,
        registrationCredential.keyPair
    )

    @Bean
    fun btcAddressGenerationConfig() = btcAddressGenerationConfig

    @Bean
    fun notaryPeerListProvider(): NotaryPeerListProvider {
        return NotaryPeerListProviderImpl(
            registrationQueryHelper(),
            btcAddressGenerationConfig.notaryListStorageAccount,
            btcAddressGenerationConfig.notaryListSetterAccount
        )
    }

    @Bean
    fun sessionConsumer() = IrohaConsumerImpl(registrationCredential, generationIrohaAPI())

    @Bean
    fun multiSigConsumer() = MultiSigIrohaConsumer(mstRegistrationCredential, generationIrohaAPI())

    @Bean
    fun notaryAccount() = btcAddressGenerationConfig.notaryAccount

    @Bean
    fun changeAddressStorageAccount() = btcAddressGenerationConfig.changeAddressesStorageAccount

    @Bean
    fun irohaChainListener() = IrohaChainListener(
        generationIrohaAPI(),
        registrationCredential
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
}
