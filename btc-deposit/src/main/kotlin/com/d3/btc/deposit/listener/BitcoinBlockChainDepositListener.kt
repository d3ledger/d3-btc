/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.listener

import com.d3.btc.deposit.handler.BtcDepositTxHandler
import com.d3.btc.storage.BtcAddressStorage
import com.d3.commons.sidechain.SideChainEvent
import io.reactivex.subjects.PublishSubject
import mu.KLogging
import org.bitcoinj.core.Block
import org.bitcoinj.core.FilteredBlock
import org.bitcoinj.core.Peer
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener
import java.util.concurrent.ExecutorService

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Listener of Bitcoin blockchain events.
 * @param btcEventsSource - observable that is used to publish Bitcoin deposit events
 * @param confidenceListenerExecutorService - executor service that is used to execute 'confidence change' events
 * @param btcAddressStorage - in-memory storage of Bitcoin addresses
 * @param onTxSave - function that is called to save transaction
 */
class BitcoinBlockChainDepositListener(
    private val btcEventsSource: PublishSubject<SideChainEvent.PrimaryBlockChainEvent>,
    private val confidenceListenerExecutorService: ExecutorService,
    private val confidenceLevel: Int,
    private val btcAddressStorage: BtcAddressStorage,
    private val onTxSave: () -> Unit
) : BlocksDownloadedEventListener {

    private val processedBlocks = HashSet<String>()

    override fun onBlocksDownloaded(
        peer: Peer?,
        block: Block,
        filteredBlock: FilteredBlock?,
        blocksLeft: Int
    ) {
        if (block.time.time < System.currentTimeMillis() - DAY_MILLIS) {
            //We cannot handle too old blocks due to Iroha time restrictions.
            return
        } else if (processedBlocks.contains(block.hashAsString)) {
            /*
            Sometimes Bitcoin blockchain misbehaves. It can see duplicated blocks.
            Simple workaround - store previously seen blocks.
            */
            //TODO remove this check after Iroha "replay attack" fix
            logger.warn { "Block ${block.hashAsString} has been already processed" }
            return
        }
        processedBlocks.add(block.hashAsString)
        val receivedCoinsListener =
            BitcoinTransactionListener(
                btcAddressStorage,
                confidenceLevel,
                confidenceListenerExecutorService,
                BtcDepositTxHandler(btcAddressStorage, btcEventsSource, onTxSave)
            )
        block.transactions?.forEach { tx ->
            receivedCoinsListener.onTransaction(
                tx,
                block.time
            )
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
