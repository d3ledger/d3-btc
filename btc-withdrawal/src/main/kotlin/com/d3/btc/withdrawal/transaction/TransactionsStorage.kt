/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.helper.transaction.shortTxHash
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.commons.model.D3ErrorException
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaEscape
import com.d3.commons.util.unHex
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

private val gson = GsonInstance.get()

/*
 * Class that is used to store transactions
 */
@Component
class TransactionsStorage(
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper,
    @Qualifier("reliableWithdrawalConsumer")
    private val btcWithdrawalConsumer: IrohaConsumer,
    @Qualifier("txStorageAccount")
    private val txStorageAccount: String
) {
    /**
     * Saves transactions
     * @param withdrawalConsensus - withdrawal consensus data
     * @param transaction - transaction that will be saved
     */
    fun save(
        withdrawalConsensus: WithdrawalConsensus,
        transaction: Transaction
    ): Result<Unit, Exception> {
        val withdrawalDetails = withdrawalConsensus.withdrawalDetails
        logger.info(
            "Save transaction in Iroha.\nDetails $withdrawalDetails\n" +
                    "Transaction $transaction\n" +
                    "Key ${transaction.shortTxHash()}"
        )
        return btcWithdrawalConsumer.getConsumerQuorum().flatMap { quorum ->
            val transactionBuilder = jp.co.soramitsu.iroha.java.Transaction
                .builder(btcWithdrawalConsumer.creator)
                .setAccountDetail(
                    txStorageAccount, transaction.shortTxHash(), WithdrawalTransaction(
                        withdrawalConsensus,
                        Utils.toHex(transaction.bitcoinSerialize())
                    ).toJson().irohaEscape()
                )
                .setCreatedTime(withdrawalDetails.withdrawalTime)
                .setQuorum(quorum)
            btcWithdrawalConsumer.send(transactionBuilder.build())
        }.map {
            Unit
        }
    }

    /**
     * Returns transaction by its hash
     * @param txHash - hash of transaction
     * @return transaction and its withdrawal consensus data
     */
    fun get(txHash: String): Result<Pair<WithdrawalConsensus, Transaction>, Exception> {
        logger.info("Read transaction in Iroha. Key ${shortTxHash(txHash)}")
        return withdrawalQueryHelper.getAccountDetails(
            txStorageAccount, btcWithdrawalConsumer.creator,
            shortTxHash(txHash)
        ).map { withdrawalTx ->
            if (!withdrawalTx.isPresent) {
                throw D3ErrorException.fatal(
                    failedOperation = WITHDRAWAL_OPERATION,
                    description = "Transaction with hash $txHash was not found"
                )
            }
            val withdrawalTransaction = WithdrawalTransaction.fromJson(withdrawalTx.get())
            val transaction =
                Transaction(btcNetworkConfigProvider.getConfig(), String.unHex(withdrawalTransaction.txHex))
            Pair(withdrawalTransaction.withdrawalConsensus, transaction)
        }
    }

    companion object : KLogging()
}

private data class WithdrawalTransaction(
    val withdrawalConsensus: WithdrawalConsensus,
    val txHex: String
) {
    fun toJson() = gson.toJson(this)!!

    companion object {
        fun fromJson(json: String) = gson.fromJson(json, WithdrawalTransaction::class.java)!!
    }
}
