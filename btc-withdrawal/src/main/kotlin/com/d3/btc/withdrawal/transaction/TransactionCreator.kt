/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.transaction

import com.d3.btc.fee.BYTES_PER_INPUT
import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.model.BtcAddress
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.provider.UTXOProvider
import com.github.kittinunf.result.*
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.springframework.stereotype.Component
import kotlin.random.Random

//TODO don't forget to restore test
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
     * @param withdrawalDetails - details of withdrawal
     * @param availableHeight - maximum height of UTXO
     * @param confidenceLevel - minimum tx depth that will be used in unspents
     * @return result with unsigned transaction full of input/output data and used unspents
     */
    fun createTransaction(
        withdrawalDetails: WithdrawalDetails,
        availableHeight: Int,
        confidenceLevel: Int
    ): Result<Transaction, Exception> {
        val transaction = Transaction(btcNetworkConfigProvider.getConfig())
        return bitcoinUTXOProvider.getAvailableAddresses(withdrawalDetails.withdrawalTime)
            .flatMap { availableAddresses ->
                logger.info("Available addresses $availableAddresses")
                bitcoinUTXOProvider.collectUnspents(
                    availableAddresses,
                    withdrawalDetails.amountSat,
                    availableHeight,
                    confidenceLevel
                )
            }.fanout {
                btcChangeAddressProvider.getAllChangeAddresses(withdrawalDetails.withdrawalTime)
            }.map { (unspents, changeAddresses) ->
                unspents.forEach { unspent -> transaction.addInput(unspent) }
                val changeAddress = chooseChangeAddress(withdrawalDetails, changeAddresses).address
                logger.info("Change address chosen for withdrawal $withdrawalDetails is $changeAddress")
                bitcoinUTXOProvider.addOutputs(
                    transaction,
                    unspents,
                    withdrawalDetails.toAddress,
                    withdrawalDetails.amountSat,
                    Address.fromBase58(
                        btcNetworkConfigProvider.getConfig(),
                        changeAddress
                    )
                )
                unspents
            }.map { unspents ->
                transactionsStorage.save(withdrawalDetails, transaction).failure { ex -> throw ex }
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
fun isDust(satValue: Long) = satValue < (CurrentFeeRate.get() * BYTES_PER_INPUT)
