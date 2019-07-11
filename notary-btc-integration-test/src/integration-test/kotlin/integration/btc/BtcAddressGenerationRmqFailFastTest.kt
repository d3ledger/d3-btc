/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import integration.helper.ContainerHelper
import integration.helper.DEFAULT_RMQ_PORT
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationRmqFailFastTest {

    private val containerHelper = ContainerHelper()
    private val dockerfile = "${containerHelper.userDir}/btc-address-generation/build/docker/Dockerfile"
    private val contextFolder = "${containerHelper.userDir}/btc-address-generation/build/docker/"
    // Create address generation container
    private val addressGenerationContainer = containerHelper.createSoraPluginContainer(contextFolder, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount Bitcoin wallet
        addressGenerationContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Start Iroha
        containerHelper.irohaContainer.start()

        // Start RMQ
        containerHelper.rmqContainer.start()

        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_HOSTNAME",
            containerHelper.irohaContainer.toriiAddress.host
        )
        addressGenerationContainer.addEnv(
            "BTC-ADDRESS-GENERATION_IROHA_PORT",
            containerHelper.irohaContainer.toriiAddress.port.toString()
        )
        addressGenerationContainer.addEnv("RMQ_HOST", containerHelper.rmqContainer.containerIpAddress)
        addressGenerationContainer.addEnv(
            "RMQ_PORT",
            containerHelper.rmqContainer.getMappedPort(DEFAULT_RMQ_PORT).toString()
        )

        // Start service
        addressGenerationContainer.start()
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        addressGenerationContainer.stop()
    }

    /**
     * @given address generation and RMQ services being started
     * @when RMQ dies
     * @then address generation dies as well
     */
    @Test
    fun testFailFast() {
        // Let service work a little
        Thread.sleep(15_000)
        assertTrue(containerHelper.isServiceHealthy(addressGenerationContainer))
        containerHelper.rmqContainer.stop()
        // Wait a little
        // Yeah, RMQ really takes a long time to accept its death
        Thread.sleep(15_000)
        // Check that the service is dead
        assertTrue(containerHelper.isServiceDead(addressGenerationContainer))
    }
}
