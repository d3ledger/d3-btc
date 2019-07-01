/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc.environment

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.expansion.AddressGenerationServiceExpansion
import com.d3.btc.generation.init.BtcAddressGenerationInitialization
import com.d3.btc.generation.trigger.AddressGenerationTrigger
import com.d3.btc.keypair.securosys.SecuroSysConfig
import com.d3.btc.keypair.securosys.SecuroSysKeyPairService
import com.d3.btc.keypair.wallet.WalletKeyPairService
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.address.BtcAddressesProvider
import com.d3.btc.provider.generation.BtcPublicKeyProvider
import com.d3.btc.provider.generation.BtcSessionProvider
import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.provider.NotaryPeerListProviderImpl
import com.d3.commons.provider.TriggerProvider
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import integration.helper.BtcIntegrationHelperUtil
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.bootstrap.changelog.ChangelogInterface
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import java.io.Closeable

//How many addresses to generate at initial phase
private const val INIT_ADDRESSES = 3

/**
 * Bitcoin address generation service testing environment
 */
class BtcAddressGenerationTestEnvironment(
    private val integrationHelper: BtcIntegrationHelperUtil,
    testName: String = "test",
    val btcGenerationConfig: BtcAddressGenerationConfig =
        integrationHelper.configHelper.createBtcAddressGenerationConfig(INIT_ADDRESSES),
    mstRegistrationCredential: IrohaCredential = IrohaCredential(
        btcGenerationConfig.mstRegistrationAccount.accountId,
        Utils.parseHexKeypair(
            btcGenerationConfig.mstRegistrationAccount.pubkey,
            btcGenerationConfig.mstRegistrationAccount.privkey
        )
    )
) : Closeable {

    private val walletConfig = integrationHelper.configHelper.createWalletConfig(testName)
    val keyPairService = WalletKeyPairService(walletConfig.btcKeysWalletPath)

    /**
     * It's essential to handle blocks in this service one-by-one.
     * This is why we explicitly set single threaded executor.
     */
    private val executor =
        createPrettySingleThreadPool(BTC_ADDRESS_GENERATION_SERVICE_NAME, "iroha-chain-listener")

    private val irohaApi by lazy {
        val irohaAPI = IrohaAPI(
            btcGenerationConfig.iroha.hostname,
            btcGenerationConfig.iroha.port
        )

        irohaAPI.setChannelForStreamingQueryStub(
            ManagedChannelBuilder.forAddress(
                btcGenerationConfig.iroha.hostname,
                btcGenerationConfig.iroha.port
            ).executor(executor).usePlaintext().build()
        )
        irohaAPI
    }

    val triggerProvider = TriggerProvider(
        integrationHelper.testCredential,
        irohaApi,
        btcGenerationConfig.pubKeyTriggerAccount
    )
    val btcKeyGenSessionProvider = BtcSessionProvider(
        integrationHelper.accountHelper.registrationAccount,
        irohaApi
    )

    private val registrationKeyPair =
        Utils.parseHexKeypair(
            btcGenerationConfig.registrationAccount.pubkey,
            btcGenerationConfig.registrationAccount.privkey
        )

    private val registrationCredential =
        IrohaCredential(btcGenerationConfig.registrationAccount.accountId, registrationKeyPair)

    private val sessionConsumer =
        IrohaConsumerImpl(registrationCredential, irohaApi)

    private val multiSigConsumer = MultiSigIrohaConsumer(
        mstRegistrationCredential,
        irohaApi
    )

    private val btcNetworkConfigProvider = BtcRegTestConfigProvider()

    private val registrationQueryHelper = IrohaQueryHelperImpl(
        irohaApi,
        registrationCredential.accountId,
        registrationCredential.keyPair
    )

    private fun btcPublicKeyProvider(): BtcPublicKeyProvider {
        val notaryPeerListProvider = NotaryPeerListProviderImpl(
            registrationQueryHelper,
            btcGenerationConfig.notaryListStorageAccount,
            btcGenerationConfig.notaryListSetterAccount
        )
        return BtcPublicKeyProvider(
            notaryPeerListProvider,
            btcGenerationConfig.notaryAccount,
            btcGenerationConfig.changeAddressesStorageAccount,
            multiSigConsumer,
            sessionConsumer,
            btcNetworkConfigProvider,
            keyPairService
        )
    }

    private val irohaListener = IrohaChainListener(
        irohaApi,
        registrationCredential
    )

    private val btcAddressesProvider = BtcAddressesProvider(
        registrationQueryHelper,
        btcGenerationConfig.mstRegistrationAccount.accountId,
        btcGenerationConfig.notaryAccount
    )

    private val btcRegisteredAddressesProvider = BtcRegisteredAddressesProvider(
        registrationQueryHelper,
        registrationCredential.accountId,
        btcGenerationConfig.notaryAccount
    )

    val btcFreeAddressesProvider =
        BtcFreeAddressesProvider(
            btcGenerationConfig.nodeId,
            btcAddressesProvider,
            btcRegisteredAddressesProvider
        )

    private val btcChangeAddressesProvider = BtcChangeAddressProvider(
        registrationQueryHelper,
        btcGenerationConfig.mstRegistrationAccount.accountId,
        btcGenerationConfig.changeAddressesStorageAccount
    )

    private val addressGenerationTrigger = AddressGenerationTrigger(
        btcKeyGenSessionProvider,
        triggerProvider,
        btcFreeAddressesProvider,
        IrohaConsumerImpl(registrationCredential, irohaApi),
        btcChangeAddressesProvider
    )

    val btcAddressGenerationInitialization = BtcAddressGenerationInitialization(
        registrationQueryHelper,
        btcGenerationConfig,
        btcPublicKeyProvider(),
        irohaListener,
        addressGenerationTrigger,
        btcNetworkConfigProvider,
        AddressGenerationServiceExpansion(
            irohaApi,
            ServiceExpansion(
                integrationHelper.accountHelper.expansionTriggerAccount.accountId,
                ChangelogInterface.superuserAccountId,
                irohaApi
            ), mstRegistrationCredential
        )
    )

    /**
     * Checks if enough free addresses were generated at initial phase
     * @throws IllegalStateException if not enough
     */
    fun checkIfFreeAddressesWereGeneratedAtInitialPhase() {
        btcFreeAddressesProvider.getFreeAddresses()
            .fold({ freeAddresses ->
                if (freeAddresses.size < btcGenerationConfig.threshold) {
                    throw IllegalStateException(
                        "Generation service was not properly started." +
                                " Not enough address were generated at initial phase " +
                                "(${freeAddresses.size} out of ${btcGenerationConfig.threshold})."
                    )
                }
            }, { ex -> throw IllegalStateException("Cannot get free addresses", ex) })
    }

    /**
     * Checks if change addresses were generated at initial phase
     * @throws IllegalStateException if not enough
     */
    fun checkIfChangeAddressesWereGeneratedAtInitialPhase() {
        btcChangeAddressesProvider.getAllChangeAddresses().fold({ changeAddresses ->
            if (changeAddresses.isEmpty()) {
                throw IllegalStateException("Change addresses were not generated")
            }
        }, { ex ->
            throw IllegalStateException("Cannot get change addresses", ex)
        })
    }

    override fun close() {
        integrationHelper.close()
        executor.shutdownNow()
        irohaApi.close()
        irohaListener.close()
    }
}
