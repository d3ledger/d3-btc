/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.registration.config

import com.d3.commons.config.IrohaConfig
import com.d3.commons.config.IrohaCredentialRawConfig

/**
 * Interface represents configs for registration service for cfg4k
 */
interface BtcRegistrationConfig {
    /** Port of registration service */
    val port: Int

    /** Iroha account for btc account register */
    val registrationCredential: IrohaCredentialRawConfig

    /** Iroha account for btc account register in MST fashion*/
    val mstRegistrationAccount: String

    /** Notary account that stores addresses in details*/
    val notaryAccount: String

    /** Iroha configuration */
    val iroha: IrohaConfig

    /** Node id */
    val nodeId: String

    /** Timeout for Iroha queries */
    val irohaQueryTimeoutMls: Int

    //Account that stores free Bitcoin addresses
    val freeAddressesStorageAccount: String
}
