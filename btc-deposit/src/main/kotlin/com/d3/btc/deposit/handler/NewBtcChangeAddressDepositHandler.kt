package com.d3.btc.deposit.handler

import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.storage.BtcAddressStorage
import org.springframework.stereotype.Component

/**
 * Handler that handles new change addresses from the deposit service perspective
 */
@Component
class NewBtcChangeAddressDepositHandler(
    private val btcAddressStorage: BtcAddressStorage,
    private val btcDepositConfig: BtcDepositConfig
) : SetAccountDetailHandler() {

    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        val address = setAccountDetailEvent.command.key
        btcAddressStorage.addChangeAddress(address)
    }

    /**
     * Checks if event is a 'new change address' event
     */
    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.command.accountId == btcDepositConfig.changeAddressesStorageAccount
                && setAccountDetailEvent.creator == btcDepositConfig.mstRegistrationAccount
}
