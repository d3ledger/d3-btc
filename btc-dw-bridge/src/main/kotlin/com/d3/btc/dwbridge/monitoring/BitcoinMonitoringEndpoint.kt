package com.d3.btc.dwbridge.monitoring

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.dwbridge.monitoring.dto.AvailableSumBtc
import com.d3.btc.dwbridge.monitoring.dto.UTXOBtc
import com.d3.btc.dwbridge.monitoring.dto.UTXOSetBtc
import com.d3.btc.helper.address.outPutToBase58Address
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
import java.util.concurrent.TimeUnit

//TODO add swagger
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
                    val confirmations = call.parameters["confirmations"]
                    if (confirmations == null) {
                        call.respond(getAvailableSumBtc())
                    } else {
                        call.respond(getAvailableSumBtc(confirmations.toInt()))
                    }
                }

                // Monitors available UTXO set
                get("/monitoring/utxo") {
                    val confirmations = call.parameters["confirmations"]
                    if (confirmations == null) {
                        call.respond(getUTXOSet())
                    } else {
                        call.respond(getUTXOSet(confirmations.toInt()))
                    }
                }
            }
        }
        server.start(wait = false)
    }

    /**
     * Returns sum of BTC that is available to spend
     * @param minConfirmations - minimum number of confirmations(depth in blocks)
     * @return sum of BTC
     */
    private fun getAvailableSumBtc(minConfirmations: Int = bitcoinConfig.confidenceLevel): AvailableSumBtc {
        var sumSat = 0L
        transferWallet.unspents
            .filter { utxo -> utxo.parentTransactionDepthInBlocks >= minConfirmations }
            .forEach { utxo -> sumSat += utxo.value.value }
        return AvailableSumBtc(satToBtc(sumSat))
    }

    /**
     * Returns available UTXO set
     * @param minConfirmations - minimum number of confirmations(depth in blocks)
     * @return UTXO set
     */
    private fun getUTXOSet(minConfirmations: Int = bitcoinConfig.confidenceLevel) = UTXOSetBtc(transferWallet.unspents
        .filter { utxo -> utxo.parentTransactionDepthInBlocks >= minConfirmations }
        .map { utxo ->
            UTXOBtc(
                utxo.parentTransactionDepthInBlocks,
                satToBtc(utxo.value.value),
                utxo.parentTransactionHash.toString(),
                utxo.index,
                outPutToBase58Address(utxo)
            )
        })

    override fun close() {
        server.stop(gracePeriod = 5, timeout = 5, timeUnit = TimeUnit.SECONDS)
    }
}
