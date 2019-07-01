package com.d3.btc.keypair.securosys

/**
 * Configurations of SecuroSys HSM
 */
interface SecuroSysConfig {
    // Host of SecuroSys
    val host: String
    // Port of SecuroSys
    val port: Int
    // SecuroSys username
    val username: String
    // SecuroSys password
    val password: String
}