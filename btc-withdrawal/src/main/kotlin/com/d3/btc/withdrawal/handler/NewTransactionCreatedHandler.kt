package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.provider.BroadcastsProvider
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.transaction.SignCollector
import com.d3.btc.withdrawal.transaction.TransactionsStorage
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component

@Component
class NewTransactionCreatedHandler(
    private val signCollector: SignCollector,
    private val transactionsStorage: TransactionsStorage,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val btcRollbackService: BtcRollbackService,
    private val broadcastsProvider: BroadcastsProvider
) : SetAccountDetailHandler() {

    /**
     * Handles "create new transaction" commands
     * @param setAccountDetailEvent - event object with created transaction
     */
    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val txHash = setAccountDetailEvent.command.key
        var savedWithdrawalDetails: WithdrawalDetails? = null
        var savedWithdrawalConsensus: WithdrawalConsensus? = null
        var savedTransaction: Transaction? = null
        transactionsStorage.get(txHash).map { (withdrawalConsensus, transaction) ->
            savedWithdrawalConsensus = withdrawalConsensus
            savedWithdrawalDetails = withdrawalConsensus.withdrawalDetails
            savedTransaction = transaction
        }.flatMap {
            broadcastsProvider.hasBeenBroadcasted(savedWithdrawalDetails!!)
        }.flatMap { broadcasted ->
            if (broadcasted) {
                logger.info("Withdrawal $savedWithdrawalDetails has been broadcasted before")
                Result.of(Unit)
            } else {
                val transaction = savedTransaction!!
                logger.info { "Tx to sign\n$savedTransaction" }
                signCollector.signAndSave(
                    savedWithdrawalConsensus!!,
                    transaction,
                    btcWithdrawalConfig.btcKeysWalletPath
                )
            }
        }.map {
            logger.info("Signatures for ${savedTransaction!!.hashAsString} were successfully processed")
        }.failure { ex ->
            if (savedTransaction != null && savedWithdrawalDetails != null) {
                logger.error("Cannot handle new transaction $savedTransaction", ex)
                btcRollbackService.rollback(
                    savedWithdrawalDetails!!, "Cannot sign", savedTransaction
                )
            } else {
                logger.error("Cannot handle new transaction with hash $txHash", ex)
            }
        }
    }

    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.accountId == btcWithdrawalConfig.txStorageAccount &&
                setAccountDetailEvent.creator == btcWithdrawalConfig.withdrawalCredential.accountId

    /**
     * Logger
     */
    companion object : KLogging()
}
