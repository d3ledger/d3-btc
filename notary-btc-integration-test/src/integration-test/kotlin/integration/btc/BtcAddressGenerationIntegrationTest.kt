/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.generation.provider.ADDRESS_GENERATION_NODE_ID_KEY
import com.d3.btc.generation.provider.ADDRESS_GENERATION_TIME_KEY
import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddressType
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcAddressGenerationTestEnvironment
import integration.helper.BtcIntegrationHelperUtil
import integration.registration.RegistrationServiceTestEnvironment
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.Wallet
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import java.io.File
import java.util.*

const val WAIT_PREGEN_PROCESS_MILLIS = 15_000L
const val WAIT_PREGEN_INIT_PROCESS_MILLIS = 30_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationIntegrationTest {

    private val nodeId = UUID.randomUUID()

    private val integrationHelper = BtcIntegrationHelperUtil()

    private val registrationEnvironment = RegistrationServiceTestEnvironment(integrationHelper)

    private val environment =
        BtcAddressGenerationTestEnvironment(
            integrationHelper,
            registrationConfig = registrationEnvironment.registrationConfig
        )

    @AfterAll
    fun dropDown() {
        environment.close()
    }

    init {
        GlobalScope.launch {
            environment.btcAddressGenerationInitialization.init().failure { ex -> throw ex }
        }
        // Wait for initial address generation
        Thread.sleep(WAIT_PREGEN_INIT_PROCESS_MILLIS)
        environment.checkIfFreeAddressesWereGeneratedAtInitialPhase()
        environment.checkIfChangeAddressesWereGeneratedAtInitialPhase()
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given "free" session account is created
     * @when special generation account is triggered
     * @then new free MultiSig btc address is created
     */
    @Test
    fun testGenerateFreeAddress() {
        val sessionAccountName = BtcAddressType.FREE.createSessionAccountName()
        environment.btcKeyGenSessionProvider.createPubKeyCreationSession(
            sessionAccountName,
            nodeId.toString()
        ).fold({ logger.info { "session $sessionAccountName was created" } },
            { ex -> fail("cannot create session", ex) })
        Thread.sleep(WAIT_PREGEN_PROCESS_MILLIS)
        val sessionDetails =
            integrationHelper.getAccountDetails(
                "$sessionAccountName@btcSession",
                environment.btcGenerationConfig.registrationAccount.accountId
            )
        val notaryKeys =
            sessionDetails.entries.filter { entry ->
                entry.key != ADDRESS_GENERATION_TIME_KEY
                        && entry.key != ADDRESS_GENERATION_NODE_ID_KEY
            }.map { entry -> entry.value }
        val pubKey = notaryKeys.first()
        assertNotNull(pubKey)
        val wallet = Wallet.loadFromFile(File(environment.btcGenerationConfig.btcKeysWalletPath))
        assertTrue(wallet.issuedReceiveKeys.any { ecKey -> ecKey.publicKeyAsHex == pubKey })
        val notaryAccountDetails =
            integrationHelper.getAccountDetails(
                environment.btcGenerationConfig.notaryAccount,
                environment.btcGenerationConfig.mstRegistrationAccount.accountId
            )
        val expectedMsAddress =
            com.d3.btc.helper.address.createMsAddress(notaryKeys, RegTestParams.get())
        assertTrue(wallet.isAddressWatched(expectedMsAddress))
        val generatedAddress =
            AddressInfo.fromJson(notaryAccountDetails[expectedMsAddress.toBase58()]!!)!!
        assertNull(generatedAddress.irohaClient)
        assertEquals(notaryKeys, generatedAddress.notaryKeys.toList())
        assertEquals(nodeId.toString(), generatedAddress.nodeId)
        assertFalse(environment.btcFreeAddressesProvider.ableToRegisterAsFree(expectedMsAddress.toBase58()).get())
    }

    /**
     * Note: Iroha must be deployed to pass the test.
     * @given "change" session account is created
     * @when special generation account is triggered
     * @then new MultiSig btc address that stores change is created
     */
    @Test
    fun testGenerateChangeAddress() {
        val sessionAccountName = BtcAddressType.CHANGE.createSessionAccountName()
        environment.btcKeyGenSessionProvider.createPubKeyCreationSession(
            sessionAccountName,
            nodeId.toString()
        ).fold({ logger.info { "session $sessionAccountName was created" } },
            { ex -> fail("cannot create session", ex) })
        Thread.sleep(WAIT_PREGEN_PROCESS_MILLIS)
        val sessionDetails =
            integrationHelper.getAccountDetails(
                "$sessionAccountName@btcSession",
                environment.btcGenerationConfig.registrationAccount.accountId
            )
        val notaryKeys =
            sessionDetails.entries.filter { entry ->
                entry.key != ADDRESS_GENERATION_TIME_KEY
                        && entry.key != ADDRESS_GENERATION_NODE_ID_KEY
            }.map { entry -> entry.value }
        val pubKey = notaryKeys.first()
        assertNotNull(pubKey)
        val wallet = Wallet.loadFromFile(File(environment.btcGenerationConfig.btcKeysWalletPath))
        assertTrue(wallet.issuedReceiveKeys.any { ecKey -> ecKey.publicKeyAsHex == pubKey })
        val changeAddressStorageAccountDetails =
            integrationHelper.getAccountDetails(
                environment.btcGenerationConfig.changeAddressesStorageAccount,
                environment.btcGenerationConfig.mstRegistrationAccount.accountId
            )
        val expectedMsAddress =
            com.d3.btc.helper.address.createMsAddress(notaryKeys, RegTestParams.get())
        val generatedAddress =
            AddressInfo.fromJson(changeAddressStorageAccountDetails[expectedMsAddress.toBase58()]!!)!!
        assertNull(generatedAddress.irohaClient)
        assertEquals(notaryKeys, generatedAddress.notaryKeys.toList())
        assertEquals(nodeId.toString(), generatedAddress.nodeId)
        assertTrue(environment.btcFreeAddressesProvider.ableToRegisterAsFree(expectedMsAddress.toBase58()).get())
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
