package com.d3.btc.keypair

import org.bitcoinj.core.ECKey

interface KeyPairService {

    /**
     * Signs given [message] with private key named [pubKeyHex]
     * @param message - message to sign
     * @param pubKeyHex - hex of public key, which corresponding private key will be used to sign [message]
     * @return signature in byte array or null if private key associated with [pubKeyHex] doesn't exist
     */
    fun sign(message: ByteArray, pubKeyHex: String): ByteArray?

    /**
     * Creates key pair
     * @return  keypair
     */
    fun createKeyPair(): ECKey

    /**
     * Cheks if public key exists
     * @param pubKeyHex - hex of public key that will checked
     * @return true if exists
     */
    fun exists(pubKeyHex: String): Boolean
}