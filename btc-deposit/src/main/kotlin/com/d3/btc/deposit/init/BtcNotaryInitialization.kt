/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.init

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.deposit.config.BTC_DEPOSIT_SERVICE_NAME
import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.deposit.expansion.DepositServiceExpansion
import com.d3.btc.deposit.listener.BitcoinBlockChainDepositListener
import com.d3.btc.deposit.service.BtcWalletListenerRestartService
import com.d3.btc.handler.SetAccountDetailHandler
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.healthcheck.HealthyService
import com.d3.btc.helper.network.addPeerConnectionStatusListener
import com.d3.btc.helper.network.startChainDownload
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.checkWalletNetwork
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.notary.NotaryImpl
import com.d3.commons.sidechain.SideChainEvent
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.failure
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import io.reactivex.subjects.PublishSubject
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService

@Component
class BtcNotaryInitialization(
    private val peerGroup: SharedPeerGroup,
    private val transferWallet: Wallet,
    private val btcDepositConfig: BtcDepositConfig,
    private val bitcoinConfig: BitcoinConfig,
    private val notary: NotaryImpl,
    private val btcEventsSource: PublishSubject<SideChainEvent.PrimaryBlockChainEvent>,
    private val btcWalletListenerRestartService: BtcWalletListenerRestartService,
    @Qualifier("confidenceListenerExecutorService")
    private val confidenceListenerExecutorService: ExecutorService,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val depositServiceExpansion: DepositServiceExpansion,
    @Qualifier("depositReliableIrohaChainListener")
    private val irohaChainListener: ReliableIrohaChainListener,
    private val btcAddressStorage: BtcAddressStorage,
    @Qualifier("depositHandlers")
    private val accountDetailHandlers: List<SetAccountDetailHandler>
) : HealthyService(), Closeable {

    // Executor that will be used to execute Bitcoin deposit listener logic
    private val blockChainDepositListenerExecutor =
        createPrettySingleThreadPool(BTC_DEPOSIT_SERVICE_NAME, "blockchain-deposit-listener")

    // Function that is called to save all the transactions in wallet
    private fun onTxSave() {
        transferWallet.saveToFile(File(btcDepositConfig.btcTransferWalletPath))
        logger.info { "Wallet was saved in ${btcDepositConfig.btcTransferWalletPath}" }
    }

    /**
     * Init notary
     */
    fun init(): Result<Unit, Exception> {
        logger.info { "Btc notary initialization" }
        //Enables short log format for Bitcoin events
        BriefLogFormatter.init()
        addPeerConnectionStatusListener(peerGroup, ::notHealthy, ::cured)
        // Check wallet network
        return transferWallet.checkWalletNetwork(btcNetworkConfigProvider.getConfig()).map {
            // Restart wallet listeners
            btcWalletListenerRestartService.restartTransactionListeners(
                transferWallet, ::onTxSave
            )
        }.flatMap {
            irohaChainListener.getBlockObservable()
        }.map { irohaObservable ->
            irohaObservable.subscribe { (block, _) ->
                // Expand the deposit service if there is a need to do so
                depositServiceExpansion.expand(block)
            }
            irohaObservable.subscribe { (block, _) ->
                getSetDetailCommands(block).map { it.setAccountDetail }
                    .forEach { setAccountDetailCommand ->
                        accountDetailHandlers.forEach { handler ->
                            handler.handleFiltered(setAccountDetailCommand)
                        }
                    }

            }
            logger.info { "Registration service listener was successfully initialized" }
        }.map {
            irohaChainListener.listen()
        }.map {
            initBtcEvents(peerGroup, bitcoinConfig.confidenceLevel)
        }.map {
            notary.initIrohaConsumer().failure { ex -> throw ex }
        }.map {
            startChainDownload(peerGroup)
        }
    }

    //Checks if address is watched by notary
    fun isWatchedAddress(btcAddress: String) =
        transferWallet.isAddressWatched(
            Address.fromBase58(
                btcNetworkConfigProvider.getConfig(),
                btcAddress
            )
        )

    /**
     * Initiates Btc deposit events
     */
    private fun initBtcEvents(
        peerGroup: PeerGroup,
        confidenceLevel: Int
    ) {
        peerGroup.addBlocksDownloadedEventListener(
            blockChainDepositListenerExecutor,
            BitcoinBlockChainDepositListener(
                btcEventsSource,
                confidenceListenerExecutorService,
                confidenceLevel,
                btcAddressStorage,
                ::onTxSave
            )
        )
    }

    override fun close() {
        logger.info { "Closing Bitcoin notary service" }
        blockChainDepositListenerExecutor.shutdownNow()
        peerGroup.stop()
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
