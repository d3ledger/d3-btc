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
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This is a peer group implementation that can be used in multiple services simultaneously with no fear of getting exception while calling 'startAsync()' or 'stopAsync()' twice
 */
@Component
class SharedPeerGroup(
    btcNetworkConfigProvider: BtcNetworkConfigProvider,
    private val wallet: Wallet,
    @Qualifier("blockStoragePath")
    blockStoragePath: String,
    @Qualifier("btcHosts")
    hosts: List<String>,
    @Qualifier("dnsSeed")
    private val dnsSeed: String?,
    private val walletInitializer: WalletInitializer
) :
    PeerGroup(
        btcNetworkConfigProvider.getConfig(),
        getBlockChain(wallet, btcNetworkConfigProvider.getConfig(), blockStoragePath)
    ) {

    init {
        hosts.forEach { host ->
            this.addAddress(InetAddress.getByName(host))
            logger.info { "$host was added to peer group" }
        }
        dnsSeed?.let {
            logger.info("Peer discovery has been configured. DNS seed is $it")
            this.addPeerDiscovery(
                DnsDiscovery.DnsSeedDiscovery(btcNetworkConfigProvider.getConfig(), it)
            )
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
