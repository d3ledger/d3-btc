/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.registration.config

import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.account.IrohaBtcAccountRegistrator
import com.d3.btc.provider.address.BtcAddressesProvider
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.iroha.util.impl.RobustIrohaQueryHelperImpl
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

val btcRegistrationConfig = loadRawLocalConfigs(
    "btc-registration",
    BtcRegistrationConfig::class.java,
    "registration.properties"
)

@Configuration
class BtcRegistrationAppConfiguration {

    private val btcRegistrationCredential = IrohaCredential(
        btcRegistrationConfig.registrationCredential.accountId,
        Utils.parseHexKeypair(
            btcRegistrationConfig.registrationCredential.pubkey,
            btcRegistrationConfig.registrationCredential.privkey
        )
    )

    @Bean
    fun irohaAPI() =
        IrohaAPI(btcRegistrationConfig.iroha.hostname, btcRegistrationConfig.iroha.port)

    @Bean
    fun queryHelper() = RobustIrohaQueryHelperImpl(
        IrohaQueryHelperImpl(
            irohaAPI(),
            btcRegistrationCredential.accountId,
            btcRegistrationCredential.keyPair
        ),
        btcRegistrationConfig.irohaQueryTimeoutMls
    )

    @Bean
    fun btcRegistrationConfig() = btcRegistrationConfig

    @Bean
    fun btcRegisteredAddressesProvider() = BtcRegisteredAddressesProvider(
        queryHelper(),
        btcRegistrationCredential.accountId,
        btcRegistrationConfig.notaryAccount
    )

    @Bean
    fun btcFreeAddressesProvider(): BtcFreeAddressesProvider {
        return BtcFreeAddressesProvider(
            btcRegistrationConfig.nodeId,
            BtcAddressesProvider(
                queryHelper(),
                btcRegistrationConfig.mstRegistrationAccount,
                btcRegistrationConfig.notaryAccount
            ),
            btcRegisteredAddressesProvider()
        )
    }

    @Bean
    fun btcRegistrationConsumer() = IrohaConsumerImpl(btcRegistrationCredential, irohaAPI())

    @Bean
    fun irohaBtcAccountCreator(): IrohaBtcAccountRegistrator {
        return IrohaBtcAccountRegistrator(
            btcRegistrationConsumer(),
            btcRegistrationConfig.notaryAccount
        )
    }
}
