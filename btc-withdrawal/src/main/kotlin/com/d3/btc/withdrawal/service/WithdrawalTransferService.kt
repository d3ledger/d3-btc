/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.TransactionCreator
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component

/*
   Service that is used to create/send Bitcoin withdrawal transactions
 */
@Component
class WithdrawalTransferService(
    private val withdrawalStatistics: WithdrawalStatistics,
    private val bitcoinConfig: BitcoinConfig,
    private val transactionCreator: TransactionCreator,
    private val btcRollbackService: BtcRollbackService
) {
    /**
     * Starts withdrawal process. Consists of the following steps:
     * 1) Create transaction
     * 2) Call all "on new transaction" listeners
     * 3) Collect transaction input signatures using current node controlled private keys
     * 4) Mark created transaction as unsigned
     * @param withdrawalDetails - details of withdrawal
     * */
    fun withdraw(
        withdrawalDetails: WithdrawalDetails,
        withdrawalConsensus: WithdrawalConsensus
    ) {
        Result.of {
            registerWithdrawal(withdrawalDetails)
        }.flatMap {
            transactionCreator.createTransaction(
                withdrawalDetails,
                withdrawalConsensus.availableHeight,
                bitcoinConfig.confidenceLevel
            )
        }.map { tx ->
            registerTx(tx)
        }.failure { ex ->
            btcRollbackService.rollback(withdrawalDetails, "Cannot create Bitcoin transaction")
            withdrawalStatistics.incFailedTransfers()
            logger.error("Cannot create withdrawal transaction", ex)
        }
    }

    open fun registerWithdrawal(withdrawalDetails: WithdrawalDetails) {
        logger.info("Withdrawal process $withdrawalDetails has been started")
    }

    open fun registerTx(tx: Transaction) {
        logger.info("Tx $tx has been created and saved in Iroha")
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
