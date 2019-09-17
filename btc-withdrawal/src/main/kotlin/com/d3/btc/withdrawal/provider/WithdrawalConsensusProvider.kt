/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.provider

import com.d3.btc.config.BTC_CONSENSUS_DOMAIN
import com.d3.btc.config.BitcoinConfig
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.model.IrohaCredential
import com.d3.commons.notary.IrohaCommand
import com.d3.commons.notary.IrohaTransaction
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.consumer.IrohaConverter
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.*
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import jp.co.soramitsu.crypto.ed25519.Ed25519Sha3
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.BigInteger

@Component
class WithdrawalConsensusProvider(
    @Qualifier("withdrawalCredential")
    private val withdrawalCredential: IrohaCredential,
    @Qualifier("consensusIrohaConsumer")
    private val consensusIrohaConsumer: IrohaConsumer,
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper,
    private val bitcoinUTXOProvider: UTXOProvider,
    private val bitcoinConfig: BitcoinConfig
) {
    private val gson = GsonInstance.get()

    /**
     * Creates consensus data and saves it in Iroha
     * @param withdrawalDetails - withdrawal details that will be used to create consensus
     */
    fun createConsensusData(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        //Create consensus storage for withdrawal
        val consensusAccountName = withdrawalDetails.irohaFriendlyHashCode()
        val consensusAccountId = "$consensusAccountName@$BTC_CONSENSUS_DOMAIN"
        return bitcoinUTXOProvider.getAvailableUTXOHeight(
            withdrawalDetails,
            bitcoinConfig.confidenceLevel,
            withdrawalDetails.withdrawalTime
        ).fanout {
            withdrawalQueryHelper.getPeersCount()
        }.map { (availableHeight, peers) ->
            val withdrawalConsensus =
                WithdrawalConsensus(
                    availableHeight,
                    peers
                )
            consensusIrohaConsumer.send(
                IrohaConverter.convert(
                    createAccountTx(
                        consensusAccountName,
                        withdrawalDetails
                    )
                )
            ).failure { ex -> throw ex }
            withdrawalConsensus
        }.map { withdrawalConsensus ->
            consensusIrohaConsumer.send(
                IrohaConverter.convert(addConsensusDataTx(consensusAccountId, withdrawalConsensus, withdrawalDetails))
            ).fold(
                {
                    logger.info(
                        "Consensus data $withdrawalConsensus has been " +
                                "successfully saved into $consensusAccountId account"
                    )
                }, { ex -> throw ex })
        }
    }

    /**
     * Returns consensus data
     * @param withdrawalHash - hash of withdrawal
     * @return consensus data in form of (withdrawal details, list of consensus data from all the nodes)
     */
    fun getConsensus(withdrawalHash: String): Result<Pair<WithdrawalDetails, List<WithdrawalConsensus>>, Exception> {
        return withdrawalQueryHelper.getAccountDetails(
            consensusIrohaConsumer.creator,
            consensusIrohaConsumer.creator,
            withdrawalHash
        ).map { value ->
            if (value.isPresent) {
                gson.fromJson(value.get(), WithdrawalDetails::class.java)
            } else {
                throw IllegalStateException("Withdrawal details data is not present for hash $withdrawalHash")
            }
        }.fanout {
            withdrawalQueryHelper.getAccountDetails(
                "$withdrawalHash@$BTC_CONSENSUS_DOMAIN",
                consensusIrohaConsumer.creator
            )
        }.map {
            val (withdrawalDetails, consensusData) = it
            Pair(
                withdrawalDetails,
                consensusData.values.map { value -> WithdrawalConsensus.fromJson(value) }.toList()
            )
        }
    }

    /**
     * Checks if withdrawal consensus has been established
     * @param withdrawalHash - hash of withdrawal to check
     * @return true if consensus has been established before
     */
    fun hasBeenEstablished(withdrawalHash: String): Result<Boolean, Exception> {
        return withdrawalQueryHelper.getAccountDetails(
            consensusIrohaConsumer.creator,
            withdrawalCredential.accountId,
            withdrawalHash
        ).map { value ->
            value.isPresent
        }
    }

    /**
     * Creates account creation transaction.
     * This account will be used to store consensus data
     * @param consensusAccountName - account name where consensus data will be stored
     * @param withdrawalDetails - details of withdrawal
     * @return well formed transaction
     */
    private fun createAccountTx(
        consensusAccountName: String,
        withdrawalDetails: WithdrawalDetails
    ): IrohaTransaction {
        return IrohaTransaction(
            consensusIrohaConsumer.creator,
            BigInteger.valueOf(withdrawalDetails.withdrawalTime),
            1,
            arrayListOf(
                IrohaCommand.CommandCreateAccount(
                    consensusAccountName,
                    BTC_CONSENSUS_DOMAIN,
                    // No matter what key. This account is used for storage only
                    Utils.parseHexPublicKey("0000000000000000000000000000000000000000000000000000000000000000").toHexString()
                )
            )
        )
    }

    /**
     * Creates consensus data addition transaction
     * @param consensusAccountId - account id, where consensus data will be stored
     * @param withdrawalConsensus - withdrawal consensus data
     * @param withdrawalDetails - details of withdrawal
     * @return well formed transaction
     */
    private fun addConsensusDataTx(
        consensusAccountId: String,
        withdrawalConsensus: WithdrawalConsensus,
        withdrawalDetails: WithdrawalDetails
    ): IrohaTransaction {
        return IrohaTransaction(
            consensusIrohaConsumer.creator,
            ModelUtil.getCurrentTime(),
            1,
            arrayListOf(
                IrohaCommand.CommandSetAccountDetail(
                    consensusAccountId,
                    String.getRandomId(),
                    withdrawalConsensus.toJson().irohaEscape()
                ),
                IrohaCommand.CommandSetAccountDetail(
                    consensusIrohaConsumer.creator,
                    withdrawalDetails.irohaFriendlyHashCode(),
                    gson.toJson(withdrawalDetails).irohaEscape()
                )
            )
        )
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
