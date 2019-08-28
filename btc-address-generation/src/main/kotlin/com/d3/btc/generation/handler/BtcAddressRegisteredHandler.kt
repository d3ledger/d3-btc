/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.handler

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.trigger.AddressGenerationTrigger
import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.provider.account.BTC_CURRENCY_NAME_KEY
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Handler that handles 'BTC address registration' events
 */
@Component
class BtcAddressRegisteredHandler(
    private val addressGenerationTrigger: AddressGenerationTrigger,
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig
) : SetAccountDetailHandler() {

    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        logger.info("BTC address has been registered. Try to generate more addresses.")
        addressGenerationTrigger.startFreeAddressGenerationIfNeeded(
            btcAddressGenerationConfig.threshold,
            btcAddressGenerationConfig.nodeId
        ).fold(
            { "Free BTC address generation was triggered" },
            { ex -> logger.error("Cannot trigger address generation", ex) })
    }

    /**
     * Checks if BTC address was registered
     * @param setAccountDetailEvent - event to check
     * @return true if BTC address was registered in a given event
     */
    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.key == BTC_CURRENCY_NAME_KEY
                && setAccountDetailEvent.creator == btcAddressGenerationConfig.registrationAccount.accountId

    companion object : KLogging()
}
