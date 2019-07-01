package com.d3.btc.keypair.wallet

import com.d3.btc.helper.address.toEcPubKey
import com.d3.btc.keypair.KeyPairService
import com.d3.btc.wallet.safeLoad
import com.d3.btc.wallet.safeSave
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.wallet.Wallet
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Key pair service base on '.wallet' files
 */
@Component
@Profile("wallet")
class WalletKeyPairService(
    @Qualifier("keysWalletPath")
    private val keysWalletPath: String
) : KeyPairService {

    @Synchronized
    override fun exists(pubKeyHex: String): Boolean {
        val wallet = safeLoad(keysWalletPath)
        val result = wallet.issuedReceiveKeys.any { ecKey ->
            ecKey.publicKeyAsHex == pubKeyHex
        }
        return result
    }

    @Synchronized
    override fun sign(message: ByteArray, pubKeyHex: String): ByteArray? {
        val wallet = safeLoad(keysWalletPath)
        val keyPair = getKeyPair(pubKeyHex, wallet)
        if (keyPair != null) {
            return keyPair.sign(Sha256Hash.wrap(message)).encodeToDER()
        }
        return null
    }

    @Synchronized
    override fun createKeyPair(): ECKey {
        var key: DeterministicKey? = null
        val wallet = safeLoad(keysWalletPath)
        try {
            return wallet.freshReceiveKey()
        } finally {
            wallet.safeSave(keysWalletPath)
        }
    }

    /**
     * Returns key pair associated with [pubKeyHex]
     * @param pubKeyHex - hex of public key which associated private key will be returned
     * @param wallet - wallet that will be used as a source of keys
     * @return key pair or null if key doesn't exist in given [wallet]
     */
    private fun getKeyPair(pubKeyHex: String, wallet: Wallet): ECKey? {
        val ecKey = toEcPubKey(pubKeyHex)
        val keyPair = wallet.findKeyFromPubHash(ecKey.pubKeyHash)
        if (keyPair != null) {
            return keyPair
        }
        return null
    }

}