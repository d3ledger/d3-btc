package com.d3.btc.keypair.securosys

import com.securosys.primus.jce.PrimusCertificate
import com.securosys.primus.jce.PrimusProvider
import mu.KLogging
import java.security.*
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec

private const val SIGN_ALGORITHM = "NONEwithECDSA"
private const val CURVE_TYPE = "secp256k1"
private const val KEY_PAIR_TYPE = "EC"
private val KEY_STORE_PASSWORD = "".toCharArray()

private val logger = KLogging().logger

/**
 * Signs message with given keypair
 * @param message - message to sign
 * @param keyPair - keypair that is used to sign data
 * @return signature in byte array
 */
fun sign(message: ByteArray, keyPair: KeyPair): ByteArray {
    val signature = Signature.getInstance(SIGN_ALGORITHM, PrimusProvider.getProviderName())
    signature.initSign(keyPair.private)
    signature.update(message)
    return signature.sign()
}

/**
 * Saves key
 * @param keyName - name of key to store
 * @param keyPair - key pair to store
 */
fun persistKeyPair(keyName: String, keyPair: KeyPair) {
    logger.info("Persist key $keyName")
    val keyStore =
        KeyStore.getInstance(PrimusProvider.getKeyStoreTypeName(), PrimusProvider.getProviderName())
    keyStore.load(null)
    keyStore.setKeyEntry(
        keyName.toLowerCase(),
        keyPair.private,
        KEY_STORE_PASSWORD,
        arrayOf<Certificate>(PrimusCertificate(keyPair.public))
    )
}

/**
 * Loads key pair from storage
 * @param keyName - name of key to load
 * @return key pair associated with given [keyName] or null
 */
fun loadKeyPair(keyName: String): KeyPair? {
    val lowerCaseKeyName=keyName.toLowerCase()
    val keyStore =
        KeyStore.getInstance(PrimusProvider.getKeyStoreTypeName(), PrimusProvider.getProviderName())
    keyStore.load(null)
    if (!keyStore.containsAlias(lowerCaseKeyName)) {
        logger.warn("Key $keyName doesn't exist")
        return null
    }
    val privateKey = keyStore.getKey(lowerCaseKeyName, KEY_STORE_PASSWORD) as PrivateKey
    val publicKey = keyStore.getCertificate(lowerCaseKeyName).publicKey
    return KeyPair(publicKey, privateKey)
}

/**
 * Creates key generator
 * @return key generator
 */
fun createGenerator(): KeyPairGenerator {
    val keyPairGenerator = KeyPairGenerator.getInstance(KEY_PAIR_TYPE, PrimusProvider.getProviderName())
    val ecParam = ECGenParameterSpec(CURVE_TYPE)
    keyPairGenerator.initialize(ecParam)
    return keyPairGenerator
}