package integration.btc

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.dwbridge.monitoring.BitcoinMonitoringEndpoint
import com.d3.btc.dwbridge.monitoring.dto.UTXOSetBtc
import com.d3.commons.util.GsonInstance
import integration.helper.BtcIntegrationHelperUtil
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.store.MemoryBlockStore
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.net.InetAddress
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BitcoinMonitoringEndpointIntegrationTest {

    private val gson = GsonInstance.get()

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
     * @when BTC transactions appear
     * @then properly calculated monitoring data is returned
     */
    @Test
    fun testMonitorBtc() {
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
            integrationHelperUtil.generateBtcBlocks(bitcoinConfig.confidenceLevel - 1)

            // Check sum
            var response = khttp.get("http://127.0.0.1:$webPort/monitoring/availableSumBtc")
            assertEquals(200, response.statusCode)
            var sum = response.jsonObject.getBigDecimal("sumBtc")
            assertEquals(BigDecimal(3), sum)

            // Check UTXO set
            response = khttp.get("http://127.0.0.1:$webPort/monitoring/utxo")
            assertEquals(200, response.statusCode)
            var utxoSet = gson.fromJson(response.jsonObject.toString(), UTXOSetBtc::class.java)
            assertEquals(2, utxoSet.utxoList.size)
            // A and B addresses are here
            assertNotNull(utxoSet.utxoList.find { utxo -> utxo.receiverAddress == addressA.toBase58() })
            assertNotNull(utxoSet.utxoList.find { utxo -> utxo.receiverAddress == addressB.toBase58() })
            // Check that every UTXO has enough confirmations
            assertTrue(utxoSet.utxoList.all { utxo -> utxo.confirmations >= bitcoinConfig.confidenceLevel })
            // Check sum and sum from UTXO set
            assertEquals(sum, getUTXOSetSum(utxoSet))

            // Change confirmation value from 6 to 5
            val confirmations = 5
            //Check sum with defined 'confirmation' param
            response = khttp.get("http://127.0.0.1:$webPort/monitoring/availableSumBtc?confirmations=$confirmations")
            assertEquals(200, response.statusCode)
            sum = response.jsonObject.getBigDecimal("sumBtc")
            assertEquals(BigDecimal(6), sum)

            // Check UTXO set
            response = khttp.get("http://127.0.0.1:$webPort/monitoring/utxo?confirmations=$confirmations")
            assertEquals(200, response.statusCode)
            utxoSet = gson.fromJson(response.jsonObject.toString(), UTXOSetBtc::class.java)
            assertEquals(3, utxoSet.utxoList.size)
            // A, B and C addresses are here
            assertNotNull(utxoSet.utxoList.find { utxo -> utxo.receiverAddress == addressA.toBase58() })
            assertNotNull(utxoSet.utxoList.find { utxo -> utxo.receiverAddress == addressB.toBase58() })
            assertNotNull(utxoSet.utxoList.find { utxo -> utxo.receiverAddress == addressC.toBase58() })
            // Check that every UTXO has enough confirmations
            assertTrue(utxoSet.utxoList.all { utxo -> utxo.confirmations >= confirmations })
            // Check sum and sum from UTXO set
            assertEquals(sum, getUTXOSetSum(utxoSet))
        } finally {
            peerGroup.stop()
        }
    }

    /**
     * Returns sum of BTC from given UTXO set
     * @param utxoSetBtc - UXTO set
     * @return sum value
     */
    private fun getUTXOSetSum(utxoSetBtc: UTXOSetBtc): BigDecimal {
        var sumBtc = BigDecimal.ZERO
        utxoSetBtc.utxoList.forEach { utxo -> sumBtc += utxo.btcAmount }
        return sumBtc
    }
}
