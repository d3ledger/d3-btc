/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.MultiSigIrohaConsumer
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.RoundingMode

private const val BTC_ASSET_ID = "btc#bitcoin"

/**
 * Service that is used to pay fees
 */
@Component
class FeeService(
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    @Qualifier("withdrawalConsumerMultiSig")
    private val withdrawalConsumer: MultiSigIrohaConsumer
) {

    /**
     * Pays fee according to [withdrawalDetails]
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     */
    fun payFee(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        return withdrawalConsumer.getConsumerQuorum()
            .flatMap { quorum ->
                ModelUtil.transferAssetIroha(
                    withdrawalConsumer,
                    withdrawalConsumer.creator,
                    btcWithdrawalConfig.withdrawalFeeAccount,
                    BTC_ASSET_ID,
                    "Fee",
                    satToBtc(withdrawalDetails.withdrawalFeeSat).setScale(8, RoundingMode.DOWN).toPlainString(),
                    withdrawalDetails.withdrawalTime,
                    quorum
                )
            }.map { Unit }
    }
}
