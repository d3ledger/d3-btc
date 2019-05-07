package integration.btc

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.commons.config.loadRawConfigs
import integration.helper.BtcContainerHelper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcAddressGenerationFailFastTest {

    private val containerHelper = BtcContainerHelper()
    private val mockConfigFile = "${containerHelper.userDir}/configs/btc/mock/address_generation_local.properties"
    private val configFile = "${containerHelper.userDir}/configs/btc/address_generation_local.properties"
    private val dockerfile = "${containerHelper.userDir}/docker/dockerfile"
    private val jarFile = "${containerHelper.userDir}/btc-address-generation/build/libs/btc-address-generation-all.jar"

    // Create address generation container
    private val addressGenerationContainer = containerHelper.createContainer(jarFile, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount configs
        addressGenerationContainer.addFileSystemBind(
            "${containerHelper.userDir}/configs/btc/mock",
            "/opt/notary/configs/btc",
            BindMode.READ_ONLY
        )
        // Mount Bitcoin wallet
        addressGenerationContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/bitcoin",
            "/opt/notary/deploy/bitcoin",
            BindMode.READ_WRITE
        )
        // Mount Iroha keys
        addressGenerationContainer.addFileSystemBind(
            "${containerHelper.userDir}/deploy/iroha/keys",
            "/opt/notary/deploy/iroha/keys",
            BindMode.READ_WRITE
        )
        // Expose health check port
        addressGenerationContainer.addExposedPort(getServiceHealthCheckPort())
        // Start Iroha
        containerHelper.irohaContainer.start()
        // Create mock config file
        containerHelper.createMockIrohaConfig(configFile, mockConfigFile, "btc-address-generation")
        // Start service
        addressGenerationContainer.start()
    }

    @AfterAll
    fun tearDown() {
        containerHelper.close()
        addressGenerationContainer.stop()
    }

    /**
     * @given address generation and Iroha services being started
     * @when Iroha dies
     * @then address generation dies as well
     */
    @Test
    fun testFailFast() {
        // Let service work a little
        Thread.sleep(15_000)
        assertTrue(containerHelper.isServiceHealthy(addressGenerationContainer, getServiceHealthCheckPort()))
        // Kill Iroha
        containerHelper.irohaContainer.stop()
        // Wait a little
        Thread.sleep(5_000)
        // Check that the service is dead
        assertTrue(containerHelper.isServiceDead(addressGenerationContainer))
    }

    /**
     * Returns address generation health check service port
     */
    private fun getServiceHealthCheckPort(): Int {
        return loadRawConfigs(
            "btc-address-generation",
            BtcAddressGenerationConfig::class.java,
            mockConfigFile
        ).healthCheckPort
    }
}
