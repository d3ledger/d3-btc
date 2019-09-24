/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.commons.util.GsonInstance
import com.d3.btc.helper.input.irohaKey
import com.d3.btc.helper.output.info
import com.d3.btc.helper.transaction.shortTxHash
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.btc.withdrawal.provider.UTXODetails
import com.d3.commons.model.D3ErrorException
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
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
    @Qualifier("withdrawalConsumer")
    private val btcWithdrawalConsumer: IrohaConsumer,
    @Qualifier("txStorageAccount")
    private val txStorageAccount: String,
    @Qualifier("utxoStorageAccount")
    private val utxoStorageAccount: String,
    @Qualifier("consensusIrohaCredential")
    private val consensusIrohaCredential: IrohaCredential
) {
    /**
     * Saves transactions
     * @param withdrawalDetails - details of withdrawal(account id, amount and time)
     * @param transaction - transaction that will be saved
     */
    fun save(
        withdrawalDetails: WithdrawalDetails,
        transaction: Transaction
    ): Result<Unit, Exception> {
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
                        withdrawalDetails,
                        Utils.toHex(transaction.bitcoinSerialize())
                    ).toJson().irohaEscape()
                )
                .setCreatedTime(withdrawalDetails.withdrawalTime)
                .setAccountDetail(consensusIrohaCredential.accountId, withdrawalDetails.irohaFriendlyHashCode(), "")
                .setQuorum(quorum)
            transaction.inputs.forEach { input ->
                transactionBuilder.setAccountDetail(
                    utxoStorageAccount,
                    input.irohaKey(),
                    gson.toJson(UTXODetails.register(withdrawalDetails.withdrawalTime)).irohaEscape()
                )
            }
            btcWithdrawalConsumer.send(transactionBuilder.build())
        }.map {
            logger.info("Registered UTXO items:\n${transaction.inputs.map { it.connectedOutput!!.info() }}")
            Unit
        }
    }

    /**
     * Returns transaction by its hash
     * @param txHash - hash of transaction
     * @return transaction
     */
    fun get(txHash: String): Result<Pair<WithdrawalDetails, Transaction>, Exception> {
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
            Pair(
                withdrawalTransaction.withdrawalDetails,
                Transaction(btcNetworkConfigProvider.getConfig(), String.unHex(withdrawalTransaction.txHex))
            )
        }
    }

    companion object : KLogging()
}

private data class WithdrawalTransaction(val withdrawalDetails: WithdrawalDetails, val txHex: String) {
    fun toJson() = gson.toJson(this)!!

    companion object {
        fun fromJson(json: String) = gson.fromJson(json, WithdrawalTransaction::class.java)!!
    }
}
