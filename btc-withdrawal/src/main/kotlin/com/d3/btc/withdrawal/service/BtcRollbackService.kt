/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.ROLLBACK_DESCRIPTION
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.github.kittinunf.result.flatMap
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import kotlin.math.min

/**
 * Bitcoin rollback service
 */
@Component
class BtcRollbackService(
    @Qualifier("withdrawalConsumerMultiSig")
    private val withdrawalConsumer: MultiSigIrohaConsumer
) {

    /**
     * Rollbacks given amount of money to a particular Iroha account
     * @param withdrawalDetails - details of withdrawal to rollback
     * @param reason - reason of rollback
     */
    fun rollback(withdrawalDetails: WithdrawalDetails, reason: String) {
        val amountToRollbackSat = withdrawalDetails.amountSat + withdrawalDetails.withdrawalFeeSat
        val rollbackMessage = "$ROLLBACK_DESCRIPTION. $reason"
        withdrawalConsumer.getConsumerQuorum().flatMap { quorum ->
            ModelUtil.transferAssetIroha(
                withdrawalConsumer,
                withdrawalConsumer.creator,
                withdrawalDetails.sourceAccountId,
                BTC_ASSET,
                rollbackMessage.substring(0, min(rollbackMessage.length, 64)).toLowerCase(),
                satToBtc(amountToRollbackSat).toPlainString(),
                withdrawalDetails.withdrawalTime,
                quorum
            )
        }.fold(
            {
                logger.info {
                    "Rollback(accountId:${withdrawalDetails.sourceAccountId}, amount:${satToBtc(amountToRollbackSat).toPlainString()}) was committed"
                }
            },
            { ex -> logger.error("Cannot perform rollback", ex) })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
