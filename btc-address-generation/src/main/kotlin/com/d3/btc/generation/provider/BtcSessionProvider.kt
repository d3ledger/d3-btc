/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.provider

import com.d3.btc.helper.transaction.DUMMY_PUB_KEY
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import jp.co.soramitsu.iroha.java.Transaction
import jp.co.soramitsu.iroha.java.Utils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

const val BTC_SESSION_DOMAIN = "btcSession"
const val ADDRESS_GENERATION_TIME_KEY = "addressGenerationTime"
const val ADDRESS_GENERATION_NODE_ID_KEY = "nodeId"

// Class for creating session accounts. These accounts are used to store BTC public keys.
@Component
class BtcSessionProvider(
    @Qualifier("registrationConsumer")
    private val registrationConsumer: IrohaConsumer
) {

    /**
     * Creates a special session account for notaries public key storage
     *
     * @param sessionId session identifier aka session account name
     * @param nodeId - node id
     * @return Result of session creation process
     */
    fun createPubKeyCreationSession(sessionId: String, nodeId: String) =
        registrationConsumer.send(createPubKeyCreationSessionTx(sessionId, nodeId))

    /**
     * Creates a transaction that may be used to create special session account for notaries public key storage
     *
     * @param sessionId - session identifier aka session account name
     * @param nodeId - node id
     * @return transaction full of session creation commands
     */
    private fun createPubKeyCreationSessionTx(sessionId: String, nodeId: String): Transaction {
        return Transaction.builder(registrationConsumer.creator)
            .createAccount(
                sessionId,
                BTC_SESSION_DOMAIN,
                DUMMY_PUB_KEY
            ).setAccountDetail(
                "$sessionId@$BTC_SESSION_DOMAIN",
                ADDRESS_GENERATION_TIME_KEY,
                System.currentTimeMillis().toString()
            ).setAccountDetail(
                "$sessionId@$BTC_SESSION_DOMAIN",
                ADDRESS_GENERATION_NODE_ID_KEY,
                nodeId
            ).build()
    }
}
