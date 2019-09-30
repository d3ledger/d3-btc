/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.helper.address.isValidBtcAddress
import com.d3.btc.helper.currency.btcToSat
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.provider.BroadcastsProvider
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.btc.withdrawal.transaction.isDust
import iroha.protocol.Commands
import mu.KLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Handler that handles Iroha Bitcoin transfers
 */
@Component
class NewTransferHandler(
    private val withdrawalStatistics: WithdrawalStatistics,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val withdrawalConsensusProvider: WithdrawalConsensusProvider,
    private val btcRollbackService: BtcRollbackService,
    private val broadcastsProvider: BroadcastsProvider
) {

    /**
     * Handles "transfer asset" command
     * @param transferCommand - object with "transfer asset" data: source account, destination account, amount and etc
     * @param feeInBtc - amount of fee for withdrawal in Bitcoin. May be zero.
     * @param withdrawalTime - time of withdrawal
     */
    fun handleTransferCommand(
        transferCommand: Commands.TransferAsset,
        feeInBtc: BigDecimal,
        withdrawalTime: Long
    ) {
        if (transferCommand.destAccountId != btcWithdrawalConfig.withdrawalCredential.accountId) {
            return
        }
        val destinationAddress = transferCommand.description
        val sourceAccountId = transferCommand.srcAccountId
        val btcAmount = BigDecimal(transferCommand.amount)
        val satAmount = btcToSat(btcAmount)
        val fee = btcToSat(feeInBtc)
        val withdrawalDetails =
            WithdrawalDetails(sourceAccountId, destinationAddress, satAmount, withdrawalTime, fee)
        logger.info {
            "Withdrawal event(" +
                    "from:$sourceAccountId " +
                    "to:$destinationAddress " +
                    "amount:${btcAmount.toPlainString()} " +
                    "fee:$fee " +
                    "hash:${withdrawalDetails.irohaFriendlyHashCode()})"
        }
        broadcastsProvider.hasBeenBroadcasted(withdrawalDetails)
            .fold({ broadcasted ->
                if (broadcasted) {
                    logger.info("Withdrawal $withdrawalDetails has been broadcasted before")
                } else {
                    checkAndStartConsensus(withdrawalDetails)
                }
            }, { ex ->
                btcRollbackService.rollback(withdrawalDetails, "Iroha error")
                logger.error("Can't execute withdrawal operation due to Iroha error", ex)
            })
    }

    /**
     * Checks if withdrawal is valid and creates withdrawal consensus if possible
     * @param withdrawalDetails - details of withdrawal
     */
    protected fun checkAndStartConsensus(withdrawalDetails: WithdrawalDetails) {
        // Check if withdrawal has valid destination address
        if (!isValidBtcAddress(withdrawalDetails.toAddress)) {
            logger.warn { "Cannot execute transfer. Destination '${withdrawalDetails.toAddress}' is not a valid base58 address." }
            btcRollbackService.rollback(
                withdrawalDetails, "Invalid address"
            )
            return
        }
        // Check if withdrawal amount is not too little
        if (isDust(withdrawalDetails.amountSat)) {
            btcRollbackService.rollback(
                withdrawalDetails, "Too small amount"
            )
            logger.warn { "Can't spend SAT ${withdrawalDetails.amountSat}, because it's considered a dust" }
            return
        }
        // Create consensus
        withdrawalStatistics.incTotalTransfers()
        startConsensusProcess(withdrawalDetails)
    }

    /**
     * Starts consensus creation process
     * @param withdrawalDetails - details of withdrawal
     */
    protected fun startConsensusProcess(withdrawalDetails: WithdrawalDetails) {
        withdrawalConsensusProvider.createConsensusData(withdrawalDetails).fold({
            logger.info("Consensus data for $withdrawalDetails has been created")
        }, { ex ->
            //TODO need to rollback UTXO as well
            logger.error("Cannot create consensus for withdrawal $withdrawalDetails", ex)
            btcRollbackService.rollback(withdrawalDetails, "Cannot create consensus")
        })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
