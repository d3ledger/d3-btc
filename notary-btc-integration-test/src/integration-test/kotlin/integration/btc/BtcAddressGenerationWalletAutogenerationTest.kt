/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.commons.util.getRandomId
import integration.helper.ContainerHelper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.BindMode
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationWalletAutogenerationTest {

    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-address-generation/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-address-generation/build/docker/"
    // Create address generation container
    private val addressGenerationContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)
    private val testWalletsFolder = "${containerHelper.userDir}/deploy/bitcoin/regtest/test wallets"
    private val containerWalletsFolder = "/deploy/bitcoin/regtest"
    private val walletName = "/subfolder/${String.getRandomId()}.d3.wallet"

    @BeforeAll
    fun startUp() {
        // Mount Bitcoin wallet folder
        addressGenerationContainer.addFileSystemBind(
            testWalletsFolder,
            containerWalletsFolder,
            BindMode.READ_WRITE
        )
        // Start Iroha
        containerHelper.irohaContainer.start()
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_HOSTNAME",
            containerHelper.irohaContainer.toriiAddress.host
        )
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_PORT",
            containerHelper.irohaContainer.toriiAddress.port.toString()
        )
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_BTCKEYSWALLETPATH",
            "$containerWalletsFolder/$walletName"
        )
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        addressGenerationContainer.stop()
    }

    /**
     * @given address generation service with no pre-created wallet file
     * @when address generation starts
     * @then address generation service creates new wallet file and works as usual
     */
    @Test
    fun testWalletAutogeneration() {
        // Wallet file doesn't exist
        val testWalletPath = "$testWalletsFolder/$walletName"
        assertFalse(File(testWalletPath).exists())
        // Start service
        addressGenerationContainer.start()
        // Let service work a little
        Thread.sleep(15_000)
        // Wallet file is created automatically
        assertTrue(File(testWalletPath).exists())
    }
}
