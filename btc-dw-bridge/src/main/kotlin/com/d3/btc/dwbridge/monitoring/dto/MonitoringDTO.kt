package com.d3.btc.dwbridge.monitoring.dto

import java.math.BigDecimal

/**
 * Class that represents available BTC sum
 */
data class AvailableSumBtc(val sumBtc: BigDecimal)

/**
 * Class that represents available UTXO set
 */
data class UTXOSetBtc(val utxoList: List<UTXOBtc>)

/**
 * Class that represents UTXO item
 */
data class UTXOBtc(
    val confirmations: Int,
    val btcAmount: BigDecimal,
    val txHash: String,
    val outputIndex: Int,
    val receiverAddress: String
)
