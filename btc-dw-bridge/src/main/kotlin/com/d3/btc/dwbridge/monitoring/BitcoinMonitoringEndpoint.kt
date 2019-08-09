package com.d3.btc.dwbridge.monitoring

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.helper.currency.satToBtc
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.Closeable
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * Endpoint for monitoring BTC
 */
@Component
class BitcoinMonitoringEndpoint(
    @Qualifier("webPort")
    private val webPort: Int,
    @Qualifier("transferWallet")
    private val transferWallet: Wallet,
    private val bitcoinConfig: BitcoinConfig
) : Closeable {

    private val server: ApplicationEngine

    init {
        server = embeddedServer(Netty, port = webPort) {
            install(CORS)
            {
                anyHost()
            }
            install(ContentNegotiation) {
                gson()
            }
            routing {
                // Monitors available amount of BTC
                get("/monitoring/availableSumBtc") {
                    call.respond(mapOf("btc" to getSumBtc()))
                }
            }
        }
        server.start(wait = false)
    }

    /**
     * Returns sum of BTC that is available to spend
     * @return sum of BTC
     */
    private fun getSumBtc(): BigDecimal {
        var sumSat = 0L
        transferWallet.unspents
            .filter { utxo -> utxo.parentTransactionDepthInBlocks >= bitcoinConfig.confidenceLevel }
            .forEach { utxo -> sumSat += utxo.value.value }
        return satToBtc(sumSat)
    }

    override fun close() {
        server.stop(gracePeriod = 5, timeout = 5, timeUnit = TimeUnit.SECONDS)
    }
}
