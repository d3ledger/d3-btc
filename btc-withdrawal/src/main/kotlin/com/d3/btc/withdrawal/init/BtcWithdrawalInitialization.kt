/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.init

import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.handler.NewBtcClientRegistrationHandler
import com.d3.btc.healthcheck.HealthyService
import com.d3.btc.helper.network.addPeerConnectionStatusListener
import com.d3.btc.helper.network.startChainDownload
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.provider.BtcChangeAddressProvider
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.checkWalletNetwork
import com.d3.btc.withdrawal.config.BTC_WITHDRAWAL_SERVICE_NAME
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.btc.withdrawal.expansion.WithdrawalServiceExpansion
import com.d3.btc.withdrawal.handler.*
import com.d3.commons.config.RMQConfig
import com.d3.commons.sidechain.iroha.BTC_CONSENSUS_DOMAIN
import com.d3.commons.sidechain.iroha.BTC_SIGN_COLLECT_DOMAIN
import com.d3.commons.sidechain.iroha.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.sidechain.iroha.util.getTransferCommands
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.BlockOuterClass
import iroha.protocol.Commands
import mu.KLogging
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component
import java.io.Closeable
import java.io.File

/*
    Class that initiates listeners that will be used to handle Bitcoin withdrawal logic
 */
@Component
class BtcWithdrawalInitialization(
    private val btcWithdrawalConfig: BtcWithdrawalConfig,
    private val peerGroup: SharedPeerGroup,
    private val transferWallet: Wallet,
    private val btcChangeAddressProvider: BtcChangeAddressProvider,
    private val btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val newSignatureEventHandler: NewSignatureEventHandler,
    private val newBtcClientRegistrationHandler: NewBtcClientRegistrationHandler,
    private val newTransferHandler: NewTransferHandler,
    private val newChangeAddressHandler: NewChangeAddressHandler,
    private val newConsensusDataHandler: NewConsensusDataHandler,
    private val newTransactionCreatedHandler: NewTransactionCreatedHandler,
    private val withdrawalServiceExpansion: WithdrawalServiceExpansion,
    rmqConfig: RMQConfig
) : HealthyService(), Closeable {

    private val irohaChainListener = ReliableIrohaChainListener(
        rmqConfig, btcWithdrawalConfig.irohaBlockQueue,
        consumerExecutorService = createPrettySingleThreadPool(
            BTC_WITHDRAWAL_SERVICE_NAME,
            "rmq-consumer"
        ),
        autoAck = false
    )

    fun init(): Result<Unit, Exception> {
        //TODO create a fee rate updating mechanism
        //Set minimum fee rate
        CurrentFeeRate.setMinimum()
        // Check wallet network
        return transferWallet.checkWalletNetwork(btcNetworkConfigProvider.getConfig()).map {
            waitChangeAddresses()
        }.flatMap {
            initBtcBlockChain()
        }.flatMap {
            initWithdrawalTransferListener()
        }
    }

    /**
     * Initiates listener that listens to withdrawal events in Iroha
     * @return result of initiation process
     */
    private fun initWithdrawalTransferListener(
    ): Result<Unit, Exception> {
        return irohaChainListener.getBlockObservable()
            .map { observable ->
                observable.subscribe { (block, ack) ->
                    safeApplyAck({ handleIrohaBlock(block) }, { ack() })
                }
            }.flatMap {
                logger.info("Start listening RMQ Iroha blocks")
                irohaChainListener.listen()
            }
    }

    //TODO refactor handlers
    /**
     * Handles Iroha blocks
     * @param block - Iroha block
     */
    private fun handleIrohaBlock(block: BlockOuterClass.Block) {
        // Expand the withdrawal service if there is a need to do so
        withdrawalServiceExpansion.expand(block)
        // Handle transfer commands
        getTransferCommands(block).forEach { command ->
            newTransferHandler.handleTransferCommand(
                command.transferAsset,
                block.blockV1.payload.createdTime
            )
        }
        // Handle 'create new transaction' events
        getSetDetailCommands(block).filter { command -> isNewTransactionCreated(command) }
            .forEach { command ->
                newTransactionCreatedHandler.handleCreateTransactionCommand(command.setAccountDetail)
            }

        // Handle signature appearance commands
        getSetDetailCommands(block).filter { command -> isNewWithdrawalSignature(command) }
            .forEach { command ->
                newSignatureEventHandler.handleNewSignatureCommand(
                    command.setAccountDetail
                ) { transferWallet.saveToFile(File(btcWithdrawalConfig.btcTransfersWalletPath)) }
            }
        // Handle 'set new consensus' events
        getSetDetailCommands(block).filter { command -> isNewConsensus(command) }
            .forEach { command ->
                newConsensusDataHandler.handleNewConsensusCommand(command.setAccountDetail)
            }
        // Handle newly registered Bitcoin addresses. We need it to update transferWallet object.
        getSetDetailCommands(block).forEach { command ->
            newBtcClientRegistrationHandler.handleNewClientCommand(command, transferWallet)
        }

        // Handle newly generated Bitcoin change addresses. We need it to update transferWallet object.
        getSetDetailCommands(block).filter { command ->
            command.hasSetAccountDetail() &&
                    command.setAccountDetail.accountId == btcWithdrawalConfig.changeAddressesStorageAccount
        }.forEach { command ->
            newChangeAddressHandler.handleNewChangeAddress(command.setAccountDetail)
        }
    }

    // Calls apply and then acknowledges it safely
    private fun safeApplyAck(apply: () -> Unit, ack: () -> Unit) {
        try {
            apply()
        } finally {
            ack()
        }
    }

    /**
     * Starts Bitcoin block chain download process
     */
    private fun initBtcBlockChain(): Result<PeerGroup, Exception> {
        //Enables short log format for Bitcoin events
        BriefLogFormatter.init()
        return Result.of {
            startChainDownload(peerGroup)
            addPeerConnectionStatusListener(peerGroup, ::notHealthy, ::cured)
            peerGroup
        }
    }

    /**
     * Waits until change addresses are generated
     */
    private fun waitChangeAddresses() {
        while (btcChangeAddressProvider.getAllChangeAddresses().get().isEmpty()) {
            logger.warn("No change addresses have been generated yet. Wait.")
            Thread.sleep(5_000)
        }
    }

    private fun isNewWithdrawalSignature(command: Commands.Command) =
        command.hasSetAccountDetail() && command.setAccountDetail.accountId.endsWith("@$BTC_SIGN_COLLECT_DOMAIN")

    private fun isNewTransactionCreated(command: Commands.Command) =
        command.hasSetAccountDetail() && command.setAccountDetail.accountId == btcWithdrawalConfig.txStorageAccount

    private fun isNewConsensus(command: Commands.Command) =
        command.hasSetAccountDetail() && command.setAccountDetail.accountId.endsWith("@$BTC_CONSENSUS_DOMAIN")

    override fun close() {
        logger.info { "Closing Bitcoin withdrawal service" }
        irohaChainListener.close()
        peerGroup.stop()
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
