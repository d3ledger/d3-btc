/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.storage

import com.d3.btc.model.BtcAddress
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import mu.KLogging

/**
 * In-memory BTC address storage.
 * Created to reduce Iroha querying.
 */
class BtcAddressStorage(
    btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
    btcChangeAddressProvider: BtcChangeAddressProvider
) {

    private val clientAddressesStorage = HashMap<String, String>()

    private val changeAddressesStorage = HashSet<String>()

    /*
      It's crucial to init this storage on start
     */
    init {
        // Get change addresses
        btcChangeAddressProvider.getAllChangeAddresses().fanout {
            // Get registered addresses
            btcRegisteredAddressesProvider.getRegisteredAddresses()
        }.map { (changeAddresses, registeredAddresses) ->
            addChangeAddresses(changeAddresses)
            addClientAddresses(registeredAddresses)
        }.fold({
            logger.info("BTC address storage has been initialized")
        }, { ex -> throw ex })
    }

    /**
     * Adds client address
     * @param address - BTC address of a client
     * @param accountId - account id of a client
     */
    @Synchronized
    fun addClientAddress(address: String, accountId: String) {
        clientAddressesStorage[address] = accountId
        logger.info("Address $address has been added to the client address storage")
    }

    /**
     * Adds client address
     * @param address - BtcAddress object with all information about an address(address itself, account id, etc)
     */
    @Synchronized
    fun addClientAddress(address: BtcAddress) {
        addClientAddress(address.address, address.info.irohaClient!!)
    }

    /**
     * Adds multiple client addresses in storage
     * @param addresses - addresses to add
     */
    @Synchronized
    fun addClientAddresses(addresses: Iterable<BtcAddress>) {
        addresses.forEach { address ->
            addClientAddress(address)
        }
    }

    /**
     * Adds change address to storage
     * @param address - change address to add
     */
    @Synchronized
    fun addChangeAddress(address: String) {
        changeAddressesStorage.add(address)
        logger.info("Address $address has been added to the change address storage")
    }

    /**
     * Adds multiple change addresses to storage
     * @param addresses - addresses to add
     */
    @Synchronized
    fun addChangeAddresses(addresses: Iterable<BtcAddress>) {
        addresses.forEach { address ->
            addChangeAddress(address.address)
        }
    }

    /**
     * Checks if address is a change address
     * @param address - address to check
     * @return true if address is a change address
     */
    @Synchronized
    fun isChangeAddress(address: String) = changeAddressesStorage.contains(address)


    /**
     * Checks if address is a client address
     * @param address - address to check
     * @return true if address is a client address
     */
    @Synchronized
    fun isOurClient(address: String) = clientAddressesStorage.contains(address)

    /**
     * Returns account id related to address
     * @param address - address that is related to some client
     * @return account id or null if there is no such address among client addresses
     */
    @Synchronized
    fun getClientAccountId(address: String) = clientAddressesStorage[address]

    companion object : KLogging()

}
