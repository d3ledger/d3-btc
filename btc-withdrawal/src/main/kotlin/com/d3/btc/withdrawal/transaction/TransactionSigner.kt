/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.helper.address.createMsRedeemScript
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.address.toEcPubKey
import com.d3.btc.helper.input.getConnectedOutput
import com.d3.btc.keypair.KeyPairService
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.BtcRegisteredAddressesProvider
import com.d3.commons.util.hex
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component

/*
   Class that is used to sign transactions using available private keys
 */
@Component
class TransactionSigner(
    private val btcRegisteredAddressesProvider: BtcRegisteredAddressesProvider,
    private val btcChangeAddressesProvider: BtcChangeAddressProvider,
    private val transfersWallet: Wallet,
    private val keyPairService: KeyPairService
) {
    /**
     * Signs transaction using available private keys
     *
     * @param tx - transaction to sign
     * @return - result with list full of signatures in form "input index"->"signatureHex hex"
     */
    fun sign(tx: Transaction): Result<List<InputSignature>, Exception> {
        return Result.of { signUnsafe(tx) }
    }

    /**
     * Returns public keys that were used to create given multi signature Bitcoin adddress
     *
     * @param btcAddress - Bitcoin address
     * @return - result with list full of public keys that were used in [btcAddress] creation
     */
    fun getUsedPubKeys(btcAddress: String): Result<List<String>, Exception> {
        return btcRegisteredAddressesProvider.getRegisteredAddresses()
            .fanout {
                btcChangeAddressesProvider.getAllChangeAddresses()
            }.map { (registeredAddresses, changeAddresses) ->
                registeredAddresses + changeAddresses
            }.map { availableAddresses ->
                availableAddresses.find { availableAddress -> availableAddress.address == btcAddress }!!.info.notaryKeys
            }
    }

    /**
     * Signs given transaction inputs if it possible
     * @return list of input signatures
     */
    private fun signUnsafe(tx: Transaction): List<InputSignature> {
        var inputIndex = 0
        val signatures = ArrayList<InputSignature>()
        tx.inputs.forEach { input ->
            val connectedOutput = input.getConnectedOutput(transfersWallet)
            getUsedPubKeys(outPutToBase58Address(connectedOutput)).fold({ pubKeys ->
                val pubKeyHex = getAvailableKey(pubKeys)
                if (pubKeyHex != null) {
                    val redeem = createMsRedeemScript(pubKeys)
                    logger.info("Redeem script for tx ${tx.hashAsString} input $inputIndex is $redeem")
                    val hashOut =
                        tx.hashForSignature(inputIndex, redeem, Transaction.SigHash.ALL, false)

                    signatures.add(
                        InputSignature(
                            inputIndex,
                            SignaturePubKey(
                                String.hex(keyPairService.sign(hashOut.bytes, pubKeyHex)!!),
                                pubKeyHex
                            )
                        )
                    )
                    logger.info { "Tx ${tx.hashAsString} input $inputIndex was signed" }
                } else {
                    logger.warn { "Cannot sign ${tx.hashAsString} input $inputIndex" }
                }
            }, { ex ->
                throw IllegalStateException("Cannot get used pub keys for ${tx.hashAsString}", ex)
            })
            inputIndex++
        }
        return signatures
    }

    /**
     * Returns public key hex which corresponding private key is controlled by the current node
     * @param pubKeys - public keys to check
     * @return public key hex or null if no keys are controlled by us
     */
    private fun getAvailableKey(pubKeys: List<String>): String? {
        return pubKeys.find { pubKey ->
            val ecKey = toEcPubKey(pubKey)
            keyPairService.exists(ecKey.publicKeyAsHex)
        }
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
