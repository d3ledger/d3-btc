/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import integration.helper.ContainerHelper
import integration.helper.DEFAULT_RMQ_PORT
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcDepositWithdrawalRmqFailFastTest {
    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-dw-bridge/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-dw-bridge/build/docker"

    // Create deposit-withdrawal container
    private val depositWithdrawalContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount Bitcoin wallet
        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Start RMQ
        containerHelper.rmqContainer.start()

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
        depositWithdrawalContainer.addEnv("RMQ_HOST", containerHelper.rmqContainer.containerIpAddress)
        depositWithdrawalContainer.addEnv(
            "RMQ_PORT",
            containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT).toString()
        )
        depositWithdrawalContainer.addEnv("REVERSE-CHAIN-ADAPTER_RMQHOST", containerHelper.rmqContainer.containerIpAddress)
        depositWithdrawalContainer.addEnv(
            "REVERSE-CHAIN-ADAPTER_RMQPORT",
            containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT).toString()
        )

        // Start service
        depositWithdrawalContainer.start()
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        depositWithdrawalContainer.stop()
    }

    /**
     * @given dw-bridge and RMQ services being started
     * @when RMQ dies
     * @then dw-bridge dies as well
     */
    @Test
    fun testFailFast() {
        // Let service work a little
        Thread.sleep(15_000)
        Assertions.assertTrue(containerHelper.isServiceHealthy(depositWithdrawalContainer))
        // Kill Iroha
        containerHelper.rmqContainer.stop()
        // Wait a little
        // Yeah, RMQ really takes a long time to accept its death
        Thread.sleep(15_000)
        // Check that the service is dead
        Assertions.assertTrue(containerHelper.isServiceDead(depositWithdrawalContainer))
    }
}
