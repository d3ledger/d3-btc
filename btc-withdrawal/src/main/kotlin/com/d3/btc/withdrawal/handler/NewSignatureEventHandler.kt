/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.SignCollector
import com.d3.btc.withdrawal.transaction.TransactionHelper
import com.d3.btc.withdrawal.transaction.TransactionsStorage
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.BTC_SIGN_COLLECT_DOMAIN
import com.github.kittinunf.result.map
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/*
    Class that is used to handle new input signature appearance events
 */
@Component
class NewSignatureEventHandler(
    private val withdrawalStatistics: WithdrawalStatistics,
    private val signCollector: SignCollector,
    private val transactionsStorage: TransactionsStorage,
    private val transactionHelper: TransactionHelper,
    private val btcRollbackService: BtcRollbackService,
    private val peerGroup: PeerGroup
) {

    private val broadcastTransactionListeners = CopyOnWriteArrayList<(tx: Transaction) -> Unit>()

    /**
     * Registers "broadcast transaction" event listener
     * For testing purposes only.
     * @param listener - function that will be called when a transaction is about to be broadcasted
     */
    fun addBroadcastTransactionListeners(listener: (tx: Transaction) -> Unit) {
        broadcastTransactionListeners.add(listener)
    }

    /**
     * Handles "add new input signatures" commands
     * @param addNewSignatureCommand - command object full of signatures
     * @param onBroadcastSuccess - function that will be called right after successful tx broadcast
     */
    fun handleNewSignatureCommand(
        addNewSignatureCommand: Commands.SetAccountDetail,
        onBroadcastSuccess: () -> Unit
    ) {
        val shortTxHash = addNewSignatureCommand.accountId.replace("@$BTC_SIGN_COLLECT_DOMAIN", "")
        transactionsStorage.get(shortTxHash).fold({ withdrawal ->
            val withdrawalCommand = withdrawal.first
            val tx = withdrawal.second
            broadcastIfEnoughSignatures(tx, withdrawalCommand, onBroadcastSuccess)
        }, { ex ->
            logger.error("Cannot read transaction by hash $shortTxHash", ex)
            withdrawalStatistics.incFailedTransfers()
        })
    }

    /**
     * Signs and broadcasts transaction if enough signatures have been collected
     * @param tx - transaction to signs and broadcast
     * @param withdrawalDetails - details of withdrawal
     * @param onBroadcastSuccess - function that will be called on successful broadcast attempt
     */
    private fun broadcastIfEnoughSignatures(
        tx: Transaction,
        withdrawalDetails: WithdrawalDetails,
        onBroadcastSuccess: () -> Unit
    ) {
        // Hash of transaction will be changed after signing. This is why we keep an "original" hash
        val originalHash = tx.hashAsString
        signCollector.getSignatures(originalHash).fold({ signatures ->
            val enoughSignaturesCollected =
                signCollector.isEnoughSignaturesCollected(tx, signatures)
            if (!enoughSignaturesCollected) {
                logger.info { "Not enough signatures were collected for tx $originalHash" }
                return
            }
            logger.info { "Tx $originalHash has enough signatures" }
            signCollector.fillTxWithSignatures(tx, signatures)
                .map {
                    logger.info { "Tx(originally known as $originalHash) is ready to be broadcasted $tx" }
                    broadcastTransactionListeners.forEach { listener ->
                        listener(tx)
                    }
                    //Wait until it is broadcasted to all connected peers
                    peerGroup.broadcastTransaction(tx).future().get()
                }.map {
                    onBroadcastSuccess()
                }.fold({
                    logger.info { "Tx ${tx.hashAsString} was successfully broadcasted" }
                    withdrawalStatistics.incSucceededTransfers()
                }, { ex ->
                    transactionHelper.unregisterUnspents(originalHash)
                    withdrawalStatistics.incFailedTransfers()
                    logger.error("Cannot complete tx $originalHash", ex)
                    btcRollbackService.rollback(
                        withdrawalDetails.sourceAccountId,
                        withdrawalDetails.amountSat,
                        withdrawalDetails.withdrawalTime,
                        "Cannot complete Bitcoin transaction"
                    )
                })

        }, { ex ->
            transactionHelper.unregisterUnspents(originalHash)
            btcRollbackService.rollback(
                withdrawalDetails.sourceAccountId,
                withdrawalDetails.amountSat,
                withdrawalDetails.withdrawalTime,
                "Cannot get signatures for Bitcoin transaction"
            )
            withdrawalStatistics.incFailedTransfers()
            logger.error("Cannot get signatures for tx $originalHash", ex)
        })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
