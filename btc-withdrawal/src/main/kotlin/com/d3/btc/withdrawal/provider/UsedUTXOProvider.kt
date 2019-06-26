package com.d3.btc.withdrawal.provider

import com.d3.btc.helper.input.irohaKey
import com.d3.btc.helper.output.irohaKey
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import com.google.gson.Gson
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

    private val gson = Gson()

    /**
     * Checks if a given output has been used already
     * @param withdrawalDetails - details of withdrawal
     * @param output - transaction output that must be checked
     * @return true if output has been used
     */
    fun isUsed(withdrawalDetails: WithdrawalDetails, output: TransactionOutput): Result<Boolean, Exception> {
        return irohaQueryHelper.getAccountDetails(
            utxoStorageAccount, withdrawalConsumer.creator, output.irohaKey()
        ).map { value ->
            if (value.isPresent) {
                val utxoDetails = gson.fromJson(value.get(), UTXODetails::class.java)
                // UTXO is considered free to use if it's removed or it was created for the same withdrawal transaction
                !(utxoDetails.removed || utxoDetails.withdrawalTime == withdrawalDetails.withdrawalTime)
            } else {
                false
            }
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
                transactionBuilder.setAccountDetail(
                    utxoStorageAccount,
                    input.irohaKey(),
                    gson.toJson(UTXODetails.remove(withdrawalDetails.withdrawalTime)).irohaEscape()
                )
            }
            withdrawalConsumer.send(transactionBuilder.build())
        }.map {
            logger.info("The following transaction inputs have been unregistered\n$transaction")
            Unit
        }
    }

    companion object : KLogging()
}

data class UTXODetails(val withdrawalTime: Long, val removed: Boolean) {

    companion object {
        fun remove(withdrawalTime: Long) = UTXODetails(withdrawalTime, true)

        fun register(withdrawalTime: Long) = UTXODetails(withdrawalTime, false)
    }
}