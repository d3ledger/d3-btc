/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.deposit.config

import com.d3.btc.config.BitcoinConfig
import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialConfig
import com.d3.commons.config.IrohaCredentialRawConfig

const val BTC_DEPOSIT_SERVICE_NAME = "btc-deposit"

/** Configuration of Bitcoin deposit */
interface BtcDepositConfig {

    val iroha: IrohaConfig

    val notaryCredential: IrohaCredentialRawConfig

    val registrationAccount: String

    val btcTransferWalletPath: String

    val mstRegistrationAccount: String

    val changeAddressesStorageAccount: String
}
