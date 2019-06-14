/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.init

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.expansion.AddressGenerationServiceExpansion
import com.d3.btc.generation.trigger.AddressGenerationTrigger
import com.d3.btc.healthcheck.HealthyService
import com.d3.btc.model.BtcAddressType
import com.d3.btc.model.getAddressTypeByAccountId
import com.d3.btc.provider.account.BTC_CURRENCY_NAME_KEY
import com.d3.btc.provider.generation.ADDRESS_GENERATION_NODE_ID_KEY
import com.d3.btc.provider.generation.ADDRESS_GENERATION_TIME_KEY
import com.d3.btc.provider.generation.BtcPublicKeyProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.checkWalletNetwork
import com.d3.btc.wallet.safeSave
import com.d3.commons.sidechain.iroha.CLIENT_DOMAIN
import com.d3.commons.sidechain.iroha.IrohaChainListener
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.getCreateAccountCommands
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import iroha.protocol.BlockOuterClass
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/*
   This class listens to special account to be triggered and starts generation process
 */
@Component
class BtcAddressGenerationInitialization(
    private val keysWallet: Wallet,
    @Qualifier("registrationQueryHelper")
    private val registrationQueryHelper: IrohaQueryHelper,
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig,
    private val btcPublicKeyProvider: BtcPublicKeyProvider,
    private val irohaChainListener: IrohaChainListener,
    private val addressGenerationTrigger: AddressGenerationTrigger,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val addressGenerationServiceExpansion: AddressGenerationServiceExpansion
) : HealthyService() {

    /**
     * Initiates address generation process
     * @param onIrohaFail - function that will be called on Iroha failure
     */
    fun init(onIrohaFail: () -> Unit): Result<Unit, Exception> {
        //Check wallet network
        return keysWallet.checkWalletNetwork(btcNetworkConfigProvider.getConfig()).flatMap {
            irohaChainListener.getBlockObservable()
        }.map { irohaObservable ->
            initIrohaObservable(irohaObservable, onIrohaFail)
        }.flatMap {
            // Start free address generation at initial phase
            addressGenerationTrigger
                .startFreeAddressGenerationIfNeeded(
                    btcAddressGenerationConfig.threshold,
                    btcAddressGenerationConfig.nodeId
                )
        }.flatMap {
            // Start change address generation at initial phase
            addressGenerationTrigger
                .startChangeAddressGenerationIfNeeded(btcAddressGenerationConfig.nodeId)
        }
    }

    /**
     * Initiates Iroha observable
     * @param irohaObservable - Iroha observable object to initiate
     * @param onIrohaFail - function that will be called on Iroha failure
     */
    private fun initIrohaObservable(
        irohaObservable: Observable<BlockOuterClass.Block>,
        onIrohaFail: () -> Unit
    ) {
        irohaObservable.subscribeOn(
            Schedulers.from(
                createPrettySingleThreadPool(
                    BTC_ADDRESS_GENERATION_SERVICE_NAME,
                    "iroha-block-handler"
                )
            )
        ).subscribe({ block ->
            // Expand the address generation service if there a need to do so
            addressGenerationServiceExpansion.expand(block)

            getCreateAccountCommands(block).forEach { command ->
                if (isNewClientRegistered(command.createAccount)) {
                    logger.info("New account has been created. Try to generate more addresses.")
                    generateAddressesIfNeeded()
                }
            }
            getSetDetailCommands(block).forEach { command ->
                if (isBtcAddressRegistered(command.setAccountDetail)) {
                    logger.info("BTC address has been registered. Try to generate more addresses.")
                    generateAddressesIfNeeded()
                } else if (isAddressGenerationTriggered(command)) {
                    //add new public key to session account, if trigger account was changed
                    val sessionAccountName = command.setAccountDetail.key
                    onGenerateKey(sessionAccountName).fold(
                        { pubKey -> logger.info { "New public key $pubKey for BTC multisignature address was created" } },
                        { ex ->
                            logger.error(
                                "Cannot generate public key for BTC multisignature address",
                                ex
                            )
                        })
                } else if (isNewKey(command)) {
                    val accountId = command.setAccountDetail.accountId
                    //create multisignature address, if we have enough keys in session account
                    onGenerateMultiSigAddress(
                        accountId,
                        getAddressTypeByAccountId(accountId)
                    ).failure { ex ->
                        logger.error(
                            "Cannot generate multi signature address", ex
                        )
                    }
                }
            }
        }, { ex ->
            notHealthy()
            logger.error("Error on subscribe", ex)
            onIrohaFail()
        })
    }

    /**
     * Generates Bitcoin addresses if there is a need to do that
     */
    private fun generateAddressesIfNeeded() {
        addressGenerationTrigger.startFreeAddressGenerationIfNeeded(
            btcAddressGenerationConfig.threshold,
            btcAddressGenerationConfig.nodeId
        ).fold(
            { "Free BTC address generation was triggered" },
            { ex -> logger.error("Cannot trigger address generation", ex) })
    }

    // Checks if new client was registered
    private fun isNewClientRegistered(createAccountCommand: Commands.CreateAccount): Boolean {
        return createAccountCommand.domainId == CLIENT_DOMAIN
    }

    // Check if btc address was registered
    private fun isBtcAddressRegistered(setAccountDetailCommand: Commands.SetAccountDetail): Boolean {
        return setAccountDetailCommand.accountId.endsWith(CLIENT_DOMAIN)
                && setAccountDetailCommand.key == BTC_CURRENCY_NAME_KEY
    }

    // Checks if address generation account was triggered
    private fun isAddressGenerationTriggered(command: Commands.Command) =
        command.setAccountDetail.accountId == btcAddressGenerationConfig.pubKeyTriggerAccount

    // Checks if new key was added
    private fun isNewKey(command: Commands.Command) =
        command.setAccountDetail.accountId.endsWith("btcSession")
                && command.setAccountDetail.key != ADDRESS_GENERATION_TIME_KEY
                && command.setAccountDetail.key != ADDRESS_GENERATION_NODE_ID_KEY

    // Generates new key
    private fun onGenerateKey(sessionAccountName: String): Result<String, Exception> {
        return btcPublicKeyProvider.createKey(sessionAccountName) { saveWallet() }
    }

    /**
     * Generates multisig address
     * @param sessionAccount - account that holds public keys that are used in multisig address generation
     * @param addressType - type of address to generate
     */
    private fun onGenerateMultiSigAddress(
        sessionAccount: String,
        addressType: BtcAddressType
    ): Result<Unit, Exception> {
        return registrationQueryHelper.getAccountDetails(
            sessionAccount,
            btcAddressGenerationConfig.registrationAccount.accountId
        ).map { it.toMutableMap() }
            .flatMap { details ->
                // Getting time
                val time = details.remove(ADDRESS_GENERATION_TIME_KEY)!!.toLong()
                // Getting node id
                val nodeId = details.remove(ADDRESS_GENERATION_NODE_ID_KEY)!!
                // Getting keys
                val notaryKeys = details.values
                if (!notaryKeys.isEmpty()) {
                    btcPublicKeyProvider.checkAndCreateMultiSigAddress(
                        notaryKeys.toList(),
                        addressType,
                        time,
                        nodeId
                    ) { saveWallet() }
                } else {
                    Result.of { Unit }
                }
            }
    }

    // Safes wallet full of keys
    private fun saveWallet() {
        keysWallet.safeSave(btcAddressGenerationConfig.btcKeysWalletPath)
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
