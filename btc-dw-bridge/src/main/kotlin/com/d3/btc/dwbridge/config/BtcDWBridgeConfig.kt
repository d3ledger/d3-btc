/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.dwbridge.config

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.config.extractCommaSeparatedList
import com.d3.commons.config.IrohaConfig

interface BtcDWBridgeConfig {

    // Peers must have at least this number of blocks to be used by D3
    val minBlockHeightForPeer: Int

    val expansionTriggerAccount: String

    // Account that triggers 'expansion' events
    val expansionTriggerCreatorAccountId: String

    val bitcoin: BitcoinConfig

    val iroha: IrohaConfig

    // Health check port
    val healthCheckPort: Int

    // Port for web services
    val webPort: Int

    // Bitcoin DNS seeds. Seeds are separated with comma (',') symbol
    val dnsSeedAddresses: String?

    /** Timeout for Iroha queries */
    val irohaQueryTimeoutMls: Int

    companion object {
        fun extractSeeds(btcDWBridgeConfig: BtcDWBridgeConfig): List<String> =
            extractCommaSeparatedList(btcDWBridgeConfig.dnsSeedAddresses)
    }
}
