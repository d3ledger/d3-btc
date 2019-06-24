package com.d3.btc.withdrawal.provider

import com.d3.btc.helper.input.removeFromIrohaKeyValue
import com.d3.btc.helper.output.irohaKey
import com.d3.btc.helper.output.isRemovedFromIroha
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
open class UsedUTXOProvider(
    private val irohaQueryHelper: IrohaQueryHelper,
    @Qualifier("withdrawalConsumer")
    private val withdrawalConsumer: IrohaConsumer,
    @Qualifier("utxoStorageAccount")
    private val utxoStorageAccount: String
) {

    /**
     * Checks if a given output has been used already
     * @param output - transaction output that must be checked
     * @return true if output has been used
     */
    fun isUsed(output: TransactionOutput): Result<Boolean, Exception> {
        return irohaQueryHelper.getAccountDetails(
            utxoStorageAccount, withdrawalConsumer.creator, output.irohaKey()
        ).map { value ->
            value.isPresent && !output.isRemovedFromIroha(value.get())
        }
    }

    /**
     * Unregisters UTXO
     * @param transaction - transaction which UTXOs will be unregistered
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     * */
    fun unregisterUsedUTXO(
        transaction: Transaction,
        withdrawalDetails: WithdrawalDetails
    ): Result<Unit, Exception> {
        return withdrawalConsumer.getConsumerQuorum().flatMap { quorum ->
            val transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
                .builder(withdrawalConsumer.creator)
                .setCreatedTime(withdrawalDetails.withdrawalTime)
                .setQuorum(quorum)
            transaction.inputs.forEach { input ->
                val (key, value) = input.removeFromIrohaKeyValue()
                transactionBuilder.setAccountDetail(utxoStorageAccount, key, value)
            }
            withdrawalConsumer.send(transactionBuilder.build())
        }.map {
            logger.info("The following transaction inputs have been unregistered\n$transaction")
            Unit
        }
    }

    companion object : KLogging()
}