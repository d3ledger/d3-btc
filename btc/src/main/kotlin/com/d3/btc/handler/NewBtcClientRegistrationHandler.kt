/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.handler

import com.d3.btc.provider.account.BTC_CURRENCY_NAME_KEY
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.storage.BtcAddressStorage
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Class that is used to handle client registration commands
 */
class NewBtcClientRegistrationHandler(
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val wallet: Wallet,
    private val btcAddressStorage: BtcAddressStorage,
    private val btcRegistrationServiceAccount: String
) : SetAccountDetailHandler() {

    /**
     * Handles newly registered Bitcoin addresses and adds addresses to current wallet object
     */
    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val address = Address.fromBase58(
            btcNetworkConfigProvider.getConfig(),
            setAccountDetailEvent.command.value
        )
        //Add new registered address to wallet
        if (wallet.addWatchedAddress(address)) {
            logger.info { "New BTC address ${setAccountDetailEvent.command.value} was added to wallet" }
        } else {
            logger.error { "Address $address was not added to wallet" }
        }
        btcAddressStorage.addClientAddress(
            address = address.toBase58(),
            accountId = setAccountDetailEvent.command.accountId
        )
    }

    /**
     * Checks if new btc client was registered
     */
    override fun filter(setAccountDetailEvent: SetAccountDetailEvent): Boolean {
        return setAccountDetailEvent.command.key == BTC_CURRENCY_NAME_KEY
                && setAccountDetailEvent.creator == btcRegistrationServiceAccount
    }

    /**
     * Logger
     */
    companion object : KLogging()

}
