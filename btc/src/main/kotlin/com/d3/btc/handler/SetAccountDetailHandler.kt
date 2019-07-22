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
     * Handles commands if it is a command of our interest
     * @param command - command to handle
     */
    fun handleFiltered(command: Commands.SetAccountDetail) {
        if (filter(command)) {
            handle(command)
        }
    }

    /**
     * Handling logic
     * @param command - command to handle
     */
    protected abstract fun handle(command: Commands.SetAccountDetail)

    /**
     * Filter of commands. Only filtered commands will be handled
     * @param command - command to filter
     * @return true if command if suitable for handling
     */
    protected abstract fun filter(command: Commands.SetAccountDetail): Boolean
}
