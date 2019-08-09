/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.healthcheck

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Health check endpoint
 */
@Component
class HealthCheckEndpoint(
    @Qualifier("healthCheckPort")
    private val healthCheckPort: Int,
    private val serviceInitHealthCheck: ServiceInitHealthCheck
) : Closeable {

    private val server: ApplicationEngine

    /**
     * Initiates ktor based health check server
     */
    init {
        server = embeddedServer(Netty, port = healthCheckPort) {
            install(CORS)
            {
                anyHost()
            }
            install(ContentNegotiation) {
                gson()
            }
            routing {
                get("/actuator/health") {
                    val (message, status) = getHealth()
                    call.respond(
                        status, mapOf(
                            "status" to message
                        )
                    )
                }
            }
        }
        server.start(wait = false)
    }

    /**
     * Returns health status in a form <message(UP or DOWN), HTTP status(200 or 500)>
     */
    private fun getHealth(): Pair<String, HttpStatusCode> {
        return if (serviceInitHealthCheck.isHealthy()) {
            Pair("UP", HttpStatusCode.OK)
        } else {
            Pair("DOWN", HttpStatusCode.InternalServerError)
        }
    }

    override fun close() {
        server.stop(gracePeriod = 5, timeout = 5, timeUnit = TimeUnit.SECONDS)
    }
}
