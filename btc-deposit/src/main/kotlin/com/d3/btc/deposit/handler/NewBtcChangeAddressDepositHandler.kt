package com.d3.btc.deposit.handler

import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.storage.BtcAddressStorage
import iroha.protocol.Commands
import org.springframework.stereotype.Component

/**
 * Handler that handles new change addresses from the deposit service perspective
 */
@Component
class NewBtcChangeAddressDepositHandler(
    private val btcAddressStorage: BtcAddressStorage,
    private val btcDepositConfig: BtcDepositConfig
) : SetAccountDetailHandler() {

    override fun handle(command: Commands.SetAccountDetail) {
        val address = command.key
        btcAddressStorage.addChangeAddress(address)
    }

    /**
     * Checks if command is a 'new change address' event
     */
    override fun filter(command: Commands.SetAccountDetail) =
        command.accountId == btcDepositConfig.changeAddressesStorageAccount

}
