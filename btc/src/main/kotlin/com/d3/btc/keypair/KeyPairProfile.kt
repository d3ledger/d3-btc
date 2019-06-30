package com.d3.btc.keypair

import mu.KLogging

private val logger = KLogging().logger
private const val KEY_PROVIDER_ENV = "KEY_PROVIDER"
private const val DEFAULT_KEY_PROVIDER = "wallet"

/**
 * Returns key provider profile from env variables
 */
fun getKeyProviderProfile(): String {
    var profile = System.getenv(KEY_PROVIDER_ENV)
    if (profile == null) {
        logger.warn { "No key provider profile set. Using default '$DEFAULT_KEY_PROVIDER' profile" }
        profile = DEFAULT_KEY_PROVIDER
    }
    return profile
}