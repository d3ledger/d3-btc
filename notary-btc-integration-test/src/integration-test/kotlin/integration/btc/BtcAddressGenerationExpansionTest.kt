/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcAddressGenerationTestEnvironment
import integration.helper.BtcIntegrationHelperUtil
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationExpansionTest {

    private val integrationHelper = BtcIntegrationHelperUtil()

    private val irohaQueryHelper = IrohaQueryHelperImpl(
        integrationHelper.irohaAPI,
        integrationHelper.accountHelper.notaryAccount.accountId,
        integrationHelper.accountHelper.notaryAccount.keyPair
    )

    private val environment =
        BtcAddressGenerationTestEnvironment(integrationHelper)

    @AfterAll
    fun dropDown() {
        environment.close()
    }

    init {
        integrationHelper.addBtcNotary("test_notary", "test_notary_address")
        GlobalScope.launch {
            environment.btcAddressGenerationInitialization.init().failure { ex -> throw ex }
        }
        // Wait for initial address generation
        Thread.sleep(WAIT_PREGEN_INIT_PROCESS_MILLIS)
        environment.checkIfFreeAddressesWereGeneratedAtInitialPhase()
        environment.checkIfChangeAddressesWereGeneratedAtInitialPhase()
    }

    /**
     * @given address generation service being started
     * @when 'expansion trigger' transaction is committed
     * @then mstRegistrationAccount updates its quorum to 2
     */
    @Test
    fun testExpansion() {
        val newQuorum = 2
        val publicKey = Ed25519Sha3().generateKeypair().public.toHexString()
        integrationHelper.triggerExpansion(
            integrationHelper.accountHelper.mstRegistrationAccount.accountId,
            publicKey,
            newQuorum
        )
        // Wait a little
        Thread.sleep(5_000)
        // Get new quorum
        val mstRegistrationQuorum =
            irohaQueryHelper.getAccountQuorum(integrationHelper.accountHelper.mstRegistrationAccount.accountId)
        // Check that it was increased
        Assertions.assertEquals(newQuorum, mstRegistrationQuorum.get())
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
