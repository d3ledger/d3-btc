package integration.btc

import integration.helper.ContainerHelper
import integration.helper.DEFAULT_RMQ_PORT
import org.junit.jupiter.api.*
import org.testcontainers.containers.BindMode

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcDepositWithdrawalRmqFailFastTest {
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
