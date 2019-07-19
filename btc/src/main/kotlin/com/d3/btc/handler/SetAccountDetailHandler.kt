package com.d3.btc.handler

import iroha.protocol.Commands

/**
 * SetAccountDetail command handler interface
 */
interface SetAccountDetailHandler {
    /**
     * Handling logic
     * @param command - command to handle
     */
    fun handle(command: Commands.SetAccountDetail)

    /**
     * Filter of commands. Only filtered commands will be handled
     * @param command - command to filter
     * @return true if command if suitable for handling
     */
    fun filter(command: Commands.SetAccountDetail): Boolean
}
