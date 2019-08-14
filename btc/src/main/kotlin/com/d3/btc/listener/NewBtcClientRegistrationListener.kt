/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.listener

import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Class that is used to listen to new client registration events
 */
@Component
class NewBtcClientRegistrationListener(
    private val newBtcClientRegistrationHandler: NewBtcClientRegistrationHandler
) {
    /**
     * Listens to newly registered Bitcoin addresses and adds addresses to current wallet object
     */
    fun listenToRegisteredClients(
        irohaChainListener: ReliableIrohaChainListener
    ) {
        irohaChainListener.getBlockObservable().fold({ observable ->
            observable.subscribe { (block, _) ->
                getSetDetailCommands(block).map { command -> command.setAccountDetail }
                    .forEach { setAccountDetailCommand ->
                        newBtcClientRegistrationHandler.handleFiltered(setAccountDetailCommand)
                    }
            }
        }, { ex -> throw ex })
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
