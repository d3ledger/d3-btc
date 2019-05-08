package integration.btc

import integration.helper.BtcContainerHelper
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcDepositWithdrawalFailFastTest {
    private val containerHelper = BtcContainerHelper()
    private val mockConfigFile = "${containerHelper.userDir}/configs/btc/mock/dw-bridge_local.properties"
    private val configFile = "${containerHelper.userDir}/configs/btc/dw-bridge_local.properties"
    private val dockerfile = "${containerHelper.userDir}/docker/dockerfile"
    private val jarFile = "${containerHelper.userDir}/btc-dw-bridge/build/libs/btc-dw-bridge-all.jar"

    // Create deposit-withdrawal container
    private val depositWithdrawalContainer = containerHelper.createContainer(jarFile, dockerfile)

    @BeforeAll
    fun startUp() {
        // Mount configs
        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/configs/btc/mock/dw-bridge_local.properties",
            "/opt/notary/configs/btc/dw-bridge_local.properties",
            BindMode.READ_ONLY
        )

        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/configs/btc/deposit_local.properties",
            "/opt/notary/configs/btc/deposit_local.properties",
            BindMode.READ_ONLY
        )

        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/configs/btc/withdrawal_local.properties",
            "/opt/notary/configs/btc/withdrawal_local.properties",
            BindMode.READ_ONLY
        )

        depositWithdrawalContainer.addFileSystemBind(
            "${containerHelper.userDir}/configs/rmq.properties",
            "/opt/notary/configs/rmq.properties",
            BindMode.READ_ONLY
        )
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
        // Create mock config file
        containerHelper.createMockIrohaConfig(configFile, mockConfigFile, "btc-dw-bridge")
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
        Thread.sleep(25_000)
        // Check that the service is dead
        Assertions.assertTrue(containerHelper.isServiceDead(depositWithdrawalContainer))
    }
}
