/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.endpoints.routing

import com.d3.btc.endpoints.dto.BtcGenerateBlocksRequest
import com.d3.btc.endpoints.dto.PlainResponse
import com.github.jleskovar.btcrpc.BitcoinRpcClient
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import de.nielsfalk.ktor.swagger.created
import de.nielsfalk.ktor.swagger.description
import de.nielsfalk.ktor.swagger.post
import de.nielsfalk.ktor.swagger.responds
import de.nielsfalk.ktor.swagger.version.shared.Group
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.locations.Location
import io.ktor.response.respond
import io.ktor.routing.Routing
import mu.KLogging

private val logger = KLogging().logger

@Group("btc")
@Location("/btc/generate")
class GenerateBlocksLocation

/**
 * Endpoint for generating blocks in the Bitcoin network
 */
fun Routing.generateBlocks(rpcClient: BitcoinRpcClient) {
    post<GenerateBlocksLocation, BtcGenerateBlocksRequest>(
        "execute"
            .description("Generates BTC blocks")
            .responds(created<PlainResponse>())
    ) { _, btcGenerateRequest ->
        logger.info { "Block generation invoked with parameters:${btcGenerateRequest.blocks}" }
        generateBlocks(rpcClient, btcGenerateRequest)
            .fold(
                { call.respond(message = PlainResponse.ok(), status = HttpStatusCode.OK) },
                { ex ->
                    call.respond(
                        message = PlainResponse.error(ex),
                        status = HttpStatusCode.InternalServerError
                    )
                }
            )
    }
}

/**
 * Generates blocks in Bitcoin
 * @param rpcClient - Bitcoin RPC client object
 * @param btcGenerateBlocksRequest - generate blocks request
 * @return result of operation
 */
private fun generateBlocks(
    rpcClient: BitcoinRpcClient,
    btcGenerateBlocksRequest: BtcGenerateBlocksRequest
): Result<Unit, Exception> {
    return Result.of {
        rpcClient.generate(btcGenerateBlocksRequest.blocks)
    }.map {
        logger.info { "${btcGenerateBlocksRequest.blocks} blocks have been generated" }
    }
}
