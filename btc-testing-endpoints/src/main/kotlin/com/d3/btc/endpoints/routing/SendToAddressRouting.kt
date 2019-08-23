/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.endpoints.routing

import com.d3.btc.endpoints.dto.BtcDepositRequest
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
import java.math.BigDecimal

private val logger = KLogging().logger
private const val CONFIRMATION_BLOCKS = 6

@Group("btc")
@Location("/btc/deposit")
class SendToAddressLocation

/**
 * Endpoint for sending BTC to a specified address
 */
fun Routing.sendToAddress(rpcClient: BitcoinRpcClient) {
    post<SendToAddressLocation, BtcDepositRequest>(
        "execute"
            .description("Sends BTC to a specified address")
            .responds(created<PlainResponse>())
    ) { _, btcDepositRequest ->
        logger.info { "Deposit invoked with parameters:${btcDepositRequest.address}, ${btcDepositRequest.amount}" }
        sendBtc(rpcClient, btcDepositRequest)
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
 * Sends BTC to a specified address
 * @param rpcClient - Bitcoin RPC client object
 * @param btcDepositRequest - deposit request
 * @return result of operation
 */
private fun sendBtc(
    rpcClient: BitcoinRpcClient,
    btcDepositRequest: BtcDepositRequest
): Result<Unit, Exception> {
    return Result.of {
        rpcClient.sendToAddress(btcDepositRequest.address, BigDecimal(btcDepositRequest.amount))
    }.map {
        rpcClient.generate(CONFIRMATION_BLOCKS)
    }.map {
        logger.info { "BTC ${btcDepositRequest.amount} has been successfully sent to ${btcDepositRequest.address}" }
    }
}
