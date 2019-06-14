/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.expansion

import com.d3.commons.expansion.ServiceExpansion
import com.d3.commons.model.IrohaCredential
import iroha.protocol.BlockOuterClass
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class AddressGenerationServiceExpansion(
    private val serviceExpansion: ServiceExpansion,
    @Qualifier("mstRegistrationCredential")
    private val mstRegistrationCredential: IrohaCredential
) {

    fun expand(block: BlockOuterClass.Block) {
        serviceExpansion.expand(block, listOf(mstRegistrationCredential))
    }
}
