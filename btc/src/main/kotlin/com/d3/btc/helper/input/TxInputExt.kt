package com.d3.btc.helper.input

import org.bitcoinj.core.TransactionInput
import org.bitcoinj.wallet.Wallet

const val REMOVED_UTXO_KEY = "removed"
const val REGISTERED_UTXO_KEY = "registered"

/**
 * Returns connected output
 * @param transfersWallet - wallet with transfers. Used to get connected output
 * @return connected output
 */
fun TransactionInput.getConnectedOutput(transfersWallet: Wallet) =
    transfersWallet.getTransaction(this.outpoint.hash)!!.outputs[this.outpoint.index.toInt()]!!


/**
 * Turns transaction input into String that may used as a key in Iroha.
 * Form: '${index of related output}_${related transaction hash}'
 * @return key
 */
fun TransactionInput.irohaKey() = "${this.outpoint.index}_${this.outpoint.hash}".substring(0..32)

/**
 * Creates key-value pair for removed UTXO
 */
fun TransactionInput.removeFromIrohaKeyValue() = Pair(this.irohaKey(), REMOVED_UTXO_KEY)

/**
 * Creates key-value pair for registered UTXO
 */
fun TransactionInput.registerInIrohaKeyValue() = Pair(this.irohaKey(), REGISTERED_UTXO_KEY)
