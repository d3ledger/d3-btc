/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.provider.address

import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map

//TODO delete probably
//Class that provides all created BTC addresses
class BtcAddressesProvider(
    private val queryHelper: IrohaQueryHelper,
    private val mstRegistrationAccount: String,
    private val notaryAccount: String
) {
    /**
     * Get all created btc addresses
     * @return list full of created BTC addresses
     */
    fun getAddresses(): Result<List<BtcAddress>, Exception> {
        return queryHelper.getAccountDetails(
            notaryAccount,
            mstRegistrationAccount
        ).map { addresses ->
            addresses.map { entry ->
                BtcAddress(entry.key, AddressInfo.fromJson(entry.value)!!)
            }
        }
    }
}
