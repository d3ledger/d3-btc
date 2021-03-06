/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("BtcDepositWithdrawalMain")

package com.d3.btc.dwbridge

import com.d3.btc.deposit.init.BtcNotaryInitialization
import com.d3.btc.dwbridge.config.dwBridgeConfig
import com.d3.btc.withdrawal.init.BtcWithdrawalInitialization
import com.d3.commons.config.getProfile
import com.d3.commons.util.createFolderIfDoesntExist
import com.d3.reverse.adapter.ReverseChainAdapter
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ComponentScan
import kotlin.system.exitProcess

const val BTC_DW_BRIDGE_SERVICE_NAME = "btc-dw-bridge"

@ComponentScan(
    basePackages = [
        "com.d3.btc.wallet",
        "com.d3.btc.provider.address",
        "com.d3.btc.provider.network",
        "com.d3.btc.withdrawal.handler",
        "com.d3.btc.withdrawal.service",
        "com.d3.btc.withdrawal.init",
        "com.d3.btc.withdrawal.provider",
        "com.d3.btc.withdrawal.transaction",
        "com.d3.btc.withdrawal.expansion",
        "com.d3.btc.listener",
        "com.d3.btc.deposit.init",
        "com.d3.btc.deposit.service",
        "com.d3.btc.deposit.expansion",
        "com.d3.btc.deposit.handler",
        "com.d3.btc.peer",
        "com.d3.btc.dwbridge",
        "com.d3.btc.healthcheck"]
)
class BtcDWBridgeApplication

private val logger = KLogging().logger

/**
 * Function that starts deposit and withdrawal services concurrently
 */
fun main() {
    Result.of {
        // Create block storage folder
        createFolderIfDoesntExist(dwBridgeConfig.bitcoin.blockStoragePath)
    }.map {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles(getProfile())
        context.register(BtcDWBridgeApplication::class.java)
        context.refresh()
        context
    }.map { context ->
        // Run withdrawal service
        GlobalScope.launch {
            context.getBean(BtcWithdrawalInitialization::class.java).init()
                .failure { ex ->
                    logger.error("Error in withdrawal service", ex)
                    exitProcess(1)
                }
        }

        // Run deposit service
        GlobalScope.launch {
            context.getBean(BtcNotaryInitialization::class.java).init().failure { ex ->
                logger.error("Error in deposit service", ex)
                exitProcess(1)
            }
        }

        // Run reverse chain adapter
        GlobalScope.launch {
            context.getBean(ReverseChainAdapter::class.java).init()
                .failure { ex ->
                    logger.error("Error in reverse chain adapter service", ex)
                    exitProcess(1)
                }
        }
    }.failure { ex ->
        logger.error("Cannot run btc deposit/withdrawal bridge", ex)
        exitProcess(1)
    }
}
