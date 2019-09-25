/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.provider

import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.btc.withdrawal.transaction.isDust
import com.d3.commons.model.D3ErrorException
import com.github.kittinunf.result.Result
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component

const val TX_FEE_SAT = 6000
private const val MAX_UTXO_ITEMS = 20

/*
   Provider that is used to collect inputs, outputs and etc
 */
@Component
class UTXOProvider(
    private val transfersWallet: Wallet,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val btcAddressStorage: BtcAddressStorage,
    private val usedUTXOProvider: UsedUTXOProvider
) {

    private val utxoComparator: Comparator<TransactionOutput> = Comparator { output1, output2 ->
        /*
        Outputs are compared by values.
        It will help us having a little amount of inputs.
        Less inputs -> smaller tx size -> smaller fee*/
        val valueComparison = -output1.value.value.compareTo(output2.value.value)
        //If values are the same, we compare UTXO by pointers
        if (valueComparison == 0) {
            val output1Pointer = output1.parentTransactionHash?.toString() + ":" + output1.index
            val output2Pointer = output2.parentTransactionHash?.toString() + ":" + output2.index
            output1Pointer.compareTo(output2Pointer)
        } else {
            valueComparison
        }
    }

    /**
     * Adds outputs(destination and change addresses) to a given transaction
     * @param transaction - current transaction
     * @param totalAmountSat - total amount of assets
     * @param destinationAddress - receiver's base58 Bitcoin address
     * @param amountToSpend - amount of SAT to spend(used to compute change)
     * @param changeAddress - address that is used to store change
     */
    fun addOutputs(
        transaction: Transaction,
        totalAmountSat: Long,
        destinationAddress: String,
        amountToSpend: Long,
        changeAddress: Address
    ) {
        transaction.addOutput(
            Coin.valueOf(amountToSpend),
            Address.fromBase58(btcNetworkConfigProvider.getConfig(), destinationAddress)
        )
        val change = totalAmountSat - amountToSpend - TX_FEE_SAT
        transaction.addOutput(Coin.valueOf(change), changeAddress)
    }

    /**
     * Collects previously sent transactions, that may be used as an input for newly created transaction
     * @param withdrawalDetails - details of withdrawal
     * @param confidenceLevel - minimum depth of transactions
     * @return result with list full of unspent transactions
     */
    fun collectUnspents(
        withdrawalDetails: WithdrawalDetails,
        confidenceLevel: Int
    ): Result<List<TransactionOutput>, Exception> = Result.of {
        val unspents = ArrayList(
            getAvailableUnspents(
                withdrawalDetails,
                transfersWallet.unspents,
                confidenceLevel
            )
        )
        if (unspents.isEmpty()) {
            throw D3ErrorException.fatal(
                failedOperation = WITHDRAWAL_OPERATION,
                description = "Cannot get enough UTXO for withdrawal $withdrawalDetails"
            )
        }
        val collectedUnspents = ArrayList<TransactionOutput>()
        unspents.sortWith(utxoComparator)
        val amountAndFee = withdrawalDetails.amountSat + TX_FEE_SAT
        var collectedAmount = 0L
        unspents.forEach { unspent ->
            if (collectedAmount >= amountAndFee) {
                return@forEach
            }
            collectedAmount += unspent.value.value
            collectedUnspents.add(unspent)
        }
        if (collectedAmount < amountAndFee) {
            throw D3ErrorException.fatal(
                failedOperation = WITHDRAWAL_OPERATION,
                description = "Cannot get enough BTC amount for withdrawal $withdrawalDetails (required $amountAndFee, collected $collectedAmount) using current unspent tx collection"
            )
        }
        collectedUnspents
    }

    /**
     * Returns currently available unspents
     * @param withdrawalDetails - details of withdrawal
     * @param unspents - all the unspents that we posses
     * @param confidenceLevel - minimum depth of transactions
     */
    private fun getAvailableUnspents(
        withdrawalDetails: WithdrawalDetails,
        unspents: List<TransactionOutput>,
        confidenceLevel: Int
    ): List<TransactionOutput> {
        var utxoCount = 0
        return unspents.sortedWith(utxoComparator).filter { unspent ->
            if (utxoCount >= MAX_UTXO_ITEMS) {
                return@filter false
            }
            val availableToSpent = !isDust(unspent.value.value) &&
                    //Only confirmed unspents may be used
                    unspent.parentTransactionDepthInBlocks >= confidenceLevel
                    //We use registered clients outputs only
                    && isAvailableOutput(unspent)
                    //Cannot use already used unspents
                    && !usedUTXOProvider.isUsed(withdrawalDetails, unspent).get()
            if (availableToSpent) {
                utxoCount++
            }
            availableToSpent
        }
    }

    // Computes total unspent value
    protected fun getTotalUnspentValue(unspents: List<TransactionOutput>): Long {
        var totalValue = 0L
        unspents.forEach { unspent -> totalValue += unspent.value.value }
        return totalValue
    }

    // Checks if fee output was addressed to available address
    protected fun isAvailableOutput(
        output: TransactionOutput
    ): Boolean {
        val btcAddress = outPutToBase58Address(output)
        return btcAddressStorage.isChangeAddress(btcAddress) || btcAddressStorage.isOurClient(btcAddress)
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
