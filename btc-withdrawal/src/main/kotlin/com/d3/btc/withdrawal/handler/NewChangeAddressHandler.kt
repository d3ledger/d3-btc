/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import iroha.protocol.Commands
import org.bitcoinj.core.Address
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Handles new change addresses
 */
@Component
class NewChangeAddressHandler(
    @Qualifier("transferWallet")
    private val transfersWallet: Wallet,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val btcWithdrawalConfig: BtcWithdrawalConfig
) : SetAccountDetailHandler() {

    /**
     * Handles change address creation event
     * @param command - Iroha command with change address details
     */
    override fun handle(command: Commands.SetAccountDetail) {
        //Make new change address watched
        transfersWallet.addWatchedAddress(
            Address.fromBase58(
                btcNetworkConfigProvider.getConfig(),
                command.key
            )
        )
    }

    override fun filter(command: Commands.SetAccountDetail) =
        command.accountId == btcWithdrawalConfig.changeAddressesStorageAccount

}
