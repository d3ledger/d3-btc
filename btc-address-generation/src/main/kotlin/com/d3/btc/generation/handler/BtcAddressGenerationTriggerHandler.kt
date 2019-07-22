/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.handler

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.provider.generation.BtcPublicKeyProvider
import com.d3.btc.wallet.safeSave
import com.github.kittinunf.result.Result
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Handler that handles 'BTC address generation trigger' events
 */
@Component
class BtcAddressGenerationTriggerHandler(
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig,
    @Qualifier("keysWallet")
    private val keysWallet: Wallet,
    private val btcPublicKeyProvider: BtcPublicKeyProvider
) : SetAccountDetailHandler() {

    override fun handle(command: Commands.SetAccountDetail) {
        //add new public key to session account, if trigger account was changed
        val sessionAccountName = command.key
        onGenerateKey(sessionAccountName).fold(
            { pubKey -> logger.info { "New public key $pubKey for BTC multisignature address was created" } },
            { ex ->
                logger.error(
                    "Cannot generate public key for BTC multisignature address",
                    ex
                )
            })
    }

    // Generates new key
    private fun onGenerateKey(sessionAccountName: String): Result<String, Exception> {
        return btcPublicKeyProvider.createKey(sessionAccountName) { keysWallet.safeSave(btcAddressGenerationConfig.btcKeysWalletPath) }
    }

    override fun filter(command: Commands.SetAccountDetail) =
        command.accountId == btcAddressGenerationConfig.pubKeyTriggerAccount

    companion object : KLogging()
}
