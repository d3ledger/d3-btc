/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.service

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.service.FinalizationDetails
import com.d3.commons.service.WithdrawalFinalizer
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import org.springframework.stereotype.Component

/**
 * Service that is used to finalize withdrawals
 */
@Component
class BtcWithdrawalFinalizeService(private val withdrawalFinalizer: WithdrawalFinalizer) {

    /**
     * Finalize operation according to [withdrawalDetails]
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     */
    fun finalize(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        return withdrawalFinalizer.finalize(
            FinalizationDetails(
                satToBtc(withdrawalDetails.amountSat),
                BTC_ASSET,
                satToBtc(withdrawalDetails.withdrawalFeeSat),
                BTC_ASSET,
                withdrawalDetails.sourceAccountId,
                withdrawalDetails.withdrawalTime
            )
        ).map { Unit }
    }
}
