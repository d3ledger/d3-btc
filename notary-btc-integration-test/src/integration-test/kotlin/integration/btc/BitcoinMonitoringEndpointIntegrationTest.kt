package integration.btc

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.dwbridge.monitoring.BitcoinMonitoringEndpoint
import integration.helper.BtcIntegrationHelperUtil
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.net.InetAddress

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BitcoinMonitoringEndpointIntegrationTest {

    private val integrationHelperUtil = BtcIntegrationHelperUtil()

    private val endpoint: BitcoinMonitoringEndpoint

    private val webPort = 17074

    private val transferWallet = Wallet(RegTestParams.get())

    private val bitcoinConfig = object : BitcoinConfig {
        override val blockStoragePath = "not used"
        override val confidenceLevel = 6
        override val hosts = "d3-btc-node0"
    }

    init {
        endpoint = BitcoinMonitoringEndpoint(webPort, transferWallet, bitcoinConfig)
    }

    @AfterAll
    fun tearDown() {
        endpoint.close()
    }

    /**
     * @given running BTC monitoring web service
     * @when 'monitoring/availableSumBtc' API method is called
     * @then properly calculated sum of BTC is returned
     */
    @Test
    fun testMonitorBtcSum() {
        integrationHelperUtil.generateBtcInitialBlocks()
        val addressA = transferWallet.currentReceiveAddress()
        val addressB = transferWallet.currentReceiveAddress()
        val addressC = transferWallet.currentReceiveAddress()

        val params = RegTestParams.get()
        val blockStore = MemoryBlockStore(params)
        val chain = BlockChain(params, transferWallet, blockStore)

        val peerGroup = PeerGroup(params, chain)
        try {
            peerGroup.addAddress(InetAddress.getByName(BitcoinConfig.extractHosts(bitcoinConfig)[0]))
            peerGroup.startAsync()
            peerGroup.downloadBlockChain()
            integrationHelperUtil.sendBtc(addressA.toBase58(), BigDecimal(1), confirmations = 0)
            integrationHelperUtil.sendBtc(addressB.toBase58(), BigDecimal(2), confirmations = 0)
            integrationHelperUtil.generateBtcBlocks(bitcoinConfig.confidenceLevel)
            integrationHelperUtil.sendBtc(addressC.toBase58(), BigDecimal(3), confirmations = 0)
            integrationHelperUtil.generateBtcBlocks(bitcoinConfig.confidenceLevel - 5)
            val response = khttp.get("http://127.0.0.1:$webPort/monitoring/availableSumBtc")
            assertEquals(200, response.statusCode)
            val sum = response.jsonObject.getBigDecimal("btc")
            assertEquals(BigDecimal(3), sum)
        } finally {
            peerGroup.stop()
        }
    }
}
