/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.model.BtcAddress
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.btc.withdrawal.provider.TX_FEE_SAT
import com.d3.btc.withdrawal.provider.UTXOProvider
import com.d3.commons.model.D3ErrorException
import com.d3.commons.util.unHex
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.springframework.stereotype.Component
import kotlin.random.Random

/*
    Class that is used to create BTC transactions
 */
@Component
class TransactionCreator(
    private val btcChangeAddressProvider: BtcChangeAddressProvider,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val bitcoinUTXOProvider: UTXOProvider,
    private val transactionsStorage: TransactionsStorage
) {

    /**
     * Creates UNSIGNED Bitcoin transaction
     * @param withdrawalConsensus - withdrawal consensus data
     * @return result with unsigned transaction full of input/output data and used unspents
     */
    fun createTransaction(
        withdrawalConsensus: WithdrawalConsensus
    ): Result<Transaction, Exception> {
        val withdrawalDetails = withdrawalConsensus.withdrawalDetails
        val transaction = Transaction(btcNetworkConfigProvider.getConfig())
        val unspents = withdrawalConsensus.utxo.map {
            TransactionInput(
                btcNetworkConfigProvider.getConfig(),
                null,
                String.unHex(it.inputHex),
                0
            )
        }
        var totalAmount: Long = 0
        withdrawalConsensus.utxo.forEach {
            totalAmount += it.amountSat
        }
        return btcChangeAddressProvider.getAllChangeAddresses(withdrawalDetails.withdrawalTime)
            .map { changeAddresses ->
                unspents.forEach { unspent -> transaction.addInput(unspent) }
                val changeAddress = chooseChangeAddress(withdrawalDetails, changeAddresses).address
                logger.info("Change address chosen for withdrawal $withdrawalDetails is $changeAddress")
                bitcoinUTXOProvider.addOutputs(
                    transaction,
                    totalAmount,
                    withdrawalDetails.toAddress,
                    withdrawalDetails.amountSat,
                    Address.fromBase58(
                        btcNetworkConfigProvider.getConfig(),
                        changeAddress
                    )
                )
                unspents
            }.map {
                transactionsStorage.save(withdrawalConsensus, transaction).failure { ex ->
                    throw D3ErrorException.fatal(
                        failedOperation = WITHDRAWAL_OPERATION,
                        description = "Cannot save Bitcoin transaction for withdrawal $withdrawalDetails",
                        errorCause = ex
                    )
                }
                transaction
            }
    }

    /**
     * Chooses change addresses for withdrawal among set of change addresses
     * @param withdrawalDetails - details of withdrawal
     * @param changeAddresses - all change addresses
     * @return change address
     */
    private fun chooseChangeAddress(
        withdrawalDetails: WithdrawalDetails,
        changeAddresses: List<BtcAddress>
    ): BtcAddress {
        /*
        Every node must choose the same change address in order to create the same Bitcoih transaction.
        Address must be chosen randomly to distribute changes among addresses equally.
        We can do it using Random() that takes withdrawal time as a seed
        (we assume that withdrawal time is the same on all the nodes)
         */
        val random = Random(withdrawalDetails.withdrawalTime)
        return changeAddresses.random(random)
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

/**
 * Checks if satValue is too low to spend
 * @param satValue - amount of SAT to check if it's a dust
 * @return true, if [satValue] is a dust
 */
fun isDust(satValue: Long) = satValue < TX_FEE_SAT
