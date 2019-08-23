/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("TestingEndpointsMain")

package com.d3.btc.endpoints

import com.d3.btc.config.BtcNodeRpcConfig
import com.d3.btc.endpoints.config.TestingEndpointConfig
import com.d3.btc.endpoints.endpoint.TestingEndpoint
import com.d3.commons.config.loadRawLocalConfigs
import com.github.jleskovar.btcrpc.BitcoinRpcClientFactory
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.IrohaAPI
import mu.KLogging
import kotlin.system.exitProcess

private val logger = KLogging().logger

private val endpointConfig =
    loadRawLocalConfigs("endpoints", TestingEndpointConfig::class.java, "testing_endpoints.properties")

private val btcNodeRpcConfig =
    loadRawLocalConfigs("btc-node-rpc", BtcNodeRpcConfig::class.java, "node-rpc.properties")

/**
 * Main entry point of Testing endpoints module
 */
fun main() {
    Result.of {
        BitcoinRpcClientFactory.createClient(
            user = btcNodeRpcConfig.user,
            password = btcNodeRpcConfig.password,
            host = btcNodeRpcConfig.host,
            port = btcNodeRpcConfig.port,
            secure = false
        )
    }.map { rpcClient ->
        TestingEndpoint(
            endpointConfig.port,
            rpcClient,
            IrohaAPI(endpointConfig.iroha.hostname, endpointConfig.iroha.port),
            endpointConfig.notaryIrohaAccount
        )
    }.failure { ex ->
        logger.error("Cannot run testing endpoints service", ex)
        exitProcess(1)
    }
}
