/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.registration.init

import com.d3.btc.registration.config.BtcRegistrationConfig
import com.d3.commons.registration.RegistrationServiceEndpoint
import com.d3.commons.registration.RegistrationStrategy
import com.github.kittinunf.result.Result
import mu.KLogging
import org.springframework.stereotype.Component

@Component
class BtcRegistrationServiceInitialization(
    private val btcRegistrationConfig: BtcRegistrationConfig,
    private val btcRegistrationStrategy: RegistrationStrategy
) {
    /**
     * Init Registration Service
     */
    fun init(): Result<Unit, Exception> {
        logger.info { "Init BTC client registration service" }
        return Result.of {
            RegistrationServiceEndpoint(
                btcRegistrationConfig.port,
                btcRegistrationStrategy
            )
            Unit
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

