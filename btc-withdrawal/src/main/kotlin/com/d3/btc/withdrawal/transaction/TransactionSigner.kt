/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.helper.address.createMsRedeemScript
import com.d3.btc.helper.address.toEcPubKey
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.btc.wallet.safeLoad
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.commons.model.D3ErrorException
import com.d3.commons.util.hex
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/*
   Class that is used to sign transactions using available private keys
 */
@Component
class TransactionSigner(
    private val btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
    private val btcChangeAddressesProvider: BtcChangeAddressProvider
) {
    /**
     * Signs transaction using available private keys from wallet
     *
     * @param tx - transaction to sign
     * @param keysWalletPath - path to wallet file. Used to take private keys
     * @param withdrawalConsensus - withdrawal consensus data
     * @return - result with list full of signatures in form "input index"->"signatureHex hex"
     */
    fun sign(
        tx: Transaction,
        keysWalletPath: String,
        withdrawalConsensus: WithdrawalConsensus
    ): Result<List<InputSignature>, Exception> {
        return Result.of { signUnsafe(tx, safeLoad(keysWalletPath), withdrawalConsensus) }
    }

    /**
     * Returns public keys that were used to create given multi signature Bitcoin adddress
     *
     * @param btcAddress - Bitcoin address
     * @return - result with list full of public keys that were used in [btcAddress] creation
     */
    fun getUsedPubKeys(btcAddress: String): Result<List<String>, Exception> {
        // Try to find the address among change addresses
        return btcChangeAddressesProvider.getAddressInfo(btcAddress)
            .flatMap { addressInfo ->
                // Try to find the address among registered addresses if it wasn't found among change addresses
                if (!addressInfo.isPresent) {
                    btcRegisteredAddressesProvider.getAddressInfo(btcAddress)
                } else {
                    Result.of { addressInfo }
                }
            }.map { addressInfo ->
                if (addressInfo.isPresent) {
                    addressInfo.get().notaryKeys
                } else {
                    throw java.lang.IllegalStateException("Cannot find BTC address $btcAddress")
                }
            }
    }

    // Main signing function
    private fun signUnsafe(
        tx: Transaction,
        wallet: Wallet,
        withdrawalConsensus: WithdrawalConsensus
    ): List<InputSignature> {
        var inputIndex = 0
        val signatures = ArrayList<InputSignature>()
        tx.inputs.forEach { input ->
            val connectedOutput = withdrawalConsensus.getConnectedOutput(input)
            getUsedPubKeys(connectedOutput.address).fold({ pubKeys ->
                val keyPair = getPrivPubKeyPair(pubKeys, wallet)
                if (keyPair != null) {
                    val redeem = createMsRedeemScript(pubKeys)
                    logger.info("Redeem script for tx ${tx.hashAsString} input $inputIndex is $redeem")
                    val hashOut =
                        tx.hashForSignature(inputIndex, redeem, Transaction.SigHash.ALL, false)
                    val signature = keyPair.sign(hashOut)
                    signatures.add(
                        InputSignature(
                            inputIndex,
                            SignaturePubKey(
                                String.hex(signature.encodeToDER()),
                                keyPair.publicKeyAsHex
                            )
                        )
                    )
                    logger.info { "Tx ${tx.hashAsString} input $inputIndex was signed" }
                } else {
                    logger.warn { "Cannot sign ${tx.hashAsString} input $inputIndex" }
                }
            }, { ex ->
                throw D3ErrorException.fatal(
                    failedOperation = WITHDRAWAL_OPERATION,
                    description = "Cannot get used pub keys for Bitcoin transaction ${tx.hashAsString}",
                    errorCause = ex
                )
            })
            inputIndex++
        }
        return signatures
    }

    //Returns key pair related to one of given public keys. Returns null if no key pair was found
    private fun getPrivPubKeyPair(pubKeys: List<String>, wallet: Wallet): ECKey? {
        pubKeys.forEach { pubKey ->
            val ecKey = toEcPubKey(pubKey)
            val keyPair = wallet.findKeyFromPubHash(ecKey.pubKeyHash)
            if (keyPair != null) {
                return keyPair
            }
        }
        return null
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

//Class that stores input with its signature and public key in hex format
data class InputSignature(val index: Int, val sigPubKey: SignaturePubKey)

//Class that stores signature and public key in hex format
data class SignaturePubKey(val signatureHex: String, val pubKey: String)
