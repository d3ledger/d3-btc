/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import io.ktor.util.sha1
import jp.co.soramitsu.iroha.java.Utils

/**
 * Withdrawal details
 * @param sourceAccountId - account that commits withdrawal
 * @param amountSat - desired amount of SAT to withdraw
 * @param toAddress - Bitcoin destination address in base58 format
 * @param withdrawalTime - time of withdrawal
 * @param withdrawalFeeSat - fee of withdrawal in Satoshi
 */
data class WithdrawalDetails(
    val sourceAccountId: String,
    val toAddress: String,
    val amountSat: Long,
    val withdrawalTime: Long,
    val withdrawalFeeSat: Long
) {
    /**
     * Computes sha1 based Iroha friendly hash code
     * @return hash code as a String
     */
    fun irohaFriendlyHashCode(): String =
        Utils.toHex(
            sha1((sourceAccountId + toAddress + amountSat + withdrawalTime + withdrawalFeeSat).toByteArray())
        ).toLowerCase().substring(0..31)
}
