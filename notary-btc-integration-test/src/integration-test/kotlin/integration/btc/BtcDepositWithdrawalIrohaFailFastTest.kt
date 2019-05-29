/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import integration.helper.ContainerHelper
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcDepositWithdrawalIrohaFailFastTest {
    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/docker/dockerfile"
    private val jarFile = "${containerHelper.userDir}/btc-dw-bridge/build/libs/btc-dw-bridge-all.jar"

    // Create deposit-withdrawal container
    private val depositWithdrawalContainer = containerHelper.createContainer(jarFile, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount Bitcoin wallet
        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/opt/notary/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Mount Iroha keys
        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/iroha/keys",
            "/opt/notary/deploy/iroha/keys",
            BindMode.READ_WRITE
        )
        // Start Iroha
        containerHelper.irohaContainer.start()

        depositWithdrawalContainer.addEnv(
            "BTC-DW-BRIDGE_IROHA_HOSTNAME",
            containerHelper.irohaContainer.toriiAddress.host
        )
        depositWithdrawalContainer.addEnv(
            "BTC-DW-BRIDGE_IROHA_PORT",
            containerHelper.irohaContainer.toriiAddress.port.toString()
        )
        depositWithdrawalContainer.addEnv("BTC-DW-BRIDGE_BITCOIN_HOSTS", "127.0.0.1")
        depositWithdrawalContainer.addEnv("RMQ_HOST", "127.0.0.1")
        // Start service
        depositWithdrawalContainer.start()
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        depositWithdrawalContainer.stop()
    }

    /**
     * @given dw-bridge and Iroha services being started
     * @when Iroha dies
     * @then dw-bridge dies as well
     */
    @Test
    fun testFailFast() {
        // Let service work a little
        Thread.sleep(15_000)
        Assertions.assertTrue(containerHelper.isServiceHealthy(depositWithdrawalContainer))
        // Kill Iroha
        containerHelper.irohaContainer.stop()
        // Wait a little
        Thread.sleep(5_000)
        // Check that the service is dead
        Assertions.assertTrue(containerHelper.isServiceDead(depositWithdrawalContainer))
    }
}
