/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.helper.format.GsonInstance
import com.d3.btc.withdrawal.service.WithdrawalFinalizeService
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.util.irohaUnEscape
import iroha.protocol.Commands
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles 'broadcast' events
 */
@Component
class BroadcastTransactionHandler(private val withdrawalFinalizeService: WithdrawalFinalizeService) {

    private val gson = GsonInstance.get()

    /**
     * Handles 'broadcast' events
     * @param broadcastCommand - command with broadcast details
     */
    fun handleBroadcastCommand(broadcastCommand: Commands.SetAccountDetail) {
        val withdrawalDetails = gson.fromJson(broadcastCommand.value.irohaUnEscape(), WithdrawalDetails::class.java)
        withdrawalFinalizeService.finalize(withdrawalDetails)
            .fold(
                { logger.info("Withdrawal $withdrawalDetails has been finalized") },
                { ex -> logger.error("Cannot finalize withdrawal $withdrawalDetails", ex) }
            )
    }

    companion object : KLogging()
}
