/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.service.RollbackService
import com.d3.commons.service.WithdrawalFinalizationDetails
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Bitcoin rollback service
 */
@Component
class BtcRollbackService(
    @Qualifier("withdrawalConsumerMultiSig")
    private val withdrawalConsumer: MultiSigIrohaConsumer
) {

    private val rollbackService = RollbackService(withdrawalConsumer)

    /**
     * Rollbacks given amount of money to a particular Iroha account
     * @param withdrawalDetails - details of withdrawal to rollback
     * @param reason - reason of rollback
     */
    fun rollback(withdrawalDetails: WithdrawalDetails, reason: String) {
        val withdrawalFinalizationDetails = WithdrawalFinalizationDetails(
            satToBtc(withdrawalDetails.amountSat),
            BTC_ASSET,
            satToBtc(withdrawalDetails.withdrawalFeeSat),
            BTC_ASSET,
            withdrawalDetails.sourceAccountId,
            withdrawalDetails.withdrawalTime,
            withdrawalDetails.toAddress
        )

        rollbackService.rollback(withdrawalFinalizationDetails, reason).fold(
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
