/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.endpoints.routing

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.endpoints.dto.BtcTransferRequest
import com.d3.btc.endpoints.dto.BtcWithdrawalRequest
import com.d3.btc.endpoints.dto.PlainResponse
import com.github.kittinunf.result.Result
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
import iroha.protocol.Endpoint
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import jp.co.soramitsu.iroha.java.subscription.WaitForTerminalStatus
import mu.KLogging

private val logger = KLogging().logger

@Group("btc")
@Location("/btc/withdraw")
class WithdrawalLocation

@Group("btc")
@Location("/btc/transfer")
class TransferLocation

/**
 * Endpoint for BTC withdrawal operations
 */
fun Routing.withdraw(irohaAPI: IrohaAPI, notaryAccountId: String) {
    post<WithdrawalLocation, BtcWithdrawalRequest>(
        "execute"
            .description("Withdraw BTC")
            .responds(created<PlainResponse>())
    ) { _, btcWithdrawRequest ->
        logger.info { "Withdrawal invoked with parameters:${btcWithdrawRequest.address}, ${btcWithdrawRequest.amount}" }
        withdrawBtc(irohaAPI, notaryAccountId, btcWithdrawRequest).fold(
            { call.respond(message = PlainResponse.ok(), status = HttpStatusCode.OK) },
            { ex -> call.respond(message = PlainResponse.error(ex), status = HttpStatusCode.InternalServerError) }
        )
    }
}

/**
 * Endpoint for BTC transfer operations
 */
fun Routing.transfer(irohaAPI: IrohaAPI) {
    post<TransferLocation, BtcTransferRequest>(
        "execute"
            .description("Transfer BTC")
            .responds(created<PlainResponse>())
    ) { _, btcTransferRequest ->
        logger.info { "Transfer invoked with parameters:${btcTransferRequest.destAccountId}, ${btcTransferRequest.amount}" }
        transferBtc(irohaAPI, btcTransferRequest).fold(
            { call.respond(message = PlainResponse.ok(), status = HttpStatusCode.OK) },
            { ex -> call.respond(message = PlainResponse.error(ex), status = HttpStatusCode.InternalServerError) }
        )
    }
}

private val subscriptionStrategy = WaitForTerminalStatus(
    listOf(
        Endpoint.TxStatus.STATELESS_VALIDATION_FAILED,
        Endpoint.TxStatus.COMMITTED,
        Endpoint.TxStatus.MST_EXPIRED,
        Endpoint.TxStatus.REJECTED,
        Endpoint.TxStatus.UNRECOGNIZED
    )
)

private fun withdrawBtc(
    irohaAPI: IrohaAPI,
    notaryAccountId: String,
    testWithdrawal: BtcWithdrawalRequest
): Result<Unit, Exception> {
    return Result.of {
        transferBtcRaw(
            irohaAPI,
            testWithdrawal.accountId,
            notaryAccountId,
            testWithdrawal.address,
            testWithdrawal.amount,
            testWithdrawal.createdTime,
            testWithdrawal.publicKey,
            testWithdrawal.privateKey,
            testWithdrawal.blocking
        )
    }
}

private fun transferBtc(irohaAPI: IrohaAPI, testTransfer: BtcTransferRequest): Result<Unit, Exception> {
    return Result.of {
        transferBtcRaw(
            irohaAPI,
            testTransfer.accountId,
            testTransfer.destAccountId,
            "test",
            testTransfer.amount,
            testTransfer.createdTime,
            testTransfer.publicKey,
            testTransfer.privateKey,
            testTransfer.blocking
        )
    }
}

private fun transferBtcRaw(
    irohaAPI: IrohaAPI,
    accountId: String,
    destAccountId: String,
    description: String,
    amount: String,
    createdTime: String?,
    publicKey: String,
    privateKey: String,
    blocking: Boolean?
) {
    val transaction =
        Transaction.builder(accountId, createdTime?.toLongOrNull() ?: System.currentTimeMillis())
            .transferAsset(
                accountId,
                destAccountId,
                BTC_ASSET,
                description,
                amount
            )
            .setQuorum(2)
            .sign(
                Utils.parseHexKeypair(
                    publicKey,
                    privateKey
                )
            )
            .build()
    val hash = Utils.toHex(Utils.hash(transaction))
    if (blocking == false) {
        irohaAPI.transaction(transaction)
        logger.info { "Sent nonblocking tx: $hash" }
    } else {
        logger.info { "Sending blocking tx: $hash" }
        val response = irohaAPI.transaction(transaction, subscriptionStrategy).lastElement().blockingGet()
        if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
            throw Exception("Not committed in Iroha. Got response:\n$response")
        }
        logger.info { "Bitcoins were transferred successfully" }
    }
}
