/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.generation.config

import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialRawConfig

interface BtcAddressGenerationConfig {
    /*
    Account for triggering.
    Triggering this account means starting BTC addresses generation
    */
    val pubKeyTriggerAccount: String

    val notaryAccount: String

    //Iroha config
    val iroha: IrohaConfig

    //TODO the only purpose of this account is creating PeerListProvider. This account must be removed from config.
    //Account that is used to register BTC addresses
    val registrationAccount: IrohaCredentialRawConfig

    //Account that is used to register BTC addresses in MST fashion
    val mstRegistrationAccount: IrohaCredentialRawConfig

    //Account that stores all registered notaries
    val notaryListStorageAccount: String

    //Account that stores change addresses
    val changeAddressesStorageAccount: String

    //Account that sets registered notaries
    val notaryListSetterAccount: String

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
}
