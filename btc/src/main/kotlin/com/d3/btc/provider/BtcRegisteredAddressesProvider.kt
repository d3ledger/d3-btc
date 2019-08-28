/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.provider

import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.btc.provider.account.BTC_CURRENCY_NAME_KEY
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map

//Class that provides all registered BTC addresses
open class BtcRegisteredAddressesProvider(
    private val queryHelper: IrohaQueryHelper,
    private val registrationAccount: String,
    private val notaryAccount: String
) {
    /**
     * Checks if given account may be registered in Bitcoin
     * @param accountId - id of account to check
     * @return true if able to register
     */
    fun ableToRegister(accountId: String) = isClient(accountId).map { !it }

    /**
     * Checks if given account is our client
     * @param accountId - id of account to check
     * @return true if our client
     */
    fun isClient(accountId: String) =
        queryHelper.getAccountDetails(accountId, registrationAccount, BTC_CURRENCY_NAME_KEY)
            .map { value ->
                value.isPresent
            }

    /**
     * Get all registered btc addresses
     * @return list full of registered BTC addresses
     */
    fun getRegisteredAddresses(): Result<List<BtcAddress>, Exception> {
        return queryHelper.getAccountDetails(
            notaryAccount,
            registrationAccount
        ).map { addresses ->
            addresses.map { entry ->
                BtcAddress(entry.key, AddressInfo.fromJson(entry.value)!!)
            }
        }
    }
}
