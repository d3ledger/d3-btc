/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.wallet

import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.storage.BtcAddressStorage
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import org.bitcoinj.wallet.Wallet

/**
 * Wallet initializer
 */
class WalletInitializer(
    private val btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
    private val btcChangeAddressProvider: BtcChangeAddressProvider
) {

    /**
     * Initializes given wallet
     * @param wallet - wallet to initialize
     */
    fun initializeWallet(wallet: Wallet) {
        // Get change addresses
        btcChangeAddressProvider.getAllChangeAddresses().fanout {
            // Get registered addresses
            btcRegisteredAddressesProvider.getRegisteredAddresses()
        }.map { (changeAddresses, registeredAddresses) ->
            // Add addresses to wallet
            wallet.addWatchedAddresses(changeAddresses)
            wallet.addWatchedAddresses(registeredAddresses)
        }.failure { ex -> throw ex }
    }
}
