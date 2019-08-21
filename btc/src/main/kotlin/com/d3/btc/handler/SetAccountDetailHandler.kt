/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.d3.btc.handler

import iroha.protocol.Commands

/**
 * SetAccountDetail command handler abstract class
 */
abstract class SetAccountDetailHandler {

    /**
     * Handles events if it is a command of our interest
     * @param setAccountDetailEvent - event to handle
     */
    fun handleFiltered(setAccountDetailEvent: SetAccountDetailEvent) {
        if (filter(setAccountDetailEvent)) {
            handle(setAccountDetailEvent)
        }
    }

    /**
     * Handling logic
     * @param setAccountDetailEvent - event to handle
     */
    protected abstract fun handle(setAccountDetailEvent: SetAccountDetailEvent)

    /**
     * Filter of commands. Only filtered commands will be handled
     * @param setAccountDetailEvent - event to filter
     * @return true if command if suitable for handling
     */
    protected abstract fun filter(setAccountDetailEvent: SetAccountDetailEvent): Boolean
}

/**
 * Data class that represents 'SetAccountDetail' command alongside with its creator
 */
data class SetAccountDetailEvent(val command: Commands.SetAccountDetail, val creator: String)