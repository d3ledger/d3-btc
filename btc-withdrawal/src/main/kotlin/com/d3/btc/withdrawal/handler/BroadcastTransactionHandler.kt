/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.helper.format.GsonInstance
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
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
class BroadcastTransactionHandler(
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val withdrawalFinalizeService: WithdrawalFinalizeService
) : SetAccountDetailHandler {

    private val gson = GsonInstance.get()

    /**
     * Handles 'broadcast' events
     * @param command - command with broadcast details
     */
    override fun handle(command: Commands.SetAccountDetail) {
        val withdrawalDetails = gson.fromJson(command.value.irohaUnEscape(), WithdrawalDetails::class.java)
        withdrawalFinalizeService.finalize(withdrawalDetails)
            .fold(
                { logger.info("Withdrawal $withdrawalDetails has been finalized") },
                { ex -> logger.error("Cannot finalize withdrawal $withdrawalDetails", ex) }
            )
    }

    override fun filter(command: Commands.SetAccountDetail) =
        command.accountId == btcWithdrawalConfig.broadcastsCredential.accountId

    companion object : KLogging()
}
