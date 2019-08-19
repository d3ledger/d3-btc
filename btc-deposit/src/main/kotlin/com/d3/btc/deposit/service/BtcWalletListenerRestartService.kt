/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.service

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.deposit.handler.BtcDepositTxHandler
import com.d3.btc.deposit.listener.BtcConfirmedTxListener
import com.d3.btc.peer.SharedPeerGroup
import com.d3.btc.storage.BtcAddressStorage
import com.d3.commons.sidechain.SideChainEvent
import com.github.kittinunf.result.Result
import io.reactivex.subjects.PublishSubject
import mu.KLogging
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService

/**
 * Service that is used to restart unconfirmed transactions confidence listeners
 */
@Component
class BtcWalletListenerRestartService(
    private val btcAddressStorage: BtcAddressStorage,
    private val bitcoinConfig: BitcoinConfig,
    private val confidenceListenerExecutorService: ExecutorService,
    private val peerGroup: SharedPeerGroup,
    private val btcEventsSource: PublishSubject<SideChainEvent.PrimaryBlockChainEvent>
) {

    /**
     * Restarts unconfirmed transactions confidence listeners
     * @param transferWallet - wallet that stores all the D3 Bitcoin transactions. Used to get unconfirmed transactions
     * @param onTxSave - function that is called to save transaction in wallet
     */
    fun restartTransactionListeners(
        transferWallet: Wallet,
        onTxSave: () -> Unit
    ): Result<Unit, Exception> {
        return Result.of {
            transferWallet.walletTransactions
                .filter { walletTransaction ->
                    val txDepth = walletTransaction.transaction.confidence.depthInBlocks
                    txDepth < bitcoinConfig.confidenceLevel
                }
                .map { walletTransaction ->
                    walletTransaction.transaction
                }
                .forEach { unconfirmedTx ->
                    logger.info { "Got unconfirmed transaction ${unconfirmedTx.hashAsString}. Try to restart listener." }
                    restartUnconfirmedTxListener(unconfirmedTx, btcAddressStorage, onTxSave)
                }
        }
    }

    /**
     * Restarts unconfirmed transaction confidence listener
     * @param unconfirmedTx - transaction that needs confidence listener restart
     * @param btcAddressStorage - in-memory storage of Bitcoin addresses
     * @param onTxSave - function that is called to save transaction in wallet
     */
    private fun restartUnconfirmedTxListener(
        unconfirmedTx: Transaction,
        btcAddressStorage: BtcAddressStorage,
        onTxSave: () -> Unit
    ) {
        // Get tx block hash
        unconfirmedTx.appearsInHashes?.let { appearsInHashes ->
            appearsInHashes.keys.firstOrNull()?.let { blockHash ->
                // Get tx block by hash
                peerGroup.getBlock(blockHash)?.let { block ->
                    // Create listener
                    unconfirmedTx.confidence.addEventListener(
                        confidenceListenerExecutorService,
                        createListener(unconfirmedTx, block, btcAddressStorage, onTxSave)
                    )
                    logger.info("Listener for ${unconfirmedTx.hashAsString} has been restarted")
                }
            }
        }
    }

    /**
     * Creates unconfirmed transaction listener
     * @param unconfirmedTx - unconfirmed transaction which confidence will be listenable
     * @param block - block of transaction
     * @param btcAddressStorage - in-memory storage of Bitcoin addresses
     * @param onTxSave - function that is called to save transaction in wallet
     * @return restarted listener
     */
    private fun createListener(
        unconfirmedTx: Transaction,
        block: StoredBlock,
        btcAddressStorage: BtcAddressStorage,
        onTxSave: () -> Unit
    )
            : BtcConfirmedTxListener {
        return BtcConfirmedTxListener(
            bitcoinConfig.confidenceLevel,
            unconfirmedTx,
            block.header.time,
            BtcDepositTxHandler(
                btcAddressStorage,
                btcEventsSource,
                onTxSave
            )::handleTx
        )
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
