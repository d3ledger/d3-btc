/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.handler

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.model.BtcAddressType
import com.d3.btc.model.getAddressTypeByAccountId
import com.d3.btc.provider.generation.ADDRESS_GENERATION_NODE_ID_KEY
import com.d3.btc.provider.generation.ADDRESS_GENERATION_TIME_KEY
import com.d3.btc.provider.generation.BtcPublicKeyProvider
import com.d3.btc.wallet.safeSave
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Handler that handles 'new key' events
 */
@Component
class NewKeyHandler(
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig,
    @Qualifier("keysWallet")
    private val keysWallet: Wallet,
    @Qualifier("registrationQueryHelper")
    private val registrationQueryHelper: IrohaQueryHelper,
    private val btcPublicKeyProvider: BtcPublicKeyProvider
) : SetAccountDetailHandler() {

    override fun handle(command: Commands.SetAccountDetail) {
        val accountId = command.accountId
        //create multisignature address, if we have enough keys in session account
        onGenerateMultiSigAddress(
            accountId,
            getAddressTypeByAccountId(accountId)
        ).failure { ex ->
            logger.error(
                "Cannot generate multi signature address", ex
            )
        }
    }

    /**
     * Generates multisig address
     * @param sessionAccount - account that holds public keys that are used in multisig address generation
     * @param addressType - type of address to generate
     */
    private fun onGenerateMultiSigAddress(
        sessionAccount: String,
        addressType: BtcAddressType
    ): Result<Unit, Exception> {
        return registrationQueryHelper.getAccountDetails(
            sessionAccount,
            btcAddressGenerationConfig.registrationAccount.accountId
        ).map { it.toMutableMap() }
            .flatMap { details ->
                // Getting time
                val time = details.remove(ADDRESS_GENERATION_TIME_KEY)!!.toLong()
                // Getting node id
                val nodeId = details.remove(ADDRESS_GENERATION_NODE_ID_KEY)!!
                // Getting keys
                val notaryKeys = details.values
                if (!notaryKeys.isEmpty()) {
                    btcPublicKeyProvider.checkAndCreateMultiSigAddress(
                        notaryKeys.toList(),
                        addressType,
                        time,
                        nodeId
                    ) { keysWallet.safeSave(btcAddressGenerationConfig.btcKeysWalletPath) }
                } else {
                    Result.of { Unit }
                }
            }
    }

    /**
     * Checks if new key was added
     * @param command - command to check
     * @return true if given [command] is a 'new key' event command
     */
    override fun filter(command: Commands.SetAccountDetail) = command.accountId.endsWith("btcSession")
            && command.key != ADDRESS_GENERATION_TIME_KEY
            && command.key != ADDRESS_GENERATION_NODE_ID_KEY


    companion object : KLogging()
}
