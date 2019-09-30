/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.listener

import com.d3.btc.deposit.handler.BtcDepositTxHandler
import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.helper.address.outPutToBase58Address
import mu.KLogging
import org.bitcoinj.core.Transaction
import java.util.*
import java.util.concurrent.ExecutorService

/**
 * Listener that listens to interested Bitcoin transactions
 * @param btcAddressStorage - in-memory storage of Bitcoin addresses
 * @param confidenceLevel - level of confidence aka depth of transaction. Recommend value is 6
 * @param confidenceListenerExecutor - executor that will be used to execute confidence listener logic
 * @param btcDepositTxHandler - handles btc deposit transactions deposit('unspent' occurrence)
 */
class BitcoinTransactionListener(
    private val btcAddressStorage: BtcAddressStorage,
    private val confidenceLevel: Int,
    private val confidenceListenerExecutor: ExecutorService,
    private val btcDepositTxHandler: BtcDepositTxHandler
) {
    fun onTransaction(tx: Transaction, blockTime: Date) {
        if (!hasOurAddresses(tx)) {
            return
        }
        if (tx.confidence.depthInBlocks >= confidenceLevel) {
            //If tx has desired depth, we call function that handles it
            logger.info { "BTC was received. Tx: ${tx.hashAsString}" }
            btcDepositTxHandler.handleTx(tx, blockTime)
        } else {
            /*
            Otherwise we will register listener, that listens to tx depth updates.
            Handling function will be called, if tx depth hits desired value
            */
            logger.info { "BTC was received, but it's not confirmed yet. Tx: ${tx.hashAsString}" }
            tx.confidence.addEventListener(
                confidenceListenerExecutor,
                BtcConfirmedTxListener(
                    confidenceLevel,
                    tx,
                    blockTime,
                    btcDepositTxHandler::handleTx
                )
            )
        }
    }

    //Checks if tx contains our addresses in its outputs
    private fun hasOurAddresses(tx: Transaction) = tx.outputs.map { out -> outPutToBase58Address(out) }
        .any { address -> btcAddressStorage.isWatchedAddress(address) }

    /**
     * Logger
     */
    companion object : KLogging()
}
