/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc.environment

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.expansion.AddressGenerationServiceExpansion
import com.d3.btc.generation.handler.BtcAddressGenerationTriggerHandler
import com.d3.btc.generation.handler.BtcAddressRegisteredHandler
import com.d3.btc.generation.handler.BtcMultiSigAddressGeneratedHandler
import com.d3.btc.generation.handler.NewKeyHandler
import com.d3.btc.generation.init.BtcAddressGenerationInitialization
import com.d3.btc.generation.provider.ADDRESS_GENERATION_NODE_ID_KEY
import com.d3.btc.generation.provider.ADDRESS_GENERATION_TIME_KEY
import com.d3.btc.generation.provider.BtcPublicKeyProvider
import com.d3.btc.generation.provider.BtcSessionProvider
import com.d3.btc.generation.trigger.AddressGenerationTrigger
import com.d3.btc.helper.address.createMsAddress
import com.d3.btc.model.BtcAddressType
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import com.d3.commons.provider.NotaryClientsProvider
import com.d3.commons.registration.NotaryRegistrationConfig
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumerImpl
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.sidechain.iroha.util.impl.RobustIrohaQueryHelperImpl
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.spy
import com.nhaarman.mockito_kotlin.whenever
import integration.btc.WAIT_PREGEN_PROCESS_MILLIS
import integration.helper.BtcIntegrationHelperUtil
import io.grpc.ManagedChannelBuilder
import jp.co.soramitsu.bootstrap.changelog.ChangelogInterface
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.Wallet
import org.junit.jupiter.api.fail
import java.io.Closeable
import java.io.File

//How many addresses to generate at initial phase
private const val INIT_ADDRESSES = 3

/**
 * Bitcoin address generation service testing environment
 */
class BtcAddressGenerationTestEnvironment(
    private val integrationHelper: BtcIntegrationHelperUtil,
    val testName: String = "test",
    val registrationConfig: NotaryRegistrationConfig,
    val btcGenerationConfig: BtcAddressGenerationConfig =
        integrationHelper.configHelper.createBtcAddressGenerationConfig(registrationConfig, INIT_ADDRESSES, testName),
    mstRegistrationCredential: IrohaCredential = IrohaCredential(
        btcGenerationConfig.mstRegistrationAccount.accountId,
        Utils.parseHexKeypair(
            btcGenerationConfig.mstRegistrationAccount.pubkey,
            btcGenerationConfig.mstRegistrationAccount.privkey
        )
    )
    , private val peers: Int = 1
) : Closeable {

    private val keysWallet = Wallet.loadFromFile(File(btcGenerationConfig.btcKeysWalletPath))
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
    private val registrationKeyPair =
        Utils.parseHexKeypair(
            btcGenerationConfig.registrationAccount.pubkey,
            btcGenerationConfig.registrationAccount.privkey
        )

    private val registrationCredential =
        IrohaCredential(btcGenerationConfig.registrationAccount.accountId, registrationKeyPair)

    private val registrationConsumer =
        IrohaConsumerImpl(registrationCredential, irohaApi)

    val btcKeyGenSessionProvider = BtcSessionProvider(registrationConsumer)

    private val multiSigConsumer = MultiSigIrohaConsumer(
        mstRegistrationCredential,
        irohaApi
    )

    private val btcNetworkConfigProvider = BtcRegTestConfigProvider()

    private val registrationQueryHelper by lazy {
        val irohaQueryHelper = spy(
            IrohaQueryHelperImpl(
                irohaApi,
                registrationCredential.accountId,
                registrationCredential.keyPair
            )
        )
        doReturn(Result.of { peers }).whenever(irohaQueryHelper).getPeersCount()
        RobustIrohaQueryHelperImpl(irohaQueryHelper, btcGenerationConfig.irohaQueryTimeoutMls)
    }

    private fun btcPublicKeyProvider(): BtcPublicKeyProvider {
        return BtcPublicKeyProvider(
            registrationQueryHelper,
            keysWallet,
            btcGenerationConfig,
            multiSigConsumer,
            registrationConsumer,
            btcNetworkConfigProvider
        )
    }

    private val rmqConfig =
        loadRawLocalConfigs("rmq", RMQConfig::class.java, "rmq.properties")

    private val irohaListener = ReliableIrohaChainListener(
        rmqConfig, btcGenerationConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_ADDRESS_GENERATION_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = true
    )

    val btcFreeAddressesProvider =
        BtcFreeAddressesProvider(
            btcGenerationConfig.nodeId,
            btcGenerationConfig.freeAddressesStorageAccount,
            registrationQueryHelper,
            registrationConsumer
        )

    private val btcChangeAddressesProvider = BtcChangeAddressProvider(
        registrationQueryHelper,
        btcGenerationConfig.mstRegistrationAccount.accountId,
        btcGenerationConfig.changeAddressesStorageAccount
    )

    private val addressGenerationTrigger = AddressGenerationTrigger(
        btcKeyGenSessionProvider,
        btcFreeAddressesProvider,
        btcChangeAddressesProvider
    )

    private val btcMstAddressGeneratedHandler =
        BtcMultiSigAddressGeneratedHandler(btcFreeAddressesProvider, btcGenerationConfig)
    private val newKeyHandler =
        NewKeyHandler(btcGenerationConfig, keysWallet, registrationQueryHelper, btcPublicKeyProvider())
    private val btcAddressRegisteredHandler =
        BtcAddressRegisteredHandler(addressGenerationTrigger, btcGenerationConfig)
    private val btcAddressGenerationTriggerHandler =
        BtcAddressGenerationTriggerHandler(btcGenerationConfig, keysWallet, btcPublicKeyProvider())

    private val notaryClientsProvider = NotaryClientsProvider(
        registrationQueryHelper,
        registrationConfig.clientStorageAccount,
        registrationConfig.registrationCredential.accountId.substringBefore("@")
    )

    val btcAddressGenerationInitialization = BtcAddressGenerationInitialization(
        keysWallet,
        btcGenerationConfig,
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
        ),
        listOf(
            newKeyHandler,
            btcAddressGenerationTriggerHandler,
            btcAddressRegisteredHandler,
            btcMstAddressGeneratedHandler
        ),
        notaryClientsProvider
    )

    /**
     * Checks if enough free addresses were generated at initial phase
     * @throws IllegalStateException if not enough
     */
    fun checkIfFreeAddressesWereGeneratedAtInitialPhase() {
        btcFreeAddressesProvider.countFreeAddresses()
            .fold({ freeAddresses ->
                if (freeAddresses < btcGenerationConfig.threshold) {
                    throw IllegalStateException(
                        "Generation service was not properly started." +
                                " Not enough address were generated at initial phase " +
                                "($freeAddresses out of ${btcGenerationConfig.threshold})."
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

    /**
     * Generates address
     * @param addressType - type of address to generate
     * @return address
     */
    fun generateAddress(addressType: BtcAddressType): String {
        val sessionAccountName = addressType.createSessionAccountName()
        btcKeyGenSessionProvider.createPubKeyCreationSession(
            sessionAccountName,
            btcGenerationConfig.nodeId
        ).fold({ logger.info { "session $sessionAccountName was created" } },
            { ex -> fail("cannot create session", ex) })
        Thread.sleep(WAIT_PREGEN_PROCESS_MILLIS)
        val sessionDetails =
            integrationHelper.getAccountDetails(
                "$sessionAccountName@btcSession",
                btcGenerationConfig.registrationAccount.accountId
            )
        val notaryKeys =
            sessionDetails.entries.filter { entry ->
                entry.key != ADDRESS_GENERATION_TIME_KEY
                        && entry.key != ADDRESS_GENERATION_NODE_ID_KEY
            }.map { entry -> entry.value }
        return createMsAddress(notaryKeys, RegTestParams.get()).toBase58()
    }

    override fun close() {
        integrationHelper.close()
        executor.shutdownNow()
        irohaApi.close()
        irohaListener.close()
    }

    companion object : KLogging()
}
