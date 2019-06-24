package com.d3.btc.helper.output

import com.d3.btc.helper.address.outPutToBase58Address
import org.bitcoinj.core.TransactionOutput

/**
 * Returns brief UTXO information
 */
fun TransactionOutput.info() =
    "Address ${outPutToBase58Address(this)} tx hash ${this.parentTransactionHash} value ${this.value}"

