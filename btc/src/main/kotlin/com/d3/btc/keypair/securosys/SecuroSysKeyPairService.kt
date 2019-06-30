package com.d3.btc.keypair.securosys

import com.d3.btc.keypair.KeyPairService
import com.securosys.primus.jce.PrimusConfiguration
import com.securosys.primus.jce.PrimusKeyAttributes
import com.securosys.primus.jce.PrimusLogin
import com.securosys.primus.jce.PrimusProvider
import jp.co.soramitsu.iroha.java.Utils
import mu.KLogging
import org.bitcoinj.core.ECKey
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import sun.security.ec.ECPublicKeyImpl


/**
 * Cloud HSM 'SecuroSys' key pair service
 */
@Component
@Profile("securosys")
class SecuroSysKeyPairService(
    private val securoSysConfig: SecuroSysConfig
) : KeyPairService {

    init {
        logger.info("Install 'Primus' provider")
        PrimusProvider.installProvider()
        setAttributes()
    }

    override fun exists(pubKeyHex: String): Boolean {
        return loginAndApply { loadKeyPair(pubKeyHex) != null }
    }

    override fun sign(message: ByteArray, pubKeyHex: String): ByteArray? {
        return loginAndApply {
            logger.info("Start signing message ${Utils.toHex(message)} with public key $pubKeyHex")
            val keyPair = loadKeyPair(pubKeyHex)
            if (keyPair != null) {
                sign(message, keyPair)
            } else {
                logger.warn("Cannot sign message ${Utils.toHex(message)} with public key $pubKeyHex. Public key is not found.")
                null
            }
        }
    }

    override fun createKeyPair(): ECKey {
        return loginAndApply {
            val keyPairGenerator = createGenerator()
            val keyPair = keyPairGenerator.generateKeyPair()
            val ecPublicKey = ECPublicKeyImpl(keyPair.public.encoded)
            val pubKeyHex = Utils.toHex(ecPublicKey.encodedPublicValue)
            logger.info("New key pair with public key $pubKeyHex has been created.")
            persistKeyPair(pubKeyHex, keyPair)
            logger.info("Key pair with public key $pubKeyHex has been saved.")
            ECKey.fromPublicOnly(ecPublicKey.encodedPublicValue)
        }
    }

    /**
     * Logs in cloud HSM, runs given function and then logs out
     * @param apply - function to apply after successful log attempt
     * @return value that returns [apply] function
     */
    @Synchronized
    private fun <T> loginAndApply(apply: () -> T): T {
        try {
            PrimusConfiguration.setHsmHostAndPortAndUser(
                securoSysConfig.host,
                securoSysConfig.port,
                securoSysConfig.username
            )
            logger.info("HSM log in attempt")
            PrimusLogin.login(securoSysConfig.username, securoSysConfig.password.toCharArray())
            logger.info("HSM log in success")
            return apply()
        } finally {
            PrimusLogin.logout()
            logger.info("HSM logout")
        }
    }

    /**
     * Sets default attributes
     */
    private fun setAttributes() {
        // Private keys MUST NOT be extractable
        PrimusKeyAttributes.setKeyAccessFlag(PrimusKeyAttributes.ACCESS_EXTRACTABLE, false)
        // Sensitive data MUST NOT be accessible
        PrimusKeyAttributes.setKeyAccessFlag(PrimusKeyAttributes.ACCESS_SENSITIVE, false)
        // Keys MUST NOT be 'deletable'
        PrimusKeyAttributes.setKeyAccessFlag(PrimusKeyAttributes.ACCESS_INDESTRUCTIBLE, true);
    }

    companion object : KLogging()
}