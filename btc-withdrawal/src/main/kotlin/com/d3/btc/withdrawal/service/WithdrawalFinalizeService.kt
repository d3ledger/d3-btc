/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.Transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

private const val BTC_ASSET_ID = "btc#bitcoin"

/**
 * Service that is used to finalize withdrawals
 */
@Component
class WithdrawalFinalizeService(
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    @Qualifier("withdrawalConsumerMultiSig")
    private val withdrawalConsumer: MultiSigIrohaConsumer
) {

    /**
     * Finalize operation according to [withdrawalDetails]
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     */
    fun finalize(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        return withdrawalConsumer.getConsumerQuorum()
            .flatMap { quorum ->
                withdrawalConsumer.send(createFinalizeTransaction(withdrawalDetails, quorum))
            }.map { Unit }
    }

    /**
     * Creates transaction that finalizes given withdrawal operation
     * @param withdrawalDetails - details of withdrawal
     * @param quorum - quorum
     * @return transaction
     */
    private fun createFinalizeTransaction(withdrawalDetails: WithdrawalDetails, quorum: Int): Transaction {
        return Transaction
            .builder(withdrawalConsumer.creator)
            // Pay fees to the corresponding account
            .transferAsset(
                withdrawalConsumer.creator,
                btcWithdrawalConfig.withdrawalBillingAccount,
                BTC_ASSET_ID,
                "Fee",
                satToBtc(withdrawalDetails.withdrawalFeeSat).toPlainString()
            )
            // Burn withdrawal account money to keep 2WP consistent
            .subtractAssetQuantity(
                BTC_ASSET_ID, satToBtc(withdrawalDetails.amountSat)
            )
            .setCreatedTime(withdrawalDetails.withdrawalTime)
            .setQuorum(quorum)
            .build()
    }
}
