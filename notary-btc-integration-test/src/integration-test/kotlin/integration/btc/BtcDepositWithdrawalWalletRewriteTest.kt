/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.provider.network.BtcRegTestConfigProvider
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.commons.util.getRandomId
import integration.helper.ContainerHelper
import integration.helper.DEFAULT_RMQ_PORT
import org.bitcoinj.wallet.Wallet
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcDepositWithdrawalWalletRewriteTest {
    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-dw-bridge/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-dw-bridge/build/docker"
    private val testWalletsFolder = "${containerHelper.userDir}/deploy/bitcoin/regtest/test wallets"
    private val containerWalletsFolder = "/deploy/bitcoin/regtest"
    private val walletName = "${String.getRandomId()}.d3.wallet"

    // Create deposit-withdrawal container
    private val depositWithdrawalContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)

    @BeforeAll
    fun startUp() {
        // Create wallet file
        createWalletIfAbsent("$testWalletsFolder/$walletName", BtcRegTestConfigProvider())
        // Mount Bitcoin folder
        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Mount Bitcoin wallet folder
        depositWithdrawalContainer.addFileSystemBind(
            testWalletsFolder,
            containerWalletsFolder,
            BindMode.READ_WRITE
        )

        // Start Iroha
        containerHelper.irohaContainer.start()
        // Start RMQ
        containerHelper.rmqContainer.start()

        depositWithdrawalContainer.addEnv(
            "BTC-DW-BRIDGE_IROHA_HOSTNAME",
            containerHelper.irohaContainer.toriiAddress.host
        )
        depositWithdrawalContainer.addEnv(
            "BTC-DW-BRIDGE_IROHA_PORT",
            containerHelper.irohaContainer.toriiAddress.port.toString()
        )
        depositWithdrawalContainer.addEnv("BTC-DW-BRIDGE_BITCOIN_HOSTS", "127.0.0.1")
        depositWithdrawalContainer.addEnv("RMQ_HOST", containerHelper.rmqContainer.containerIpAddress)
        depositWithdrawalContainer.addEnv(
            "RMQ_PORT",
            containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT).toString()
        )
        depositWithdrawalContainer.addEnv(
            "BTC-WITHDRAWAL_BTCTRANSFERSWALLETPATH",
            "$containerWalletsFolder/$walletName"
        )

        depositWithdrawalContainer.addEnv(
            "BTC-DEPOSIT_BTCTRANSFERWALLETPATH",
            "$containerWalletsFolder/$walletName"
        )
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        depositWithdrawalContainer.stop()
    }

    /**
     * @given deposit/withdrawal service with pre-created wallet file
     * @when deposit/withdrawal starts
     * @then deposit/withdrawal service doesn't create a new wallet file and works as usual
     */
    @Test
    fun testWalletRewrite() {
        // Wallet file exists
        val testWalletPath = "$testWalletsFolder/$walletName"
        Assertions.assertTrue(File(testWalletPath).exists())
        val wallet = Wallet.loadFromFile(File(testWalletPath))
        // Start service
        depositWithdrawalContainer.start()
        // Let service work a little
        Thread.sleep(15_000)
        // Wallet file is the same as before
        Assertions.assertArrayEquals(
            wallet.keyChainSeed.seedBytes,
            Wallet.loadFromFile(File(testWalletPath)).keyChainSeed.seedBytes
        )
    }
}
