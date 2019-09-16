/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.provider

import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.helper.address.createMsAddress
import com.d3.btc.model.AddressInfo
import com.d3.btc.model.BtcAddressType
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.getRandomId
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.fanout
import com.github.kittinunf.result.map
import mu.KLogging
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 *  Bitcoin keys provider
 *  @param queryHelper - query helper that is used to get number of peers
 *  @param keysWallet - bitcoin wallet
 *  @param btcAddressGenerationConfig - address generation configuration object
 *  @param multiSigConsumer - consumer of multisignature Iroha account. Used to create multisignature transactions.
 *  @param registrationConsumer - consumer of session Iroha account. Used to store session data.
 *  @param btcNetworkConfigProvider - provider of network configuration
 */
@Component
class BtcPublicKeyProvider(
    private val queryHelper: IrohaQueryHelper,
    @Qualifier("keysWallet")
    private val keysWallet: Wallet,
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig,
    @Qualifier("multiSigConsumer")
    private val multiSigConsumer: IrohaConsumer,
    @Qualifier("registrationConsumer")
    private val registrationConsumer: IrohaConsumer,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider
) {

    /**
     * Creates notary public key and sets it into session account details
     * @param sessionAccountId - id of session account
     * @param onKeyCreated - function that will be called right after key creation
     * @return new public key created by notary
     */
    fun createKey(sessionAccountId: String, onKeyCreated: () -> Unit): Result<String, Exception> {
        // Generate new key from wallet
        val key = keysWallet.freshReceiveKey()
        onKeyCreated()
        val pubKey = key.publicKeyAsHex
        return ModelUtil.setAccountDetail(
            registrationConsumer,
            sessionAccountId,
            String.getRandomId(),
            pubKey
        ).map {
            logger.info { "New key has been generated" }
            pubKey
        }
    }

    /**
     * Creates multisignature address if enough public keys are provided
     * @param notaryKeys - list of all notaries public keys
     * @param addressType - type of address to create
     * @param generationTime - time of address generation. Used in Iroha multisig
     * @param nodeId - node id
     * @param onMsAddressCreated - function that will be called right after MS address creation
     * @return Result of operation
     */
    fun checkAndCreateMultiSigAddress(
        notaryKeys: List<String>,
        addressType: BtcAddressType,
        generationTime: Long,
        nodeId: String,
        onMsAddressCreated: () -> Unit
    ): Result<Unit, Exception> {
        return queryHelper.getPeersCount().fanout {
            multiSigConsumer.getConsumerQuorum()
        }.map { (peers, quorum) ->
            if (peers == 0) {
                throw IllegalStateException("No peers to create btc MultiSig address")
            } else if (notaryKeys.size != peers) {
                logger.info(
                    "Not enough keys are collected to generate a MultiSig address(${notaryKeys.size}" +
                            " out of $peers)"
                )
                return@map
            } else if (!hasMyKey(notaryKeys)) {
                logger.info("Cannot be involved in address generation. No access to $notaryKeys.")
                return@map
            }
            val msAddress = createMsAddress(notaryKeys, btcNetworkConfigProvider.getConfig())
            if (keysWallet.isAddressWatched(msAddress)) {
                logger.info("Address $msAddress has been already created")
                return@map
            } else if (!keysWallet.addWatchedAddress(msAddress)) {
                throw IllegalStateException("BTC address $msAddress was not added to wallet")
            }
            onMsAddressCreated()
            logger.info("Address $msAddress was added to wallet. Used keys are $notaryKeys")
            val addressStorage =
                createAddressStorage(addressType, notaryKeys, nodeId, generationTime)
            ModelUtil.setAccountDetail(
                multiSigConsumer,
                addressStorage.storageAccount,
                msAddress.toBase58(),
                addressStorage.addressInfo.toJson().irohaEscape(),
                generationTime,
                quorum
            ).fold({
                logger.info("New BTC ${addressType.title} address $msAddress was created. Node id '$nodeId'")
            }, { ex -> throw Exception("Cannot create Bitcoin MultiSig address", ex) })
        }
    }

    /**
     * Checks if current notary has its key in notaryKeys
     * @param notaryKeys - public keys of notaries
     * @return true if at least one current notary key is among given notaryKeys
     */
    private fun hasMyKey(notaryKeys: Collection<String>) = notaryKeys.find { key ->
        keysWallet.issuedReceiveKeys.find { ecKey -> ecKey.publicKeyAsHex == key } != null
    } != null

    /**
     * Creates address storage object that depends on generated address type
     * @param addressType - type of address to generate
     * @param notaryKeys - keys that were used to generate this address
     * @param nodeId - node id
     * @param generationTime - time of address generation
     */
    private fun createAddressStorage(
        addressType: BtcAddressType,
        notaryKeys: Collection<String>,
        nodeId: String,
        generationTime: Long
    ): AddressStorage {
        val (addressInfo, storageAccount) = when (addressType) {
            BtcAddressType.CHANGE -> {
                logger.info { "Creating change address" }
                Pair(
                    AddressInfo.createChangeAddressInfo(
                        ArrayList(notaryKeys),
                        nodeId,
                        generationTime
                    ),
                    btcAddressGenerationConfig.changeAddressesStorageAccount
                )
            }
            BtcAddressType.FREE -> {
                logger.info { "Creating free address" }
                Pair(
                    AddressInfo.createFreeAddressInfo(
                        ArrayList(notaryKeys),
                        nodeId,
                        generationTime
                    ),
                    //TODO use another account to store addresses
                    btcAddressGenerationConfig.notaryAccount
                )
            }
        }
        return AddressStorage(addressInfo, storageAccount)
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

/**
 * Data class that holds information about account storage
 * @param addressInfo - stores information about address: used public keys, client name and etc
 * @param storageAccount - account where this information will be stored
 */
private data class AddressStorage(val addressInfo: AddressInfo, val storageAccount: String)
