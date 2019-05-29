/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.config

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
        fun extractHosts(bitcoinConfig: BitcoinConfig) = bitcoinConfig.hosts.replace(" ", "").split(",")
    }
}
