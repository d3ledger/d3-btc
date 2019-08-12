/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.init

import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.handler.SetAccountDetailHandler
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
import com.d3.btc.withdrawal.handler.NewTransferHandler
import com.d3.chainadapter.client.RMQConfig
import com.d3.chainadapter.client.ReliableIrohaChainListener
import com.d3.commons.sidechain.iroha.FEE_DESCRIPTION
import com.d3.commons.sidechain.iroha.util.getSetDetailCommands
import com.d3.commons.sidechain.iroha.util.getWithdrawalTransactions
import com.d3.commons.util.createPrettySingleThreadPool
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMap
import com.github.kittinunf.result.map
import iroha.protocol.BlockOuterClass
import iroha.protocol.Commands
import iroha.protocol.TransactionOuterClass
import mu.KLogging
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.Closeable
import java.math.BigDecimal

private const val ONE_DAY_MILLIS = 1000 * 60 * 60 * 24

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
    private val newTransferHandler: NewTransferHandler,
    @Qualifier("withdrawalHandlers")
    private val accountDetailHandlers: List<SetAccountDetailHandler>,
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

    /**
     * Handles Iroha blocks
     * @param block - Iroha block
     */
    private fun handleIrohaBlock(block: BlockOuterClass.Block) {
        if ((System.currentTimeMillis() - block.blockV1.payload.createdTime) > ONE_DAY_MILLIS) {
            logger.warn("Ignore old block ${block.blockV1.payload.height}")
            return
        }
        // Expand the withdrawal service if there is a need to do so
        withdrawalServiceExpansion.expand(block)
        // Handle transfer commands
        getWithdrawalTransactions(
            block,
            btcWithdrawalConfig.withdrawalCredential.accountId
        ).forEach { transaction ->
            getWithdrawalCommand(transaction)?.let { withdrawalCommand ->
                newTransferHandler.handleTransferCommand(
                    withdrawalCommand.command.transferAsset,
                    withdrawalCommand.feeInBtc,
                    block.blockV1.payload.createdTime
                )
            }
        }
        // Handle other commands
        getSetDetailCommands(block).map { it.setAccountDetail }
            .forEach { setAccountDetailCommand ->
                accountDetailHandlers.forEach { handler ->
                    handler.handleFiltered(setAccountDetailCommand)
                }
            }
    }

    // Calls apply and then acknowledges it safely
    private fun safeApplyAck(apply: () -> Unit, ack: () -> Unit) {
        try {
            apply()
        } catch (e: Exception) {
            logger.error("Cannot apply", e)
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

    /**
     * Returns withdrawal command and fee from transaction
     * @param transaction - transaction that will be used to get commands from
     * @return withdrawal command and fee from transaction or null if no appropriate commands were found
     */
    private fun getWithdrawalCommand(transaction: TransactionOuterClass.Transaction): WithdrawalCommandWithFee? {
        var withdrawalCommand: Commands.Command? = null
        var feeCommand: Commands.Command? = null
        transaction.payload.reducedPayload.commandsList.forEach { command ->
            if (command.hasTransferAsset()) {
                val transferCommand = command.transferAsset
                if (transferCommand.description == FEE_DESCRIPTION) {
                    // If description equals to 'withdrawal fee', then it's a fee command
                    feeCommand = command
                } else {
                    withdrawalCommand = command
                }
            }
        }
        // If no withdrawal command was found
        if (withdrawalCommand == null) {
            return null
        }
        val feeInBtc = if (feeCommand == null) {
            // If no fee command was found
            BigDecimal.ZERO
        } else {
            feeCommand!!.transferAsset.amount.toBigDecimal()
        }
        return WithdrawalCommandWithFee(withdrawalCommand!!, feeInBtc)
    }

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

/**
 * Data class that stores withdrawal command and withdrawal fee value in Bitcoin
 */
private data class WithdrawalCommandWithFee(val command: Commands.Command, val feeInBtc: BigDecimal)