package com.d3.btc.dwbridge.monitoring

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.dwbridge.monitoring.routing.availableSumBtc
import com.d3.btc.dwbridge.monitoring.routing.availableUTXOSet
import de.nielsfalk.ktor.swagger.SwaggerSupport
import de.nielsfalk.ktor.swagger.version.v2.Swagger
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.locations.Locations
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.Closeable
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
            install(Locations)
            // Visit `apidocs` to access Swagger
            install(SwaggerSupport) {
                swagger = Swagger()
            }
            routing {
                availableSumBtc(transferWallet, bitcoinConfig)
                availableUTXOSet(transferWallet, bitcoinConfig)
            }
        }
        server.start(wait = false)
    }

    override fun close() {
        server.stop(gracePeriod = 5, timeout = 5, timeUnit = TimeUnit.SECONDS)
    }
}
