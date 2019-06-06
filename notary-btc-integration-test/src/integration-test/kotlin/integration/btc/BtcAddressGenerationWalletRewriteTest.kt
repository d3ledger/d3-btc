/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.commons.util.getRandomId
import integration.helper.ContainerHelper
import org.bitcoinj.wallet.Wallet
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.BindMode
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationWalletRewriteTest {

    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-address-generation/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-address-generation/build/docker/"
    // Create address generation container
    private val addressGenerationContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)
    private val testWalletsFolder = "${containerHelper.userDir}/deploy/bitcoin/regtest/test wallets"
    private val containerWalletsFolder = "/deploy/bitcoin/regtest"
    private val walletName = "${String.getRandomId()}.d3.wallet"

    @BeforeAll
    fun startUp() {
        // Create wallet file
        createWalletIfAbsent("$testWalletsFolder/$walletName", BtcRegTestConfigProvider())
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
     * @given address generation service with pre-created wallet file
     * @when address generation starts
     * @then address generation service doesn't create a new wallet file and works as usual
     */
    @Test
    fun testWalletRewrite() {
        // Wallet file exists
        val testWalletPath = "$testWalletsFolder/$walletName"
        assertTrue(File(testWalletPath).exists())
        val wallet = Wallet.loadFromFile(File(testWalletPath))
        // Start service
        addressGenerationContainer.start()
        // Let service work a little
        Thread.sleep(15_000)
        // Wallet file is the same as before
        assertArrayEquals(
            wallet.keyChainSeed.seedBytes,
            Wallet.loadFromFile(File(testWalletPath)).keyChainSeed.seedBytes
        )
    }
}
