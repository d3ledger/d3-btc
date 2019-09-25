/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.helper.input.irohaKey
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.service.RollbackService
import com.d3.commons.service.WithdrawalFinalizationDetails
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaEscape
import com.d3.reverse.client.ReliableIrohaConsumerImpl
import com.github.kittinunf.result.flatMap
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

const val ROLLBACK_KEY = "withdrawal_rollback"

/**
 * Bitcoin rollback service
 */
@Component
class BtcRollbackService(
    @Qualifier("reliableWithdrawalConsumer")
    private val withdrawalConsumer: ReliableIrohaConsumerImpl
) {

    private val rollbackService = RollbackService(withdrawalConsumer)
    private val gson = GsonInstance.get()

    /**
     * Rollbacks given amount of money to a particular Iroha account
     * @param withdrawalDetails - details of withdrawal to rollback
     * @param reason - reason of rollback
     * @param btcTx - Bitcoin transaction to rollback. Used to unregister UTXO. The argument is optional.
     */
    fun rollback(withdrawalDetails: WithdrawalDetails, reason: String, btcTx: Transaction? = null) {
        val withdrawalFinalizationDetails = WithdrawalFinalizationDetails(
            satToBtc(withdrawalDetails.amountSat),
            BTC_ASSET,
            satToBtc(withdrawalDetails.withdrawalFeeSat),
            BTC_ASSET,
            withdrawalDetails.sourceAccountId,
            withdrawalDetails.withdrawalTime,
            withdrawalDetails.toAddress
        )
        withdrawalConsumer.getConsumerQuorum().flatMap { quorum ->
            var transaction = rollbackService.createRollbackTransaction(withdrawalFinalizationDetails, reason, quorum)
            if (btcTx != null) {
                logger.info("Rollback Bitcoin transaction as well $btcTx")
                val withdrawalRollbackData = WithdrawalRollbackData(
                    withdrawalDetails,
                    btcTx.inputs.map { input -> input.irohaKey() })
                transaction = transaction.makeMutable()
                    .setAccountDetail(
                        withdrawalConsumer.creator,
                        ROLLBACK_KEY,
                        gson.toJson(withdrawalRollbackData).irohaEscape()
                    ).build()
            }
            withdrawalConsumer.send(transaction)
        }.fold(
            {
                logger.info {
                    "Rollback withdrawal(accountId:${withdrawalDetails.sourceAccountId}, amount:${satToBtc(
                        withdrawalDetails.amountSat
                    ).toPlainString()}) fee(accountId:${withdrawalDetails.sourceAccountId}, amount:${satToBtc(
                        withdrawalDetails.withdrawalFeeSat
                    ).toPlainString()}) was committed"
                }
            },
            { ex -> logger.error("Cannot perform rollback", ex) })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

/**
 * Data class that holds information about rollback
 */
data class WithdrawalRollbackData(val withdrawalDetails: WithdrawalDetails, val utxoKeys: List<String>)
