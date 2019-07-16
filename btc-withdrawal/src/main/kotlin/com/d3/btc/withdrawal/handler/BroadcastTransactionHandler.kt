/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.withdrawal.service.FeeService
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.util.irohaUnEscape
import com.google.gson.Gson
import iroha.protocol.Commands
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles 'broadcast' events
 */
@Component
class BroadcastTransactionHandler(private val feeService: FeeService) {

    private val gson = Gson()

    /**
     * Handles 'broadcast' events
     * @param broadcastCommand - command with broadcast details
     */
    fun handleBroadcastCommand(broadcastCommand: Commands.SetAccountDetail) {
        val withdrawalDetails = gson.fromJson(broadcastCommand.value.irohaUnEscape(), WithdrawalDetails::class.java)
        feeService.payFee(withdrawalDetails)
            .fold(
                { logger.info("Fee for withdrawal $withdrawalDetails has been payed") },
                { ex -> logger.error("Cannot pay fee for withdrawal $withdrawalDetails", ex) }
            )
    }

    companion object : KLogging()
}
