package com.d3.btc.withdrawal.handler

import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.service.BtcRollbackService
import com.d3.btc.withdrawal.transaction.SignCollector
import com.d3.btc.withdrawal.transaction.TransactionsStorage
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import iroha.protocol.Commands
import mu.KLogging
import org.springframework.stereotype.Component

@Component
class NewTransactionCreatedHandler(
    private val signCollector: SignCollector,
    private val transactionsStorage: TransactionsStorage,
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val btcRollbackService: BtcRollbackService
) {

    /**
     * Handles "create new transaction" commands
     * @param createNewTxCommand - command object with created transaction
     */
    fun handleCreateTransactionCommand(
        createNewTxCommand: Commands.SetAccountDetail
    ) {
        val txHash = createNewTxCommand.key
        transactionsStorage.get(createNewTxCommand.key).map { (withdrawalDetails, transaction) ->
            logger.info { "Tx to sign\n$transaction" }
            signCollector.signAndSave(transaction, btcWithdrawalConfig.btcKeysWalletPath).fold({
                logger.info { "Signatures for ${transaction.hashAsString} were successfully processed" }
            }, { ex ->
                logger.error("Cannot sign transaction $transaction", ex)
                btcRollbackService.rollback(
                    withdrawalDetails.sourceAccountId,
                    withdrawalDetails.amountSat,
                    withdrawalDetails.withdrawalTime,
                    "Cannot sign"
                )
            })
        }.failure { ex -> logger.error("Cannot get transaction from Iroha by hash ${txHash}", ex) }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
