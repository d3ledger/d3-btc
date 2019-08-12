package com.d3.btc.dwbridge.monitoring.routing

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.dwbridge.monitoring.dto.AvailableSumBtc
import com.d3.btc.dwbridge.monitoring.dto.UTXOBtc
import com.d3.btc.dwbridge.monitoring.dto.UTXOSetBtc
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.currency.satToBtc
import de.nielsfalk.ktor.swagger.created
import de.nielsfalk.ktor.swagger.description
import de.nielsfalk.ktor.swagger.get
import de.nielsfalk.ktor.swagger.responds
import de.nielsfalk.ktor.swagger.version.shared.Group
import io.ktor.application.call
import io.ktor.locations.Location
import io.ktor.response.respond
import io.ktor.routing.Routing
import org.bitcoinj.wallet.Wallet

@Group("monitoring")
@Location("/monitoring/sumBtc")
class SumBtcLocation

@Group("monitoring")
@Location("/monitoring/utxo")
class UTXOBtcLocation

/**
 * Monitors available amount of BTC
 *
 * @param transferWallet - wallet with transfers
 * @param bitcoinConfig - Bitcoin config object
 */
fun Routing.availableSumBtc(transferWallet: Wallet, bitcoinConfig: BitcoinConfig) {
    get<SumBtcLocation>(
        "all"
            .description("Returns available sum of BTC")
            .responds(created<AvailableSumBtc>())
    ) {
        val confirmations = call.parameters["confirmations"]
        if (confirmations == null) {
            call.respond(getAvailableSumBtc(transferWallet, bitcoinConfig.confidenceLevel))
        } else {
            call.respond(getAvailableSumBtc(transferWallet, confirmations.toInt()))
        }
    }
}

/**
 * Monitors available UTXO set
 *
 * @param transferWallet - wallet with transfers
 * @param bitcoinConfig - Bitcoin config object
 */
fun Routing.availableUTXOSet(transferWallet: Wallet, bitcoinConfig: BitcoinConfig) {
    get<UTXOBtcLocation>(
        "all"
            .description("Returns available UTXO set")
            .responds(created<UTXOSetBtc>())
    ) {
        val confirmations = call.parameters["confirmations"]
        if (confirmations == null) {
            call.respond(getUTXOSet(transferWallet, bitcoinConfig.confidenceLevel))
        } else {
            call.respond(getUTXOSet(transferWallet, confirmations.toInt()))
        }
    }
}

/**
 * Returns available UTXO set
 * @param transferWallet - wallet with transfers
 * @param minConfirmations - minimum number of confirmations(depth in blocks)
 * @return UTXO set
 */
private fun getUTXOSet(transferWallet: Wallet, minConfirmations: Int) = UTXOSetBtc(transferWallet.unspents
    .filter { utxo -> utxo.parentTransactionDepthInBlocks >= minConfirmations }
    .map { utxo ->
        UTXOBtc(
            utxo.parentTransactionDepthInBlocks,
            satToBtc(utxo.value.value).toPlainString(),
            utxo.parentTransactionHash.toString(),
            utxo.index,
            outPutToBase58Address(utxo)
        )
    })

/**
 * Returns sum of BTC that is available to spend
 * @param transferWallet - wallet with transfers
 * @param minConfirmations - minimum number of confirmations(depth in blocks)
 * @return sum of BTC
 */
private fun getAvailableSumBtc(transferWallet: Wallet, minConfirmations: Int): AvailableSumBtc {
    var sumSat = 0L
    transferWallet.unspents
        .filter { utxo -> utxo.parentTransactionDepthInBlocks >= minConfirmations }
        .forEach { utxo -> sumSat += utxo.value.value }
    return AvailableSumBtc(satToBtc(sumSat).toPlainString())
}
