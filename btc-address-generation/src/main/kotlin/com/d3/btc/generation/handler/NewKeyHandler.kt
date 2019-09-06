/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.handler

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.provider.ADDRESS_GENERATION_NODE_ID_KEY
import com.d3.btc.generation.provider.ADDRESS_GENERATION_TIME_KEY
import com.d3.btc.generation.provider.BTC_SESSION_DOMAIN
import com.d3.btc.generation.provider.BtcPublicKeyProvider
import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.model.BtcAddressType
import com.d3.btc.model.getAddressTypeByAccountId
import com.d3.btc.wallet.safeSave
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
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

    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val accountId = setAccountDetailEvent.command.accountId
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
     * @param setAccountDetailEvent - event to check
     * @return true if given event is a 'new key' event
     */
    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.accountId.endsWith("@$BTC_SESSION_DOMAIN")
                && setAccountDetailEvent.command.key != ADDRESS_GENERATION_TIME_KEY
                && setAccountDetailEvent.command.key != ADDRESS_GENERATION_NODE_ID_KEY
                && setAccountDetailEvent.creator == btcAddressGenerationConfig.registrationAccount.accountId


    companion object : KLogging()
}
