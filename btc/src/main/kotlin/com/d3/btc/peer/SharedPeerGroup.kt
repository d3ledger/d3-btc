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
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This is a peer group implementation that can be used in multiple services simultaneously with no fear of get exception while calling 'startAsync()' or 'stopAsync()' twice
 */
@Component
class SharedPeerGroup(
    @Autowired btcNetworkConfigProvider: BtcNetworkConfigProvider,
    @Autowired private val wallet: Wallet,
    @Qualifier("blockStoragePath")
    @Autowired blockStoragePath: String,
    @Qualifier("btcHosts")
    @Autowired hosts: List<String>,
    @Autowired private val walletInitializer: WalletInitializer
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
    }

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
            return super.startAsync()
        }
        logger.warn { "Cannot start peer group, because it was started previously." }
        return null
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

    override fun downloadBlockChain() {
        if (started.get()) {
            super.downloadBlockChain()
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
