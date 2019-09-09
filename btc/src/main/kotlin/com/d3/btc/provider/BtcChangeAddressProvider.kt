/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.provider

import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import java.util.*

/*
    Class that is used to get change address
 */
open class BtcChangeAddressProvider(
    private val queryHelper: IrohaQueryHelper,
    private val mstRegistrationAccount: String,
    private val changeAddressesStorageAccount: String
) {

    /**
     * Returns all change addresses
     */
    fun getAllChangeAddresses(): Result<List<BtcAddress>, Exception> {
        return queryHelper.getAccountDetails(
            changeAddressesStorageAccount,
            mstRegistrationAccount
        ).map { details ->
            // Map details into BtcAddress collection
            details.entries.map { entry ->
                BtcAddress(entry.key, AddressInfo.fromJson(entry.value)!!)
            }
        }
    }

    /**
     * Returns given address info
     * @param btcAddress - Bitcoin address
     * @return address info or null if there is no such an address
     */
    fun getAddressInfo(btcAddress: String): Result<Optional<AddressInfo>, Exception> {
        return queryHelper.getAccountDetailsFirst(
            changeAddressesStorageAccount,
            mstRegistrationAccount
        ) { key, _ -> key == btcAddress }.map { detail ->
            if (detail.isPresent) {
                val addressInfoJson = detail.get().value
                Optional.of(AddressInfo.fromJson(addressInfoJson))
            } else {
                Optional.empty()
            }
        }
    }

    /**
     * Returns all change addresses that were generated before given time
     * @param generatedBefore - only addresses that were generated before this value will be returned
     */
    fun getAllChangeAddresses(generatedBefore: Long): Result<List<BtcAddress>, Exception> {
        return getAllChangeAddresses().map { changeAddresses ->
            changeAddresses
                .filter { changeAddress -> changeAddress.info.generationTime!! <= generatedBefore }
        }
    }
}
