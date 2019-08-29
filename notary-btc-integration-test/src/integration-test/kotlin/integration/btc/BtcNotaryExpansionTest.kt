/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcNotaryTestEnvironment
import integration.helper.BtcIntegrationHelperUtil
import integration.registration.RegistrationServiceTestEnvironment
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import org.junit.jupiter.api.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcNotaryExpansionTest {

    private val integrationHelper = BtcIntegrationHelperUtil()
    private val registrationServiceEnvironment =
        RegistrationServiceTestEnvironment(integrationHelper)
    private val environment =
        BtcNotaryTestEnvironment(integrationHelper, registrationServiceEnvironment.registrationConfig)
    private val irohaQueryHelper = IrohaQueryHelperImpl(
        integrationHelper.irohaAPI,
        integrationHelper.accountHelper.notaryAccount.accountId,
        integrationHelper.accountHelper.notaryAccount.keyPair
    )

    @AfterAll
    fun dropDown() {
        registrationServiceEnvironment.close()
        environment.close()
    }

    init {
        registrationServiceEnvironment.registrationInitialization.init()
        val blockStorageFolder = File(environment.bitcoinConfig.blockStoragePath)
        //Clear bitcoin blockchain folder
        blockStorageFolder.deleteRecursively()
        //Recreate folder
        blockStorageFolder.mkdirs()
        integrationHelper.generateBtcInitialBlocks()
        environment.btcNotaryInitialization.init()
            .failure { ex -> fail("Cannot run BTC notary", ex) }
    }

    /**
     * @given deposit service being started
     * @when 'expansion trigger' transaction is committed
     * @then notary updates its quorum to 2
     */
    @Test
    fun testExpansion() {
        val newQuorum = 2
        val publicKey = Ed25519Sha3().generateKeypair().public.toHexString()
        integrationHelper.triggerExpansion(
            integrationHelper.accountHelper.notaryAccount.accountId,
            publicKey,
            newQuorum
        )
        // Wait a little
        Thread.sleep(5_000)
        // Get new quorum
        val notaryQuorum =
            irohaQueryHelper.getAccountQuorum(integrationHelper.accountHelper.notaryAccount.accountId)
        // Check that it was increased
        Assertions.assertEquals(newQuorum, notaryQuorum.get())
    }
}
