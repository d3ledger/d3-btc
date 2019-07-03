/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.endpoints

import com.d3.commons.notary.endpoint.ServerInitializationBundle
import com.github.jleskovar.btcrpc.BitcoinRpcClient
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respondText
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import iroha.protocol.Endpoint
import jp.co.soramitsu.iroha.java.IrohaAPI
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import java.math.BigDecimal

const val DEPOSIT_PATH = "deposit"
const val WITHDRAWAL_PATH = "withdraw"
const val TRANSFER_PATH = "transfer"
const val BTC_ASSET = "btc#bitcoin"

private const val CONFIRMATION_BLOCKS = 6

/**
 * Class represent useful functionality for testing via REST API
 */
class TestingEndpoint(
    private val serverBundle: ServerInitializationBundle,
    private val rpcClient: BitcoinRpcClient,
    private val irohaAPI: IrohaAPI,
    private val notaryAccountId: String
) {

    init {
        logger.info { "Start ${serverBundle.ethRefund} test endpoints on port ${serverBundle.port}" }

        val server = embeddedServer(Netty, port = serverBundle.port) {
            install(CORS)
            {
                anyHost()
                allowCredentials = true
            }
            install(ContentNegotiation) {
                gson()
            }
            routing {
                post(serverBundle.ethRefund + "/$DEPOSIT_PATH") {
                    val testDeposit = call.receive(TestDeposit::class)
                    logger.info { "Testing deposit invoked with parameters:${testDeposit.address}, ${testDeposit.amount}" }
                    sendBtc(testDeposit).fold({
                        call.respondText("", status = HttpStatusCode.NoContent)
                    },
                        { ex -> call.respondText(ex.message!!, status = HttpStatusCode.BadRequest) }
                    )
                }
                post(serverBundle.ethRefund + "/$WITHDRAWAL_PATH") {
                    val testWithdrawal = call.receive(TestWithdrawal::class)
                    TestingEndpoint.logger.info { "Testing withdrawal invoked with parameters:${testWithdrawal.address}, ${testWithdrawal.amount}" }
                    withdrawBtc(testWithdrawal).fold({
                        TestingEndpoint.logger.info { "Bitcoins were withdrawn successfully" }
                        call.respondText("", status = HttpStatusCode.NoContent)
                    },
                        { ex -> call.respondText(ex.message!!, status = HttpStatusCode.BadRequest) }
                    )
                }
                post(serverBundle.ethRefund + "/$TRANSFER_PATH") {
                    val testTransfer = call.receive(TestTransfer::class)
                    TestingEndpoint.logger.info { "Testing transfer invoked with parameters:${testTransfer.destAccountId}, ${testTransfer.amount}" }
                    transferBtc(testTransfer).fold({
                        TestingEndpoint.logger.info { "Bitcoins were transferred successfully" }
                        call.respondText("", status = HttpStatusCode.NoContent)
                    },
                        { ex -> call.respondText(ex.message!!, status = HttpStatusCode.BadRequest) }
                    )
                }
            }
        }
        server.start(wait = false)
    }

    private fun sendBtc(testDeposit: TestDeposit): Result<Unit, Exception> {
        return Result.of {
            rpcClient.sendToAddress(testDeposit.address, BigDecimal(testDeposit.amount))
        }.map {
            rpcClient.generate(CONFIRMATION_BLOCKS)
        }.map {
            logger.info { "BTC ${testDeposit.amount} was successfully sent to ${testDeposit.address}" }
        }
    }

    private fun withdrawBtc(testWithdrawal: TestWithdrawal): Result<Unit, Exception> {
        return Result.of {
            transferBtcRaw(
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

    private fun transferBtc(testTransfer: TestTransfer): Result<Unit, Exception> {
        return Result.of {
            transferBtcRaw(
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
        if (blocking == false) {
            irohaAPI.transaction(transaction)
        } else {
            val response = irohaAPI.transaction(transaction).lastElement().blockingGet()
            if (response.txStatus != Endpoint.TxStatus.COMMITTED) {
                throw Exception("Not committed in Iroha. Got response:\n$response")
            }
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

data class TestDeposit(val address: String, val amount: String)

data class TestWithdrawal(
    val accountId: String,
    val createdTime: String?,
    val address: String,
    val amount: String,
    val publicKey: String,
    val privateKey: String,
    val blocking: Boolean?
)

data class TestTransfer(
    val accountId: String,
    val destAccountId: String,
    val createdTime: String?,
    val amount: String,
    val publicKey: String,
    val privateKey: String,
    val blocking: Boolean?
)
