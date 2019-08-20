/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.service.BtcWithdrawalFinalizeService
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaUnEscape
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles 'broadcast' events
 */
@Component
class BroadcastTransactionHandler(
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val btcWithdrawalFinalizeService: BtcWithdrawalFinalizeService
) : SetAccountDetailHandler() {

    private val gson = GsonInstance.get()

    /**
     * Handles 'broadcast' events
     * @param setAccountDetailEvent - event with broadcast details
     */
    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val withdrawalDetails =
            gson.fromJson(setAccountDetailEvent.command.value.irohaUnEscape(), WithdrawalDetails::class.java)
        if (withdrawalDetails == null) {
            logger.error("Cannot handle 'null' withdrawal")
            return
        }
        btcWithdrawalFinalizeService.finalize(withdrawalDetails)
            .fold(
                { logger.info("Withdrawal $withdrawalDetails has been finalized") },
                { ex -> logger.error("Cannot finalize withdrawal $withdrawalDetails", ex) }
            )
    }

    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.accountId == btcWithdrawalConfig.broadcastsCredential.accountId &&
                setAccountDetailEvent.creator == btcWithdrawalConfig.broadcastsCredential.accountId

    companion object : KLogging()
}
