/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.expansion

import com.d3.commons.expansion.ExpansionUtils
import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import iroha.protocol.BlockOuterClass
import jp.co.soramitsu.iroha.java.IrohaAPI
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class DepositServiceExpansion(
    private val irohaAPI: IrohaAPI,
    private val serviceExpansion: ServiceExpansion,
    @Qualifier("notaryCredential")
    private val notaryCredential: IrohaCredential
) {

    fun expand(block: BlockOuterClass.Block) {
        serviceExpansion.expand(block) { expansionsDetails, _, triggerTime ->
            ExpansionUtils.addSignatureExpansionLogic(
                irohaAPI,
                notaryCredential,
                expansionsDetails,
                triggerTime
            )
        }
    }
}
