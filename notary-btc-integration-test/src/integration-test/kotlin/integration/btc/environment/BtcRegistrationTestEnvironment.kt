/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc.environment

import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.account.IrohaBtcAccountRegistrator
import com.d3.btc.provider.address.BtcAddressesProvider
import com.d3.btc.registration.init.BtcRegistrationServiceInitialization
import com.d3.btc.registration.strategy.BtcRegistrationStrategyImpl
import com.d3.commons.model.IrohaCredential
import com.d3.commons.registration.NotaryRegistrationConfig
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.iroha.util.impl.RobustIrohaQueryHelperImpl
import com.d3.commons.util.toHexString
import integration.helper.BtcIntegrationHelperUtil
import integration.helper.D3_DOMAIN
import jp.co.soramitsu.iroha.java.Utils
import khttp.post
import khttp.responses.Response
import java.io.Closeable

/**
 * Bitcoin client registration service testing environment
 */
class BtcRegistrationTestEnvironment(
    private val integrationHelper: BtcIntegrationHelperUtil,
    private val registrationConfig: NotaryRegistrationConfig
) :
    Closeable {

    val btcRegistrationConfig = integrationHelper.configHelper.createBtcRegistrationConfig()

    val btcAddressGenerationConfig =
        integrationHelper.configHelper.createBtcAddressGenerationConfig(registrationConfig, 0)

    private val btcRegistrationCredential =
        IrohaCredential(
            btcRegistrationConfig.registrationCredential.accountId, Utils.parseHexKeypair(
                btcRegistrationConfig.registrationCredential.pubkey,
                btcRegistrationConfig.registrationCredential.privkey
            )
        )

    private val registrationConsumer =
        IrohaConsumerImpl(btcRegistrationCredential, integrationHelper.irohaAPI)

    private val registrationQueryHelper =
        RobustIrohaQueryHelperImpl(
            IrohaQueryHelperImpl(integrationHelper.irohaAPI, btcRegistrationCredential),
            btcRegistrationConfig.irohaQueryTimeoutMls
        )

    val btcFreeAddressesProvider = BtcFreeAddressesProvider(
        btcRegistrationConfig.nodeId,
        btcRegistrationConfig.freeAddressesStorageAccount,
        registrationQueryHelper,
        registrationConsumer
    )

    val btcRegistrationServiceInitialization = BtcRegistrationServiceInitialization(
        btcRegistrationConfig,
        BtcRegistrationStrategyImpl(
            btcRegisteredAddressesProvider(),
            btcFreeAddressesProvider,
            irohaBtcAccountCreator()
        )
    )

    private fun btcRegisteredAddressesProvider() = BtcRegisteredAddressesProvider(
        registrationQueryHelper,
        btcRegistrationCredential.accountId,
        btcRegistrationConfig.notaryAccount
    )

    private fun irohaBtcAccountCreator() = IrohaBtcAccountRegistrator(
        registrationConsumer,
        btcRegistrationConfig.notaryAccount
    )

    val btcRegisteredAddressesProvider = BtcRegisteredAddressesProvider(
        registrationQueryHelper,
        btcRegistrationConfig.registrationCredential.accountId,
        integrationHelper.accountHelper.notaryAccount.accountId
    )

    fun register(
        name: String,
        pubkey: String = ModelUtil.generateKeypair().public.toHexString()
    ): Response {
        return post(
            "http://127.0.0.1:${btcRegistrationConfig.port}/users",
            data = mapOf("name" to name, "pubkey" to pubkey, "domain" to D3_DOMAIN)
        )
    }

    override fun close() {
        integrationHelper.close()
    }
}
