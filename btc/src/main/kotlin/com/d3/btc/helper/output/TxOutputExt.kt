package com.d3.btc.helper.output

import com.d3.btc.helper.address.outPutToBase58Address
import org.bitcoinj.core.TransactionOutput

/**
 * Returns brief UTXO information
 */
fun TransactionOutput.info() =
    "Address ${outPutToBase58Address(this)} tx hash ${this.parentTransactionHash} value ${this.value}"

/**
 * Turns transaction input into String that may used as a key in Iroha.
 * Form: '${index of related output}_${related transaction hash}'
 * @return key
 */
fun TransactionOutput.irohaKey() = "${this.index}_${this.parentTransaction!!.hash}".substring(0..32)