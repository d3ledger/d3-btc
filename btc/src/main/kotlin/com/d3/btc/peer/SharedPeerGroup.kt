/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.peer

import com.d3.btc.helper.network.getBlockChain
import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.WalletInitializer
import com.google.common.util.concurrent.ListenableFuture
import mu.KLogging
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.Wallet
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * This is a peer group implementation that can be used in multiple services simultaneously with no fear of getting exception while calling 'startAsync()' or 'stopAsync()' twice
 */
@Component
class SharedPeerGroup(
    btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val wallet: Wallet,
    sharedPeerGroupConfig: SharedPeerGroupConfig,
    private val walletInitializer: WalletInitializer
) :
    PeerGroup(
        btcNetworkConfigProvider.getConfig(),
        getBlockChain(wallet, btcNetworkConfigProvider.getConfig(), sharedPeerGroupConfig.blockStoragePath)
    ) {

    init {
        // Add peers
        sharedPeerGroupConfig.hosts.forEach { host ->
            this.addAddress(InetAddress.getByName(host))
            logger.info { "$host was added to peer group" }
        }
        // Add peer discovery
        if (sharedPeerGroupConfig.dnsSeeds.isNotEmpty()) {
            logger.info("Peer discovery has been configured. DNS seeds are ${sharedPeerGroupConfig.dnsSeeds}.")
            this.addPeerDiscovery(
                DnsDiscovery(sharedPeerGroupConfig.dnsSeeds.toTypedArray(), btcNetworkConfigProvider.getConfig())
            )
        }
        // Filter peers
        this.addConnectedEventListener { peer: Peer, _ ->
            val minPeerChainHeight = max(wallet.lastBlockSeenHeight, sharedPeerGroupConfig.minBlockHeightForPeer)
            if (peer.bestHeight < minPeerChainHeight) {
                logger.warn("Peer $peer hasn't got enough blockchain data. Need at least $minPeerChainHeight while $peer has just ${peer.bestHeight} ")
                peer.close()
                logger.warn("Peer $peer has been closed.")
            }
        }
    }

    private val downloadLock = CountDownLatch(1)
    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()

    /**
     * Returns block by hash
     * @param blockHash - hash of block
     * @return block with given hash if exists and null otherwise
     */
    fun getBlock(blockHash: Sha256Hash) = chain?.blockStore?.get(blockHash)

    override fun startAsync(): ListenableFuture<*>? {
        if (started.compareAndSet(false, true)) {
            // Initialize wallet only once
            walletInitializer.initializeWallet(wallet)
            val result = super.startAsync()
            // Start downloading blockchain in a separate thread
            Thread {
                super.downloadBlockChain()
                downloadLock.countDown()
            }.start()
            return result
        }
        logger.warn { "Cannot start peer group, because it was started previously." }
        return null
    }

    /**
     * Blocks thread until blockchain is entirely downloaded
     */
    fun awaitDownload() {
        downloadLock.await()
    }

    override fun stopAsync(): ListenableFuture<*>? {
        if (stopped.compareAndSet(false, true)) {
            // Close block store if possible
            chain?.blockStore?.close()
            return super.stopAsync()
        }
        logger.warn { "Cannot stop peer group, because it was stopped previously" }
        return null
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

/**
 * Shared peer group configuration class
 */
data class SharedPeerGroupConfig(
    val blockStoragePath: String,
    val hosts: List<String>,
    val dnsSeeds: List<String>,
    val minBlockHeightForPeer: Int
)
