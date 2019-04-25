package com.d3.btc.deposit.config

import com.d3.commons.config.BitcoinConfig
import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialConfig

const val BTC_DEPOSIT_SERVICE_NAME="btc-deposit"

/** Configuration of Bitcoin deposit */
interface BtcDepositConfig {
    /** Web port for health checks */
    val healthCheckPort: Int

    val iroha: IrohaConfig

    val bitcoin: BitcoinConfig

    val notaryCredential: IrohaCredentialConfig

    val registrationAccount: String

    val btcTransferWalletPath: String

    val mstRegistrationAccount: String

    val changeAddressesStorageAccount: String
}
