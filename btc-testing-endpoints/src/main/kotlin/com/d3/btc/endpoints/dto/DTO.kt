/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.endpoints.dto


/**
 * Class that represents Bitcoin deposit request data
 */
data class BtcDepositRequest(val address: String, val amount: String)

/**
 * Class that represents Bitcoin blocks generation request data
 */
data class BtcGenerateBlocksRequest(val blocks: Int)

/**
 * Class that represents Bitcoin withdrawal operation request data
 */
data class BtcWithdrawalRequest(
    val accountId: String,
    val createdTime: String?,
    val address: String,
    val amount: String,
    val publicKey: String,
    val privateKey: String,
    val blocking: Boolean?
)

/**
 * Class that represents Bitcoin transfer operation request data
 */
data class BtcTransferRequest(
    val accountId: String,
    val destAccountId: String,
    val createdTime: String?,
    val amount: String,
    val publicKey: String,
    val privateKey: String,
    val blocking: Boolean?
)

/**
 * Class that represents plain response
 */
data class PlainResponse(val message: String, val error: Exception? = null) {
    companion object {
        fun ok() = PlainResponse("Ok")
        fun error(ex: Exception) = PlainResponse("Error", ex)
    }
}
