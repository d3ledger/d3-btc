/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.handler

import com.d3.btc.handler.SetAccountDetailEvent
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.withdrawal.provider.UsedUTXOProvider
import com.d3.btc.withdrawal.service.ROLLBACK_KEY
import com.d3.btc.withdrawal.service.WithdrawalRollbackData
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaUnEscape
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Handler that handles 'rollback' events
 */
@Component
class RollbackHandler(
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper,
    private val usedUTXOProvider: UsedUTXOProvider
) : SetAccountDetailHandler() {

    private val gson = GsonInstance.get()

    /**
     * Handles 'rollback' event
     * @param setAccountDetailEvent - rollback event
     */
    override fun handle(setAccountDetailEvent: SetAccountDetailEvent) {
        // Get rollback data
        val withdrawalRollbackData =
            gson.fromJson(setAccountDetailEvent.command.value.irohaUnEscape(), WithdrawalRollbackData::class.java)
        // Unregister UTXO
        usedUTXOProvider.unregisterUsedUTXO(withdrawalRollbackData.utxoKeys, withdrawalRollbackData.withdrawalDetails)
    }

    override fun filter(setAccountDetailEvent: SetAccountDetailEvent) =
        setAccountDetailEvent.creator == withdrawalQueryHelper.getQueryCreatorAccountId()
                && setAccountDetailEvent.command.accountId == withdrawalQueryHelper.getQueryCreatorAccountId()
                && setAccountDetailEvent.command.key == ROLLBACK_KEY

    companion object : KLogging()
}
