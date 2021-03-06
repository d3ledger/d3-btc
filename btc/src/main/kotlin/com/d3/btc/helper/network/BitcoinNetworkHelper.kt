/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.helper.network

import com.d3.btc.peer.SharedPeerGroup
import mu.KLogging
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.Context
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.store.LevelDBBlockStore
import org.bitcoinj.wallet.Wallet
import java.io.File

private val logger = KLogging().logger

/**
 * Starts bitcoin blockchain downloading process
 */
fun startChainDownload(peerGroup: SharedPeerGroup) {
    logger.info("Start blockchain download")
    peerGroup.startAsync()
    peerGroup.awaitDownload()
    logger.info("Done downloading blockchain")
}

/**
 * Returns Bitcoin blockchain
 */
fun getBlockChain(
    wallet: Wallet,
    networkParameters: NetworkParameters,
    blockStoragePath: String
): BlockChain {
    val levelDbFolder = File(blockStoragePath)
    val blockStore = LevelDBBlockStore(Context(networkParameters), levelDbFolder)
    return BlockChain(networkParameters, wallet, blockStore)
}

/**
 * Adds listener to peer group that listens to peer connection/disconnection events
 * @param peerGroup - group of peers
 * @param onNoPeersLeft - function that is called, when no peers left in a given peer group
 * @param onNewPeerConnected - function that is called, when new peer appears
 */
fun addPeerConnectionStatusListener(
    peerGroup: PeerGroup,
    onNoPeersLeft: () -> Unit,
    onNewPeerConnected: () -> Unit
) {
    peerGroup.addDisconnectedEventListener { _, peerCount ->
        //If no peers left
        if (peerCount == 0) {
            logger.warn { "Out of peers" }
            onNoPeersLeft()
        }
    }
    // If new peer connected
    peerGroup.addConnectedEventListener { _, _ -> onNewPeerConnected() }
}
