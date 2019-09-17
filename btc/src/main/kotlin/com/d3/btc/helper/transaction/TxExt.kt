package com.d3.btc.helper.transaction

import jp.co.soramitsu.iroha.java.Utils
import org.bitcoinj.core.Transaction

const val DUMMY_PUB_KEY_HEX = "0000000000000000000000000000000000000000000000000000000000000000"
val DUMMY_PUB_KEY = Utils.parseHexPublicKey(DUMMY_PUB_KEY_HEX)

/**
 * Cuts [txHash] using raw tx hash string. Only first 32 symbols are taken.
 */
fun shortTxHash(txHash: String) = txHash.substring(0, 32)

/**
 * Cuts tx hash
 */
fun Transaction.shortTxHash() = shortTxHash(this.hashAsString)
