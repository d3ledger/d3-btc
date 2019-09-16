/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.config

import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialRawConfig

interface BtcAddressGenerationConfig {

    val notaryAccount: String

    //Iroha config
    val iroha: IrohaConfig

    //Path to BTC wallet file
    val btcKeysWalletPath: String

    //Account that is used to register BTC addresses
    val registrationAccount: IrohaCredentialRawConfig

    //Account that is used to register BTC addresses in MST fashion
    val mstRegistrationAccount: IrohaCredentialRawConfig

    //Account that stores change addresses
    val changeAddressesStorageAccount: String

    //Account that stores free Bitcoin addresses
    val freeAddressesStorageAccount: String

    //Port of health check service
    val healthCheckPort: Int

    //Minimum number of free addresses to keep in Iroha
    val threshold: Int

    // Node id 
    val nodeId: String

    // Account for triggering 'expansion' events
    val expansionTriggerAccount: String

    // Account that triggers 'expansion' events
    val expansionTriggerCreatorAccountId: String

    // Queue where chain-adapter saves Iroha blocks
    val irohaBlockQueue: String

    // Name of registration service account (no domain)
    val registrationServiceAccountName: String

    // Name of registration service account (no domain)
    val clientStorageAccount: String

    /** Timeout for Iroha queries */
    val irohaQueryTimeoutMls: Int
}
