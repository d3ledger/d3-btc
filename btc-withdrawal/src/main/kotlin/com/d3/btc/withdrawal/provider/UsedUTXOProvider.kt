package com.d3.btc.withdrawal.provider

import com.d3.btc.helper.iroha.isCASError
import com.d3.btc.helper.output.irohaKey
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.TransactionBuilder
import mu.KLogging
import org.bitcoinj.core.TransactionOutput
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

private const val REMOVED_UTXO_KEY = "removed"

@Component
open class UsedUTXOProvider(
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper,
    @Qualifier("consensusIrohaConsumer")
    private val consensusIrohaConsumer: IrohaConsumer,
    @Qualifier("utxoStorageAccount")
    private val utxoStorageAccount: String
) {

    /**
     * Checks if a given output has been used already
     * @param withdrawalDetails - details of withdrawal
     * @param output - transaction output that must be checked
     * @return true if output has been used
     */
    fun isUsed(withdrawalDetails: WithdrawalDetails, output: TransactionOutput): Result<Boolean, Exception> {
        return withdrawalQueryHelper.getAccountDetails(
            utxoStorageAccount, consensusIrohaConsumer.creator, output.irohaKey()
        ).map { value ->
            if (value.isPresent) {
                !(value.get() == REMOVED_UTXO_KEY || value.get() == withdrawalDetails.irohaFriendlyHashCode())
            } else {
                false
            }
        }
    }

    fun addRegisterUTXOCommands(
        transactionBuilder: TransactionBuilder,
        withdrawalDetails: WithdrawalDetails,
        unspents: List<TransactionOutput>
    ) {
        unspents.forEach { utxo ->
            transactionBuilder.setAccountDetail(
                utxoStorageAccount,
                utxo.irohaKey(),
                withdrawalDetails.irohaFriendlyHashCode()
            )
        }
    }

    /**
     * Unregisters UTXO
     * @param utxoKeys - UTXO keys to remove
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     * */
    fun unregisterUsedUTXO(
        utxoKeys: List<String>,
        withdrawalDetails: WithdrawalDetails
    ) {
        val transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
            .builder(consensusIrohaConsumer.creator)
        utxoKeys.forEach { utxoItem ->
            transactionBuilder.compareAndSetAccountDetail(
                utxoStorageAccount,
                utxoItem,
                REMOVED_UTXO_KEY,
                withdrawalDetails.irohaFriendlyHashCode()
            )
        }
        consensusIrohaConsumer.send(transactionBuilder.build())
            .fold({
                logger.info("UTXO of withdrawal have been unregistered")
            }, { ex ->
                if (isCASError(ex)) {
                    logger.info("UTXO of withdrawal have been unregistered by someone else.")
                } else {
                    logger.error("Cannot unregister UTXO of withdrawal", ex)
                }
            })
    }

    companion object : KLogging()
}
