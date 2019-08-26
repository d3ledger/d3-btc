/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.config

import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialRawConfig

const val BTC_WITHDRAWAL_SERVICE_NAME = "btc-withdrawal"

interface BtcWithdrawalConfig {
    // Account that handles withdrawal events
    val withdrawalCredential: IrohaCredentialRawConfig
    // Account that stores withdrawal broadcasts
    val broadcastsCredential: IrohaCredentialRawConfig
    // Account that stores Bitcoin transaction signatures
    val signatureCollectorCredential: IrohaCredentialRawConfig
    // Account that is used to deal with registered accounts
    val registrationCredential: IrohaCredentialRawConfig
    //Account that stores withdrawal consensus data
    val btcConsensusCredential: IrohaCredentialRawConfig
    // Account that stores created addresses
    val mstRegistrationAccount: String
    // Account that stores change addresses
    val changeAddressesStorageAccount: String
    // Iroha configurations
    val iroha: IrohaConfig
    // RabbitMQ queue that is used for listening to Iroha blocks
    val irohaBlockQueue: String
    // Path to wallet that stores transfers(UTXO)
    val btcTransfersWalletPath: String
    // Path to wallet that stores keys
    val btcKeysWalletPath: String
    // Account that is used to store created Bitcoin transactions
    val txStorageAccount: String
    // Account that is used to store used UTXO
    val utxoStorageAccount: String
    // Account that collects withdrawal fees
    val withdrawalBillingAccount: String
}
