/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.handler

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_OPERATION_NAME
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddress
import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.commons.model.D3ErrorException
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaUnEscape
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import mu.KLogging
import org.springframework.stereotype.Component

private val gson = GsonInstance.get()

/**
 * Handler that handles 'Bitcoin MultiSig address registration' events
 */
@Component
class BtcMultiSigAddressGeneratedHandler(
    private val btcFreeAddressProvider: BtcFreeAddressesProvider,
    private val addressGenerationConfig: BtcAddressGenerationConfig
) : SetAccountDetailHandler() {

    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        // Get address
        val address = setAccountDetailEvent.command.key
        // Get address info(public keys, node id, etc)
        val addressInfo = gson.fromJson(setAccountDetailEvent.command.value.irohaUnEscape(), AddressInfo::class.java)
        var statusNeedsUpdate = false
        // Check if we can register address as free
        btcFreeAddressProvider.ableToRegisterAsFree(address).map { ableToRegisterAsFree ->
            if (!ableToRegisterAsFree) {
                logger.warn("Cannot register address $address as a free address. Address $address already exists.")
            } else {
                statusNeedsUpdate = true
                // Register address as free
                btcFreeAddressProvider.createFreeAddress(BtcAddress(address, addressInfo)).failure { ex ->
                    throw D3ErrorException.warning(
                        failedOperation = BTC_ADDRESS_GENERATION_OPERATION_NAME,
                        description = "Cannot mark recently generated $address as free",
                        errorCause = ex
                    )
                }
            }
        }.fold(
            {
                if (statusNeedsUpdate) {
                    logger.info("Address $address has been successfully registered as a free address")
                }
            },
            { ex -> logger.error("Cannot register address $address as a free address", ex) })
    }

    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.creator == addressGenerationConfig.mstRegistrationAccount.accountId &&
                setAccountDetailEvent.command.accountId == addressGenerationConfig.notaryAccount

    companion object : KLogging()
}
