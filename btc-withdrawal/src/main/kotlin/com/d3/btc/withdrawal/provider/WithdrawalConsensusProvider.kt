/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.provider

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.helper.iroha.isCASError
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.withdrawal.init.WITHDRAWAL_OPERATION
import com.d3.btc.withdrawal.transaction.SerializableUTXO
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.model.D3ErrorException
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import jp.co.soramitsu.iroha.java.TransactionBuilder
import mu.KLogging
import org.bitcoinj.core.Transaction
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class WithdrawalConsensusProvider(
    @Qualifier("consensusIrohaConsumer")
    private val consensusIrohaConsumer: IrohaConsumer,
    @Qualifier("reliableWithdrawalConsumer")
    private val withdrawalIrohaConsumer: IrohaConsumer,
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper,
    private val bitcoinUTXOProvider: UTXOProvider,
    private val usedUTXOProvider: UsedUTXOProvider,
    private val bitcoinConfig: BitcoinConfig,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider
) {

    /**
     * Creates consensus data and saves it in Iroha
     * @param withdrawalDetails - withdrawal details that will be used to create consensus
     */
    fun createConsensusData(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        return hasBeenEstablished(withdrawalDetails.irohaFriendlyHashCode()).flatMap { hasBeenEstablished ->
            // No need to create consensus if it has been established before
            if (hasBeenEstablished) {
                return@flatMap Result.of(Unit)
            }
            handleConsensus(withdrawalDetails)
        }
    }

    /**
     * Handle consensus creation process
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     */
    private fun handleConsensus(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        val utxo = ArrayList<SerializableUTXO>()
        // Collect unspents
        return bitcoinUTXOProvider.collectUnspents(withdrawalDetails, bitcoinConfig.confidenceLevel)
            .flatMap { unspents ->
                unspents.forEach { output ->
                    val transaction = Transaction(btcNetworkConfigProvider.getConfig())
                    val input = transaction.addInput(output)
                    input.setParent(null)
                    // Populate utxo list
                    utxo.add(SerializableUTXO.toSerializableUTXO(input, output))
                }
                // Only one node will succeed to commit the following tx
                val transactionBuilder = TransactionBuilder(
                    consensusIrohaConsumer.creator,
                    withdrawalDetails.withdrawalTime
                ).compareAndSetAccountDetail(
                    consensusIrohaConsumer.creator,
                    withdrawalDetails.irohaFriendlyHashCode(),
                    WithdrawalConsensus(utxo, withdrawalDetails).toJson().irohaEscape(),
                    null
                )
                // And UTXO registration commands to the transaction
                usedUTXOProvider.addRegisterUTXOCommands(transactionBuilder, withdrawalDetails, unspents)
                consensusIrohaConsumer.send(transactionBuilder.build())
            }.fold(
                {
                    // Start consensus registration if everything is ok
                    return registerConsensus(WithdrawalConsensus(utxo, withdrawalDetails))
                }, { ex ->
                    return if (isCASError(ex)) {
                        // Start consensus registration if the error is a CAS issue
                        registerConsensusCASFailure(withdrawalDetails)
                    } else {
                        // Return error if it's something else
                        Result.error(ex)
                    }
                })
    }

    /**
     * Register consensus in case of CAS failure
     * @param withdrawalDetails - details of withdrawal
     * @return result of operation
     */
    private fun registerConsensusCASFailure(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        // Get consensus data
        return withdrawalQueryHelper.getAccountDetails(
            consensusIrohaConsumer.creator,
            consensusIrohaConsumer.creator,
            withdrawalDetails.irohaFriendlyHashCode()
        ).flatMap { withdrawalConsensusDetail ->
            if (withdrawalConsensusDetail.isPresent) {
                // Register consensus data
                registerConsensus(WithdrawalConsensus.fromJson(withdrawalConsensusDetail.get()))
            } else {
                throw D3ErrorException.fatal(
                    failedOperation = WITHDRAWAL_OPERATION,
                    description = "Cannot register consensus for withdrawal $withdrawalDetails"
                )
            }
        }
    }

    /**
     * Registers consensus in an MST fashion
     * @param withdrawalConsensus - withdrawal consensus data to register
     */
    private fun registerConsensus(withdrawalConsensus: WithdrawalConsensus): Result<Unit, Exception> {
        return withdrawalIrohaConsumer.getConsumerQuorum()
            .flatMap { quorum ->
                withdrawalIrohaConsumer.send(
                    TransactionBuilder(
                        withdrawalIrohaConsumer.creator,
                        withdrawalConsensus.withdrawalDetails.withdrawalTime
                    ).setQuorum(quorum).setAccountDetail(
                        consensusIrohaConsumer.creator,
                        withdrawalConsensus.withdrawalDetails.irohaFriendlyHashCode(),
                        withdrawalConsensus.toJson().irohaEscape()
                    ).build()
                )
            }.map { Unit }
    }

    /**
     * Checks if withdrawal consensus has been established
     * @param withdrawalHash - hash of withdrawal to check
     * @return true if consensus has been established before
     */
    private fun hasBeenEstablished(withdrawalHash: String): Result<Boolean, Exception> {
        return withdrawalQueryHelper.getAccountDetails(
            consensusIrohaConsumer.creator,
            withdrawalIrohaConsumer.creator,
            withdrawalHash
        ).map { value ->
            value.isPresent
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
