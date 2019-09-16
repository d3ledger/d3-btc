/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.provider

import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.fee.getTxFee
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.output.info
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.btc.withdrawal.transaction.isDust
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component

//Only two outputs are used: destination and change
private const val OUTPUTS = 2

/*
   Provider that is used to collect inputs, outputs and etc
 */
@Component
class UTXOProvider(
    private val transfersWallet: Wallet,
    private val peerGroup: SharedPeerGroup,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val btcAddressStorage: BtcAddressStorage,
    private val usedUTXOProvider: UsedUTXOProvider
) {

    /**
     * Adds outputs(destination and change addresses) to a given transaction
     * @param transaction - current transaction
     * @param unspents - used to compute change
     * @param destinationAddress - receiver's base58 Bitcoin address
     * @param amount - amount of SAT to spend(used to compute change)
     * @param changeAddress - address that is used to store change
     */
    fun addOutputs(
        transaction: Transaction,
        unspents: List<TransactionOutput>,
        destinationAddress: String,
        amount: Long,
        changeAddress: Address
    ) {
        val totalAmount = getTotalUnspentValue(unspents)
        transaction.addOutput(
            Coin.valueOf(amount),
            Address.fromBase58(btcNetworkConfigProvider.getConfig(), destinationAddress)
        )
        val change =
            totalAmount - amount - getTxFee(transaction.inputs.size, OUTPUTS, CurrentFeeRate.get())
        transaction.addOutput(Coin.valueOf(change), changeAddress)
    }

    /**
     * Collects previously sent transactions, that may be used as an input for newly created transaction
     * @param withdrawalDetails - details of withdrawal
     * @param availableAddresses - set of addresses which transactions will be available to spend
     * @param amount - amount of SAT to spend
     * @param availableHeight - maximum available height for UTXO
     * @param confidenceLevel - minimum depth of transactions
     * @return result with list full of unspent transactions
     */
    fun collectUnspents(
        withdrawalDetails: WithdrawalDetails,
        availableAddresses: Set<String>,
        amount: Long,
        availableHeight: Int,
        confidenceLevel: Int
    ): Result<List<TransactionOutput>, Exception> {
        return Result.of {
            collectUnspentsRec(
                withdrawalDetails,
                availableAddresses,
                amount,
                0,
                availableHeight,
                confidenceLevel,
                ArrayList()
            )
        }
    }

    /**
     * Frees outputs, making them usable for other transactions
     * @param transaction -  transaction which outputs/unspents must be freed
     * @param withdrawalDetails - details of withdrawal that is related to given [transaction]
     */
    fun unregisterUnspents(transaction: Transaction, withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        return usedUTXOProvider.unregisterUsedUTXO(transaction, withdrawalDetails)
    }

    /**
     * Returns available addresses (intersection between watched and registered addresses)
     * @param generatedBefore - only addresses that were generated before certain time are considered available
     * @return result with set full of available addresses
     */
    fun getAvailableAddresses(generatedBefore: Long): Result<Set<String>, Exception> {
        return Result.of {
            val allAddresses = HashSet<String>()
            allAddresses.addAll(btcAddressStorage.getClientAddresses())
            allAddresses.addAll(btcAddressStorage.getChangeAddresses())
            logger.info("All addresses $allAddresses")
            allAddresses.filter { btcAddress ->
                transfersWallet.isAddressWatched(
                    Address.fromBase58(
                        btcNetworkConfigProvider.getConfig(),
                        btcAddress
                    )
                )
            }.toSet()
        }
    }

    /**
     * Returns currently available UTXO height
     * @param withdrawalDetails - details of withdrawal
     * @param withdrawalTime - time of withdrawal
     * @param confidenceLevel - minimum depth of transactions
     */
    fun getAvailableUTXOHeight(
        withdrawalDetails: WithdrawalDetails,
        confidenceLevel: Int,
        withdrawalTime: Long
    ): Result<Int, Exception> {
        return getAvailableAddresses(withdrawalTime).map { availableAddresses ->
            getAvailableUnspents(
                withdrawalDetails,
                transfersWallet.unspents,
                Integer.MAX_VALUE,
                confidenceLevel,
                availableAddresses
            ).map { unspent -> getUnspentHeight(unspent) }.max() ?: 0
        }
    }

    /**
     * Collects previously sent transactions, that may be used as an input for newly created transaction.
     * It may go into recursion if not enough money for fee was collected.
     * @param withdrawalDetails - details of withdrawal
     * @param availableAddresses - set of addresses which transactions will be available to spend
     * @param amount - amount of SAT to spend
     * @param fee - tx fee that depends on inputs and outputs. Initial value is zero.
     * @param availableHeight - maximum available height for UTXO
     * @param confidenceLevel - minimum depth of transactions
     * @param recursivelyCollectedUnspents - list of unspents collected from all recursion levels. It will be returned at the end on execution
     * @return list full of unspent transactions
     */
    private tailrec fun collectUnspentsRec(
        withdrawalDetails: WithdrawalDetails,
        availableAddresses: Set<String>,
        amount: Long,
        fee: Int,
        availableHeight: Int,
        confidenceLevel: Int,
        recursivelyCollectedUnspents: MutableList<TransactionOutput>
    ): List<TransactionOutput> {
        logger.info("All unspents\n${transfersWallet.unspents.map { unspent -> unspent.info() }}")
        val unspents = ArrayList(getAvailableUnspents(
            withdrawalDetails,
            transfersWallet.unspents,
            availableHeight,
            confidenceLevel,
            availableAddresses
        ).filter { unspent -> !recursivelyCollectedUnspents.contains(unspent) })

        if (unspents.isEmpty()) {
            throw IllegalStateException("Out of unspents")
        }
        logger.info("Filtered unspents\n${unspents.map { unspent -> unspent.info() }}")

        /*
        Wallet stores unspents in a HashSet. Order of a HashSet depends on several factors: current array size and etc.
        This may lead different notary nodes to pick different transactions.
        This is why we order transactions manually, essentially reducing the probability of
        different nodes to pick different output transactions.*/
        unspents.sortWith(Comparator { output1, output2 ->
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
        })
        val amountAndFee = amount + fee
        var collectedAmount = getTotalUnspentValue(recursivelyCollectedUnspents)
        unspents.forEach { unspent ->
            if (collectedAmount >= amountAndFee) {
                return@forEach
            }
            collectedAmount += unspent.value.value
            recursivelyCollectedUnspents.add(unspent)
        }
        if (collectedAmount < amountAndFee) {
            throw IllegalStateException("Cannot get enough BTC amount(required $amountAndFee, collected $collectedAmount) using current unspent tx collection")
        }
        // Check if able to pay fee
        val newFee = getTxFee(recursivelyCollectedUnspents.size, OUTPUTS, CurrentFeeRate.get())
        if (collectedAmount < amount + newFee) {
            logger.info { "Not enough BTC amount(required $amount, fee $newFee, collected $collectedAmount) was collected for fee" }
            // Try to collect more unspents if no money is left for fee
            return collectUnspentsRec(
                withdrawalDetails,
                availableAddresses,
                amount,
                newFee,
                availableHeight,
                confidenceLevel,
                recursivelyCollectedUnspents
            )
        }
        return recursivelyCollectedUnspents
    }

    /**
     * Returns currently available unspents
     * @param withdrawalDetails - details of withdrawal
     * @param unspents - all the unspents that we posses
     * @param availableHeight - maximum available height for UTXO
     * @param confidenceLevel - minimum depth of transactions
     * @param availableAddresses - available addresses
     */
    private fun getAvailableUnspents(
        withdrawalDetails: WithdrawalDetails,
        unspents: List<TransactionOutput>,
        availableHeight: Int,
        confidenceLevel: Int,
        availableAddresses: Set<String>
    ): List<TransactionOutput> {
        return unspents.filter { unspent ->
            // It's senseless to use 'dusty' transaction, because its fee will be higher than its value
            !isDust(unspent.value.value) &&
                    //Only confirmed unspents may be used
                    unspent.parentTransactionDepthInBlocks >= confidenceLevel
                    //Cannot use already used unspents
                    && !usedUTXOProvider.isUsed(withdrawalDetails, unspent).get()
                    //We are able to use those UTXOs which height is not bigger then availableHeight
                    && getUnspentHeight(unspent) <= availableHeight
                    //We use registered clients outputs only
                    && isAvailableOutput(availableAddresses, unspent)
        }
    }

    /**
     * Returns block height of a given unspent
     * @param unspent - UTXO
     * @return time of unspent transaction block
     */
    private fun getUnspentHeight(unspent: TransactionOutput): Int {
        return peerGroup.getBlock(unspent.parentTransaction!!.appearsInHashes!!.keys.first())!!.height
    }

    // Computes total unspent value
    protected fun getTotalUnspentValue(unspents: List<TransactionOutput>): Long {
        var totalValue = 0L
        unspents.forEach { unspent -> totalValue += unspent.value.value }
        return totalValue
    }

    // Checks if fee output was addressed to available address
    protected fun isAvailableOutput(
        availableAddresses: Set<String>,
        output: TransactionOutput
    ): Boolean {
        val btcAddress = outPutToBase58Address(output)
        return availableAddresses.contains(btcAddress)
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
