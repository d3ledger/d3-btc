package com.d3.btc.helper.transaction

import org.bitcoinj.core.Transaction


/**
 * Cuts [txHash] using raw tx hash string. Only first 32 symbols are taken.
 */
fun shortTxHash(txHash: String) = txHash.substring(0, 32)

/**
 * Cuts tx hash
 */
fun Transaction.shortTxHash() = shortTxHash(this.hashAsString)
