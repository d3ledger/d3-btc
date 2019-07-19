/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.config.BTC_SIGN_COLLECT_DOMAIN
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.provider.BroadcastsProvider
import com.d3.btc.withdrawal.provider.UTXOProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics
import com.d3.btc.withdrawal.transaction.SignCollector
import com.d3.btc.withdrawal.transaction.TransactionsStorage
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/*
    Class that is used to handle new input signature appearance events
 */
@Component
class NewSignatureEventHandler(
    @Qualifier("transferWallet")
    private val transferWallet: Wallet,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val withdrawalStatistics: WithdrawalStatistics,
    private val signCollector: SignCollector,
    private val transactionsStorage: TransactionsStorage,
    private val bitcoinUTXOProvider: UTXOProvider,
    private val btcRollbackService: BtcRollbackService,
    private val peerGroup: PeerGroup,
    private val broadcastsProvider: BroadcastsProvider
) : SetAccountDetailHandler {

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
     * @param command - command object full of signatures
     */
    override fun handle(
        command: Commands.SetAccountDetail
    ) {
        val shortTxHash = command.accountId.replace("@$BTC_SIGN_COLLECT_DOMAIN", "")
        var savedWithdrawal: WithdrawalDetails? = null
        var savedTx: Transaction? = null
        transactionsStorage.get(shortTxHash).map { withdrawal ->
            savedWithdrawal = withdrawal.first
            savedTx = withdrawal.second
            broadcastsProvider.hasBeenBroadcasted(withdrawal.first).fold({ broadcasted ->
                if (broadcasted) {
                    logger.info("No need to sign. Withdrawal ${withdrawal.first} has been broadcasted before")
                } else {
                    val withdrawalCommand = withdrawal.first
                    val tx = withdrawal.second
                    broadcastIfEnoughSignatures(tx, withdrawalCommand)
                }
            }, { ex -> throw ex })
        }.failure { ex ->
            if (savedWithdrawal != null && savedTx != null) {
                btcRollbackService.rollback(
                    savedWithdrawal!!, "Cannot handle new signature"
                )
                bitcoinUTXOProvider.unregisterUnspents(savedTx!!, savedWithdrawal!!)
                    .failure { e -> logger.error("Cannot unregister unspents", e) }
            }
            logger.error("Cannot handle new signature for tx $shortTxHash", ex)
            withdrawalStatistics.incFailedTransfers()
        }
    }

    /**
     * Signs and broadcasts transaction if enough signatures have been collected
     * @param tx - transaction to signs and broadcast
     * @param withdrawalDetails - details of withdrawal
     */
    protected fun broadcastIfEnoughSignatures(
        tx: Transaction,
        withdrawalDetails: WithdrawalDetails
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
            var successfullyBroadcasted = false
            logger.info { "Tx $originalHash has enough signatures" }
            signCollector.fillTxWithSignatures(tx, signatures)
                .map {
                    logger.info { "Tx(originally known as $originalHash) is ready to be broadcasted $tx" }
                    broadcastTransactionListeners.forEach { listener ->
                        listener(tx)
                    }
                    //Wait until it is broadcasted to all the connected peers
                    peerGroup.broadcastTransaction(tx).future().get()
                    successfullyBroadcasted = true
                }.map {
                    // Save wallet
                    transferWallet.saveToFile(File(btcWithdrawalConfig.btcTransfersWalletPath))
                }.map {
                    // Mark withdrawal as 'broadcasted'
                    broadcastsProvider.markAsBroadcasted(withdrawalDetails)
                }
                .fold({
                    logger.info { "Tx ${tx.hashAsString} was successfully broadcasted" }
                    withdrawalStatistics.incSucceededTransfers()
                }, { ex ->
                    withdrawalStatistics.incFailedTransfers()
                    logger.error("Cannot complete tx $originalHash", ex)
                    if (successfullyBroadcasted) {
                        logger.warn("Cannot rollback $withdrawalDetails because it has been successfully broadcasted recently")
                    } else {
                        btcRollbackService.rollback(
                            withdrawalDetails, "Cannot complete Bitcoin transaction"
                        )
                        bitcoinUTXOProvider.unregisterUnspents(tx, withdrawalDetails)
                            .failure { e -> logger.error("Cannot unregister unspents", e) }
                    }
                })

        }, { ex ->
            btcRollbackService.rollback(
                withdrawalDetails, "Cannot get signatures for Bitcoin transaction"
            )
            bitcoinUTXOProvider.unregisterUnspents(tx, withdrawalDetails)
                .failure { e -> logger.error("Cannot unregister unspents", e) }
            withdrawalStatistics.incFailedTransfers()
            logger.error("Cannot get signatures for tx $originalHash", ex)
        })
    }

    override fun filter(command: Commands.SetAccountDetail) = command.accountId.endsWith("@$BTC_SIGN_COLLECT_DOMAIN")

    /**
     * Logger
     */
    companion object : KLogging()
}
