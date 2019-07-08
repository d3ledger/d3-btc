package com.d3.btc.withdrawal.handler

import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.provider.BroadcastsProvider
import com.d3.btc.withdrawal.provider.UTXOProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.transaction.SignCollector
import com.d3.btc.withdrawal.transaction.TransactionsStorage
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component

@Component
class NewTransactionCreatedHandler(
    private val signCollector: SignCollector,
    private val transactionsStorage: TransactionsStorage,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val btcRollbackService: BtcRollbackService,
    private val bitcoinUTXOProvider: UTXOProvider,
    private val broadcastsProvider: BroadcastsProvider
) {

    /**
     * Handles "create new transaction" commands
     * @param createNewTxCommand - command object with created transaction
     */
    fun handleCreateTransactionCommand(
        createNewTxCommand: Commands.SetAccountDetail
    ) {
        val txHash = createNewTxCommand.key
        var savedWithdrawalDetails: WithdrawalDetails? = null
        var savedTransaction: Transaction? = null
        transactionsStorage.get(txHash).map { (withdrawalDetails, transaction) ->
            savedWithdrawalDetails = withdrawalDetails
            savedTransaction = transaction
        }.flatMap {
            broadcastsProvider.hasBeenBroadcasted(savedWithdrawalDetails!!)
        }.map { broadcasted ->
            if (broadcasted) {
                logger.info("Withdrawal $savedWithdrawalDetails has been broadcasted before")
                return@map
            }
            val transaction = savedTransaction!!
            logger.info { "Tx to sign\n$savedTransaction" }
            signCollector.signAndSave(transaction, btcWithdrawalConfig.btcKeysWalletPath)
        }.map {
            logger.info { "Signatures for ${savedTransaction!!.hashAsString} were successfully processed" }
        }.failure { ex ->
            if (savedTransaction != null && savedWithdrawalDetails != null) {
                logger.error("Cannot handle new transaction $savedTransaction", ex)
                btcRollbackService.rollback(
                    savedWithdrawalDetails!!, "Cannot sign"
                )
                bitcoinUTXOProvider.unregisterUnspents(savedTransaction!!, savedWithdrawalDetails!!)
                    .failure { e -> NewSignatureEventHandler.logger.error("Cannot unregister unspents", e) }
            } else {
                logger.error("Cannot handle new transaction with hash $txHash", ex)
            }
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
