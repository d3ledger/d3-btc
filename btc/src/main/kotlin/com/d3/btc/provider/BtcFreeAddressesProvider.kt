/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.provider

import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.Transaction

// Class that used to fetch free addresses(addresses that might be registered by clients)
class BtcFreeAddressesProvider(
    private val nodeId: String,
    private val freeAddressesStorageAccount: String,
    private val registrationQueryHelper: IrohaQueryHelper,
    private val registrationIrohaConsumer: IrohaConsumer
) {

    // Predicate for free Bitcoin addresses
    private val freeAddressPredicate =
        { _: String, value: String ->
            value.isNotEmpty() && AddressInfo.fromJson(value).nodeId == nodeId
        }

    /**
     * Counts free Bitcoin addresses
     * @return number of free Bitcoin addresses
     */
    fun countFreeAddresses() = registrationQueryHelper.getAccountDetailsCount(
        freeAddressesStorageAccount,
        registrationQueryHelper.getQueryCreatorAccountId(), freeAddressPredicate
    )

    /**
     * Creates free address
     * @param btcAddress - address to create
     * @return result of operation
     */
    fun createFreeAddress(btcAddress: BtcAddress) = ModelUtil.setAccountDetail(
        irohaConsumer = registrationIrohaConsumer,
        accountId = freeAddressesStorageAccount,
        key = btcAddress.address,
        value = btcAddress.info.toJson().irohaEscape()
    ).map { Unit }

    /**
     * Checks if it's able to register given address
     * @param address - address to check
     * @return true if able to register
     */
    fun ableToRegister(address: String) =
        registrationQueryHelper.getAccountDetails(
            storageAccountId = freeAddressesStorageAccount,
            writerAccountId = registrationQueryHelper.getQueryCreatorAccountId(),
            key = address
        ).map { details -> details.isPresent && details.get().isNotEmpty() }

    /**
     * Checks if it's able to register given address as free
     * @param address - address to check
     * @return true if able to register as free
     *
     */
    fun ableToRegisterAsFree(address: String) =
        registrationQueryHelper.getAccountDetails(
            storageAccountId = freeAddressesStorageAccount,
            writerAccountId = registrationQueryHelper.getQueryCreatorAccountId(),
            key = address
        ).map { details -> !details.isPresent }

    /**
     * Returns free address
     * @return free address
     */
    fun getFreeAddress(): Result<BtcAddress, Exception> {
        return registrationQueryHelper.getAccountDetailsFirst(
            freeAddressesStorageAccount,
            registrationQueryHelper.getQueryCreatorAccountId(), freeAddressPredicate
        ).map { freeAddressDetail ->
            if (!freeAddressDetail.isPresent) {
                throw IllegalStateException("No free address to take")
            }
            val (address, addressInfoJson) = freeAddressDetail.get()
            BtcAddress(address, AddressInfo.fromJson(addressInfoJson))
        }
    }

    /**
     * Adds 'free address registration' commands to a registration tx
     * @param tx - transaction to enrich with commands
     * @param freeAddress - free address
     * @return new transaction
     */
    fun addRegisterFreeAddressCommands(tx: Transaction, freeAddress: BtcAddress) =
        tx.makeMutable()
            .setAccountDetail(freeAddressesStorageAccount, freeAddress.address, "")
            .build()!!
}
