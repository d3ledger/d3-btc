/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.commons.util.GsonInstance
import com.d3.commons.util.hex
import com.d3.commons.util.unHex
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutput

private val gson = GsonInstance.get()

/**
 * Data class that holds all the information to decide how withdrawal transaction must be created
 */
data class WithdrawalConsensus(
    val utxo: List<SerializableUTXO>,
    val withdrawalDetails: WithdrawalDetails
) {
    fun toJson() = gson.toJson(this)!!

    /**
     * Returns connected output by given input
     * @param input - input that is bound with some output
     * @return output
     */
    fun getConnectedOutput(input: TransactionInput): ConnectedOutput {
        val connectedUTXO =
            utxo.first { it.index == input.outpoint.index.toInt() && it.parentTxHash.toLowerCase() == String.hex(input.outpoint.hash.bytes).toLowerCase() }
        return ConnectedOutput(connectedUTXO.address, String.unHex(connectedUTXO.scriptHex))
    }

    companion object {
        fun fromJson(json: String) = gson.fromJson(json, WithdrawalConsensus::class.java)!!
    }
}

/**
 * Data class that represents connected output data
 */
data class ConnectedOutput(val address: String, val script: ByteArray)

/**
 * Data class that represents UTXO in a well serializable format
 */
data class SerializableUTXO(
    val inputHex: String,
    val index: Int,
    val parentTxHash: String,
    val amountSat: Long,
    val address: String,
    val scriptHex: String
) {
    companion object {
        fun toSerializableUTXO(input: TransactionInput, output: TransactionOutput): SerializableUTXO {
            return SerializableUTXO(
                index = output.index,
                parentTxHash = String.hex(output.parentTransactionHash!!.bytes),
                scriptHex = String.hex(output.scriptBytes),
                amountSat = output.value.value,
                inputHex = String.hex(input.bitcoinSerialize()),
                address = outPutToBase58Address(output)
            )
        }
    }
}
