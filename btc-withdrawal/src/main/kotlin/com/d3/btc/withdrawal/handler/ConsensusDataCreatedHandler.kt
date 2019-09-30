/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.service.WithdrawalTransferService
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.commons.util.irohaUnEscape
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles consensus data appearance
 */
@Component
class ConsensusDataCreatedHandler(
    private val withdrawalTransferService: WithdrawalTransferService,
    private val btcWithdrawalConfig: BtcWithdrawalConfig
) : SetAccountDetailHandler() {

    /**
     * Handles consensus command
     * @param setAccountDetailEvent - new consensus event
     */
    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val withdrawalConsensus =
            WithdrawalConsensus.fromJson(setAccountDetailEvent.command.value.irohaUnEscape())
        withdrawalTransferService.withdraw(withdrawalConsensus)
    }

    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.accountId == btcWithdrawalConfig.btcConsensusCredential.accountId &&
                setAccountDetailEvent.creator == btcWithdrawalConfig.withdrawalCredential.accountId

    /**
     * Logger
     */
    companion object : KLogging()
}

