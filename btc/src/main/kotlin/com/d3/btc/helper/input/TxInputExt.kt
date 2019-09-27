package com.d3.btc.helper.input

import org.bitcoinj.core.TransactionInput
import org.bitcoinj.script.Script

/**
 * Turns transaction input into String that may used as a key in Iroha.
 * Form: '${index of related output}_${related transaction hash}'
 * @return key
 */
fun TransactionInput.irohaKey() = "${this.outpoint.index}_${this.outpoint.hash}".substring(0..32)

/**
 * Verifies that the current input was signed correctly
 * @param scriptBytes - corresponding output script
 */
fun TransactionInput.verify(scriptBytes: ByteArray) {
    val pubKey = Script(scriptBytes)
    val myIndex = parentTransaction.inputs.indexOf(this)
    scriptSig.correctlySpends(parentTransaction, myIndex.toLong(), pubKey)
}
