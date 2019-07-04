/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.helper.address.getSignThreshold
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.service.WithdrawalTransferService
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.BTC_CONSENSUS_DOMAIN
import com.d3.commons.util.irohaUnEscape
import iroha.protocol.Commands
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles new consensus data appearance
 */
@Component
class NewConsensusDataHandler(
    private val withdrawalTransferService: WithdrawalTransferService,
    private val withdrawalConsensusProvider: WithdrawalConsensusProvider,
    private val btcRollbackService: BtcRollbackService
) {

    /**
     * Handles new consensus command
     * @param addConsensusCommand - new consensus command
     */
    fun handleNewConsensusCommand(addConsensusCommand: Commands.SetAccountDetail) {
        val withdrawalHash = addConsensusCommand.accountId.replace("@$BTC_CONSENSUS_DOMAIN", "")
        val withdrawalConsensus =
            WithdrawalConsensus.fromJson(addConsensusCommand.value.irohaUnEscape())
        withdrawalConsensusProvider.hasBeenEstablished(withdrawalHash).fold({ established ->
            if (established) {
                logger.info("Withdrawal consensus has been already established")
                return@fold
            }
            var savedWithdrawalDetails: WithdrawalDetails? = null
            withdrawalConsensusProvider.getConsensus(withdrawalHash).fold({
                val (withdrawalDetails, consensus) = it
                savedWithdrawalDetails = withdrawalDetails
                val threshold = getSignThreshold(withdrawalConsensus.peers)
                if (consensus.size < threshold) {
                    logger.info(
                        "Not enough consensus data was collected for withdrawal $withdrawalDetails. " +
                                "Need at least $threshold but current value is ${consensus.size}"
                    )
                } else {
                    val commonConsensus = WithdrawalConsensus.createCommonConsensus(consensus)
                    logger.info("Got common withdrawal consensus $commonConsensus. Start withdrawal operation")
                    withdrawalTransferService.withdraw(withdrawalDetails, commonConsensus)
                }
            }, { ex ->
                logger.error("Cannot create consensus", ex)
                if (savedWithdrawalDetails != null) {
                    btcRollbackService.rollback(savedWithdrawalDetails!!, "Cannot create consensus")
                }
            })
        }, { ex ->
            logger.error("Cannot create consensus", ex)
        })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

