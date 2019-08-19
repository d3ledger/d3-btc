/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.handler

import com.d3.btc.storage.BtcAddressStorage
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.currency.satToBtc
import com.d3.commons.sidechain.SideChainEvent
import io.reactivex.subjects.PublishSubject
import mu.KLogging
import org.bitcoinj.core.Transaction
import java.math.BigInteger
import java.util.*

private const val BTC_ASSET_NAME = "btc"
private const val BTC_ASSET_DOMAIN = "bitcoin"
private const val TWO_HOURS_MILLIS = 2 * 60 * 60 * 1000L

/**
 * Handler of Bitcoin deposit transactions
 * @param btcAddressStorage - in-memory storage of Bitcoin addresses
 * @param btcEventsSource - source of Bitcoin deposit events
 * @param onTxSave - function that is called to save transaction in wallet
 */
class BtcDepositTxHandler(
    private val btcAddressStorage: BtcAddressStorage,
    private val btcEventsSource: PublishSubject<SideChainEvent.PrimaryBlockChainEvent>,
    private val onTxSave: () -> Unit
) {

    /**
     * Handles deposit transaction
     * @param tx - Bitcoin deposit transaction
     * @param blockTime - time of block where [tx] appeared for the first time. This time is used in MST
     */
    fun handleTx(tx: Transaction, blockTime: Date) {
        tx.outputs.forEach { output ->
            val txBtcAddress = outPutToBase58Address(output)
            logger.info { "Tx ${tx.hashAsString} has output address $txBtcAddress" }
            if (btcAddressStorage.isOurClient(txBtcAddress)) {
                val clientAccountId = btcAddressStorage.getClientAccountId(txBtcAddress)!!
                logger.info("Handle our client address $txBtcAddress")
                val btcValue = satToBtc(output.value.value)
                val event = SideChainEvent.PrimaryBlockChainEvent.ChainAnchoredOnPrimaryChainDeposit(
                    hash = tx.hashAsString,
                    /*
                    Due to Iroha time restrictions, tx time must be in range [current time - 1 day; current time + 5 min],
                    while Bitcoin block time must be in range [median time of last 11 blocks; network time + 2 hours].
                    Given these restrictions, block time may be more than 5 minutes ahead of current time.
                    Subtracting 2 hours is just a simple workaround of this problem.
                    */
                    time = BigInteger.valueOf(blockTime.time - TWO_HOURS_MILLIS),
                    user = clientAccountId,
                    asset = "$BTC_ASSET_NAME#$BTC_ASSET_DOMAIN",
                    amount = btcValue.toPlainString(),
                    from = tx.hashAsString
                )
                logger.info {
                    "BTC deposit event(tx ${tx.hashAsString}, amount ${btcValue.toPlainString()}) was created. " +
                            "Related client is $clientAccountId. "
                }
                btcEventsSource.onNext(event)
                //TODO better call this function after event consumption.
                onTxSave()
            } else if (btcAddressStorage.isChangeAddress(txBtcAddress)) {
                logger.info("Handle change address $txBtcAddress")
                onTxSave()
            }
        }
    }

    /**
     * Logger
     */
    companion object : KLogging()
}
