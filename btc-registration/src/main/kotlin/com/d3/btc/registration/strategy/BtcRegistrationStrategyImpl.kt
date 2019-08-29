/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.registration.strategy

import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.account.IrohaBtcAccountRegistrator
import com.d3.commons.registration.RegistrationStrategy
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

//Strategy for registering BTC addresses
@Component
class BtcRegistrationStrategyImpl(
    private val btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
    private val btcFreeAddressesProvider: BtcFreeAddressesProvider,
    private val irohaBtcAccountCreator: IrohaBtcAccountRegistrator
) : RegistrationStrategy {

    /**
     * Registers new Iroha client and associates BTC address to it
     * @param accountName - client name
     * @param domainId - client domain
     * @param publicKey - client public key
     * @return associated BTC address
     */
    @Synchronized
    override fun register(
        accountName: String,
        domainId: String,
        publicKey: String
    ): Result<String, Exception> {
        return btcRegisteredAddressesProvider.ableToRegister("$accountName@$domainId")
            .map { ableToRegister ->
                if (!ableToRegister) {
                    throw IllegalStateException("Not able to register $accountName@$domainId")
                }
            }
            .flatMap { btcFreeAddressesProvider.getFreeAddresses() }
            .flatMap { freeAddresses ->
                if (freeAddresses.isEmpty()) {
                    throw IllegalStateException("No free btc address to register")
                }
                // Get the newest address among free addresses
                val freeAddress =
                    freeAddresses.maxBy { address -> address.info.generationTime ?: 0 }

                irohaBtcAccountCreator.create(
                    freeAddress!!.address,
                    accountName,
                    domainId,
                    freeAddress.info.notaryKeys,
                    btcFreeAddressesProvider.nodeId
                )
            }
    }

    /**
     * Get number of free addresses.
     */
    override fun getFreeAddressNumber(): Result<Int, Exception> {
        return btcFreeAddressesProvider.getFreeAddresses().map { freeAddresses ->
            freeAddresses.size
        }
    }

}
