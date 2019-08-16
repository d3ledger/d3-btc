/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.commons.sidechain.iroha.util.impl.IrohaQueryHelperImpl
import com.d3.commons.util.getRandomString
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcWithdrawalTestEnvironment
import integration.helper.BtcIntegrationHelperUtil
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import mu.KLogging
import org.junit.jupiter.api.*
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcWithdrawalExpansionTest {
    private val integrationHelper = BtcIntegrationHelperUtil()
    private val irohaQueryHelper = IrohaQueryHelperImpl(
        integrationHelper.irohaAPI,
        integrationHelper.accountHelper.notaryAccount.accountId,
        integrationHelper.accountHelper.notaryAccount.keyPair
    )
    private val environment =
        BtcWithdrawalTestEnvironment(
            integrationHelper,
            "withdrawal_test_${String.getRandomString(5)}"
        )

    @AfterAll
    fun dropDown() {
        environment.close()
    }

    @BeforeAll
    fun setUp() {
        val blockStorageFolder = File(environment.bitcoinConfig.blockStoragePath)
        //Clear bitcoin blockchain folder
        blockStorageFolder.deleteRecursively()
        //Recreate folder
        blockStorageFolder.mkdirs()
        integrationHelper.addBtcNotary("test", "test")
        integrationHelper.generateBtcInitialBlocks()
        integrationHelper.genChangeBtcAddress(environment.btcWithdrawalConfig.btcKeysWalletPath)
            .failure { ex -> throw ex }
        environment.btcWithdrawalInitialization.init().failure { ex -> throw ex }
        environment.reverseChainAdapter.init().failure { ex -> throw ex }
    }

    /**
     * @given withdrawal service being started
     * @when 'expansion trigger' transaction is committed
     * @then withdrawal account updates its quorum to 2
     */
    @Test
    fun testExpansion() {
        val newQuorum = 2
        val publicKey = Ed25519Sha3().generateKeypair().public.toHexString()
        integrationHelper.triggerExpansion(
            integrationHelper.accountHelper.btcWithdrawalAccount.accountId,
            publicKey,
            newQuorum
        )
        // Wait a little
        Thread.sleep(5_000)
        // Get new quorum
        val withdrawalQuorum =
            irohaQueryHelper.getAccountQuorum(integrationHelper.accountHelper.btcWithdrawalAccount.accountId)
        // Check that it was increased
        Assertions.assertEquals(newQuorum, withdrawalQuorum.get())
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
