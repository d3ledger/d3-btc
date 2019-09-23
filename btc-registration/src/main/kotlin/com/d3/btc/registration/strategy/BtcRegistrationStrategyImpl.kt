/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.registration.strategy

import com.d3.btc.provider.BtcFreeAddressesProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.provider.account.IrohaBtcAccountRegistrator
import com.d3.btc.registration.BTC_REGISTRATION_OPERATION_NAME
import com.d3.commons.model.D3ErrorException
import com.d3.commons.registration.RegistrationStrategy
import com.d3.commons.sidechain.iroha.consumer.status.ToriiErrorResponseException
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import mu.KLogging
import org.springframework.stereotype.Component

private const val BAD_OLD_VALUE_ERROR_CODE = 4
private const val COMPARE_AND_SET_DETAIL_COMMAND = "compareAndSetAccountDetail"
private const val MAX_CAS_ATTEMPTS = 15

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
    override fun register(
        accountName: String,
        domainId: String,
        publicKey: String
    ): Result<String, Exception> {
        return btcRegisteredAddressesProvider.ableToRegister("$accountName@$domainId")
            .map { ableToRegister ->
                if (!ableToRegister) {
                    throw D3ErrorException.warning(
                        failedOperation = BTC_REGISTRATION_OPERATION_NAME,
                        description = "Not able to register $accountName@$domainId. The user probably has been registered before"
                    )
                }
            }
            .flatMap {
                registerCAS(accountName, domainId)
            }
    }

    /**
     * Registers account using Iroha CAS
     * @param accountName - account name
     * @param domainId - domain id
     * @return registered address
     */
    private fun registerCAS(accountName: String, domainId: String): Result<String, Exception> {
        var attempts = 1
        var stopRegistration = false
        var result: Result<String, Exception>? = null
        while (!stopRegistration) {
            if (attempts >= MAX_CAS_ATTEMPTS) {
                return Result.error(
                    D3ErrorException.warning(
                        failedOperation = BTC_REGISTRATION_OPERATION_NAME,
                        description = "Cannot register $accountName@$domainId. Too many CAS attempts"
                    )
                )
            }
            // Get free address
            btcFreeAddressesProvider.getFreeAddress()
                .flatMap { freeAddress ->
                    // Try to register
                    irohaBtcAccountCreator.create(
                        freeAddress.address,
                        accountName,
                        domainId,
                        freeAddress.info.notaryKeys,
                        freeAddress.info.nodeId
                    ) {
                        btcFreeAddressesProvider.addRegisterFreeAddressCommands(it, freeAddress)
                    }
                }.fold({
                    logger.info("User $accountName@$domainId has been registered. CAS attempts $attempts")
                    // Stop the loop and return result if everything was ok
                    stopRegistration = true
                    result = Result.of(it)
                }, { ex ->
                    if (isCASError(ex)) {
                        // Wait a little before next attempt
                        attempts++
                        // Go on next iteration and try again if an error occurred due to CAS issues
                        logger.warn("Cannot register $accountName@$domainId due to CAS issues. Try again. CAS attempts $attempts")
                    } else {
                        // Stop the loop and return an exception
                        stopRegistration = true
                        result = Result.error(
                            D3ErrorException.warning(
                                failedOperation = BTC_REGISTRATION_OPERATION_NAME,
                                description = "Cannot register user $accountName@$domainId due to error response from Iroha",
                                errorCause = ex
                            )
                        )
                    }
                })
        }
        return result!!
    }

    /**
     * Checks if a given exception is related to CAS issues
     * @param ex - exception to check
     * @return true if it's related to CAS issues
     */
    private fun isCASError(ex: Exception) =
        ex is ToriiErrorResponseException &&
                ex.toriiResponse.errorCode == BAD_OLD_VALUE_ERROR_CODE &&
                ex.toriiResponse.errOrCmdName == COMPARE_AND_SET_DETAIL_COMMAND

    /**
     * Get number of free addresses.
     */
    override fun getFreeAddressNumber() = btcFreeAddressesProvider.countFreeAddresses()

    companion object : KLogging()
}
