/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.init

import com.d3.btc.generation.BTC_ADDRESS_GENERATION_SERVICE_NAME
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.generation.expansion.AddressGenerationServiceExpansion
import com.d3.btc.generation.trigger.AddressGenerationTrigger
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.healthcheck.HealthyService
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.checkWalletNetwork
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.CLIENT_DOMAIN
import com.d3.commons.sidechain.iroha.util.getCreateAccountCommands
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
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
    @Qualifier("keysWallet")
    private val keysWallet: Wallet,
    private val btcAddressGenerationConfig: BtcAddressGenerationConfig,
    private val irohaChainListener: ReliableIrohaChainListener,
    private val addressGenerationTrigger: AddressGenerationTrigger,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val addressGenerationServiceExpansion: AddressGenerationServiceExpansion,
    @Qualifier("addressGenerationHandlers")
    private val handlers: List<SetAccountDetailHandler>
) : HealthyService() {

    /**
     * Initiates address generation process
     */
    fun init(): Result<Unit, Exception> {
        //Check wallet network
        return keysWallet.checkWalletNetwork(btcNetworkConfigProvider.getConfig()).flatMap {
            irohaChainListener.getBlockObservable()
        }.map { irohaObservable ->
            initIrohaObservable(irohaObservable)
        }.flatMap {
            irohaChainListener.listen()
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
     */
    private fun initIrohaObservable(
        irohaObservable: Observable<Pair<BlockOuterClass.Block, () -> Unit>>
    ) {
        irohaObservable.subscribeOn(
            Schedulers.from(
                createPrettySingleThreadPool(
                    BTC_ADDRESS_GENERATION_SERVICE_NAME,
                    "iroha-block-handler"
                )
            )
        ).subscribe({ (block, _) ->
            // Expand the address generation service if there a need to do so
            addressGenerationServiceExpansion.expand(block)

            // Handle creation of D3 clients
            getCreateAccountCommands(block).forEach { command ->
                if (isNewClientRegistered(command.createAccount)) {
                    logger.info("New account has been created. Try to generate more addresses.")
                    generateAddressesIfNeeded()
                }
            }
            // Handle other commands
            getSetDetailCommands(block).map { command -> command.setAccountDetail }.forEach { setAccountDetailCommand ->
                handlers.forEach { handler ->
                    handler.handleFiltered(setAccountDetailCommand)
                }
            }
        }, { ex ->
            notHealthy()
            logger.error("Error on subscribe", ex)
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

    /**
     * Logger
     */
    companion object : KLogging()
}
