/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.config

const val BTC_ASSET = "btc#bitcoin"
const val BTC_SIGN_COLLECT_DOMAIN = "btcSignCollect"
const val BTC_CONSENSUS_DOMAIN = "btcConsensus"

/**
 * Bitcoin configurations
 */
interface BitcoinConfig {
    //Path of block storage folder
    val blockStoragePath: String
    //Depth of transactions in BTC blockchain
    val confidenceLevel: Int
    //BTC node hosts
    val hosts: String

    companion object {
        fun extractHosts(bitcoinConfig: BitcoinConfig) = extractCommaSeparatedList(bitcoinConfig.hosts)
    }
}

/**
 * Turns comma separated text into list of items
 * @param text - text to transform
 * @return list of items
 */
fun extractCommaSeparatedList(text: String?): List<String> {
    return if (text.isNullOrEmpty()) {
        emptyList()
    } else {
        text.replace(" ", "").split(",")
            .filter { it.isNotEmpty() }
    }
}
