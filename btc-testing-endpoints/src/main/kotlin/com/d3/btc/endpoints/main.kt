/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("TestingEndpointsMain")

package com.d3.btc.endpoints

import com.d3.btc.cli.BtcNodeRpcConfig
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.config.loadRawLocalConfigs
import com.d3.commons.notary.endpoint.ServerInitializationBundle
import com.github.jleskovar.btcrpc.BitcoinRpcClientFactory
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.IrohaAPI
import mu.KLogging

private const val ENDPOINT_BITCOIN = "btc"

private val logger = KLogging().logger

private val endpointConfig =
    loadRawLocalConfigs("endpoints", TestingEndpointConfig::class.java, "testing_endpoints.properties")

/**
 * Main entry point of Testing endpoints deployment module
 */
fun main(args: Array<String>) {
    loadLocalConfigs("btc-node-rpc", BtcNodeRpcConfig::class.java, "node-rpc.properties")
        .map { btcNodeRpcConfig ->
            BitcoinRpcClientFactory.createClient(
                user = btcNodeRpcConfig.user,
                password = btcNodeRpcConfig.password,
                host = btcNodeRpcConfig.host,
                port = btcNodeRpcConfig.port,
                secure = false
            )
        }
        .fanout {
            Result.of {
                ServerInitializationBundle(
                    endpointConfig.port,
                    ENDPOINT_BITCOIN
                )
            }
        }
        .map { (deployHelper, bundle) ->
            TestingEndpoint(
                bundle,
                deployHelper,
                IrohaAPI(endpointConfig.iroha.hostname, endpointConfig.iroha.port),
                endpointConfig.notaryIrohaAccount
            )
        }
        .failure { ex ->
            logger.error("Cannot run testing endpoints service", ex)
            System.exit(1)
        }
}
