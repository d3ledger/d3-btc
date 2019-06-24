/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.monitoring.Monitoring
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.TransactionCreator
import com.d3.btc.withdrawal.transaction.TransactionHelper
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.btc.withdrawal.transaction.consensus.WithdrawalConsensus
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/*
   Service that is used to create/send Bitcoin withdrawal transactions
 */
@Component
class WithdrawalTransferService(
    private val withdrawalStatistics: WithdrawalStatistics,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val transactionCreator: TransactionCreator,
    private val transactionHelper: TransactionHelper,
    private val btcRollbackService: BtcRollbackService
) : Monitoring() {
    override fun monitor() = withdrawalStatistics

    private val newBtcTransactionListeners = CopyOnWriteArrayList<(tx: Transaction) -> Unit>()

    /**
     * Registers "new transaction was created" event listener
     * For testing purposes only.
     * @param listener - function that will be called when new transaction is created
     */
    fun addNewBtcTransactionListener(listener: (tx: Transaction) -> Unit) {
        newBtcTransactionListeners.add(listener)
    }

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
        transactionCreator.createTransaction(
            withdrawalDetails,
            withdrawalConsensus.availableHeight,
            btcWithdrawalConfig.bitcoin.confidenceLevel
        ).map { (transaction, unspents) ->
            newBtcTransactionListeners.forEach { listener ->
                listener(transaction)
            }
            Pair(transaction, unspents)
        }.map { (transaction, unspents) ->
            transactionHelper.registerUnspents(transaction, unspents)
            logger.info { "Tx ${transaction.hashAsString} was added to collection of unsigned transactions" }
        }.failure { ex ->
            btcRollbackService.rollback(withdrawalDetails, "Cannot create Bitcoin transaction")
            withdrawalStatistics.incFailedTransfers()
            logger.error("Cannot create withdrawal transaction", ex)
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
