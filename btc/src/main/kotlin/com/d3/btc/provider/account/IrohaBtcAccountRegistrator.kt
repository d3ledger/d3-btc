/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.provider.account

import com.d3.btc.model.AddressInfo
import com.d3.commons.registration.SideChainRegistrator
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.Transaction

const val BTC_CURRENCY_NAME_KEY = "bitcoin"

/*
    Class that is used to create Bitcoin accounts in Iroha
 */
class IrohaBtcAccountRegistrator(
    private val registrationIrohaConsumer: IrohaConsumer,
    notaryIrohaAccount: String
) {
    private val irohaAccountRegistrator =
        SideChainRegistrator(registrationIrohaConsumer, notaryIrohaAccount, BTC_CURRENCY_NAME_KEY)

    /**
     * Creates new Bitcoin account to Iroha with given address
     * @param btcAddress - Bitcoin address
     * @param userName - client userName in Iroha
     * @param domain - client domain
     * @param notaryKeys - keys that were used to create given address
     * @param nodeId - node id
     * @param transactionMutator - function that mutates(adds new commands) original registration transaction
     * @return address associated with userName
     */
    fun create(
        btcAddress: String,
        userName: String,
        domain: String,
        notaryKeys: List<String>,
        nodeId: String,
        transactionMutator: (Transaction) -> Transaction
    ): Result<String, Exception> {
        return irohaAccountRegistrator.buildTx(
            btcAddress,
            "$userName@$domain"
        ) {
            AddressInfo(
                "$userName@$domain",
                notaryKeys,
                nodeId,
                null
            ).toJson().irohaEscape()
        }.map { transaction ->
            transactionMutator(transaction)
        }.flatMap {
            registrationIrohaConsumer.send(it)
        }.map { btcAddress }
    }
}
