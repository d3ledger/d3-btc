/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.model

import com.d3.commons.util.GsonInstance
import com.d3.commons.util.irohaEscape

private val gson = GsonInstance.get()

data class BtcAddress(val address: String, val info: AddressInfo)

/**
 * Data class that holds information about address
 * @param irohaClient - address owner Iroha client id
 * @param notaryKeys - keys that were used to create this address
 * @param nodeId - id of node that created this address
 * @param generationTime - time of address generation
 */
data class AddressInfo(
    val irohaClient: String?,
    val notaryKeys: List<String>,
    val nodeId: String,
    val generationTime: Long?
) {

    fun toJson() = gson.toJson(this)!!

    companion object {

        fun fromJson(json: String) = gson.fromJson(json, AddressInfo::class.java)!!

        fun createFreeAddressInfo(notaryKeys: List<String>, nodeId: String, generationTime: Long) =
            AddressInfo(null, notaryKeys, nodeId, generationTime)

        fun createChangeAddressInfo(
            notaryKeys: List<String>,
            nodeId: String,
            generationTime: Long
        ) = AddressInfo(null, notaryKeys, nodeId, generationTime)
    }
}
