package integration.helper

import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.google.protobuf.util.JsonFormat
import iroha.protocol.BlockOuterClass
import jp.co.soramitsu.iroha.testcontainers.IrohaContainer
import jp.co.soramitsu.iroha.testcontainers.PeerConfig
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

/**
 * Helper that is used to start Iroha, create containers, etc
 */
class BtcContainerHelper : Closeable {

    private val network = Network.SHARED

    val userDir = System.getProperty("user.dir")!!

    private val peerKeyPair =
        ModelUtil.loadKeypair(
            "$userDir/deploy/iroha/keys/node0.pub",
            "$userDir/deploy/iroha/keys/node0.priv"
        ).get()

    val irohaContainer =
        IrohaContainer()
            .withPeerConfig(getPeerConfig())
            .withNetwork(network)
            .withLogger(null)!! // turn of nasty Iroha logs

    /**
     * Creates service docker container based on [dockerFile]
     * @param jarFile - path to jar file that will be used to run service
     * @param dockerFile - path to docker file that will be used to create containers
     * @return container
     */
    fun createContainer(jarFile: String, dockerFile: String): KGenericContainerImage {
        return KGenericContainerImage(
            ImageFromDockerfile()
                .withFileFromFile(jarFile, File(jarFile))
                .withFileFromFile("Dockerfile", File(dockerFile)).withBuildArg("JAR_FILE", jarFile)
        ).withLogConsumer { outputFrame -> print(outputFrame.utf8String) }.withNetwork(network)
    }

    /**
     * Returns Iroha peer config
     */
    private fun getPeerConfig(): PeerConfig {
        val builder = BlockOuterClass.Block.newBuilder()
        JsonFormat.parser().merge(File("$userDir/deploy/iroha/genesis.block").readText(), builder)
        val config = PeerConfig.builder()
            .genesisBlock(builder.build())
            .build()
        config.withPeerKeyPair(peerKeyPair)
        return config
    }

    /**
     * Creates mock config. It takes original config and creates new file with modified Iroha configs
     * @param originalConfigFile - path to original config file
     * @param mockConfigFile - path to mock config file. It will be created if it doesn't exist
     * @param configPrefix - prefix of config
     */
    fun createMockIrohaConfig(originalConfigFile: String, mockConfigFile: String, configPrefix: String) {
        val config = Properties()
        FileInputStream(originalConfigFile).use {
            config.load(it)
            config.setProperty("$configPrefix.iroha.hostname", IrohaContainer.defaultIrohaAlias)
            config.setProperty(
                "$configPrefix.iroha.port",
                50051.toString()
            )
            val file = File(mockConfigFile)
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }
            file.createNewFile()
            config.store(FileOutputStream(mockConfigFile), "Mock config")
        }
    }

    /**
     * Checks if service is healthy
     * @param serviceContainer - container of service to check
     * @param healthCheckPort - port of health check service
     * @return true if healthy
     */
    fun isServiceHealthy(serviceContainer: KGenericContainerImage, healthCheckPort: Int): Boolean {
        println("PORT is $healthCheckPort")
        println("Mapper PORT is ${serviceContainer.getMappedPort(healthCheckPort)}")
        val healthy = khttp.get(
            "http://127.0.0.1:${serviceContainer.getMappedPort(healthCheckPort)}/health"
        ).statusCode == 200
        return healthy && serviceContainer.isRunning
    }

    /**
     * Cheks if service is dead
     * @param serviceContainer - container of service to check
     * @return true if dead
     */
    fun isServiceDead(serviceContainer: KGenericContainerImage) = !serviceContainer.isRunning

    override fun close() {
        irohaContainer.stop()
    }

}
