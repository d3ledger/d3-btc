/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.helper

import com.d3.btc.config.BitcoinConfig
import com.d3.btc.deposit.config.BtcDepositConfig
import com.d3.btc.dwbridge.config.BtcDWBridgeConfig
import com.d3.btc.generation.config.BtcAddressGenerationConfig
import com.d3.btc.keypair.wallet.WalletConfig
import com.d3.btc.registration.config.BtcRegistrationConfig
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig
import com.d3.commons.config.loadLocalConfigs
import com.d3.commons.model.IrohaCredential
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.Wallet
import java.io.File

private const val TEST_WALLETS_FOLDER = "deploy/bitcoin/regtest/test wallets"

/**
 * Class that handles all the configuration objects
 **/
class BtcConfigHelper(
    private val accountHelper: IrohaAccountHelper
) : IrohaConfigHelper() {

    private val utxoStorageAccountCredential = accountHelper.createTesterAccount("utxo_storage")
    private val txStorageAccountCredential = accountHelper.createTesterAccount("tx_storage")

    /** Creates config for BTC multisig addresses generation
     * @param initAddresses - number of addresses that will be generated at initial phase
     * @param walletNamePostfix - postfix of wallet file name. used to keep wallets as multiple files in multisig tests.
     * @return config
     * */
    fun createBtcAddressGenerationConfig(
        initAddresses: Int
    ): BtcAddressGenerationConfig {
        val btcPkPreGenConfig =
            loadLocalConfigs(
                "btc-address-generation",
                BtcAddressGenerationConfig::class.java,
                "address_generation.properties"
            ).get()

        return object : BtcAddressGenerationConfig {
            override val expansionTriggerAccount = accountHelper.expansionTriggerAccount.accountId
            override val threshold = initAddresses
            override val nodeId = NODE_ID
            override val changeAddressesStorageAccount =
                accountHelper.changeAddressesStorageAccount.accountId
            override val healthCheckPort = btcPkPreGenConfig.healthCheckPort
            override val notaryListStorageAccount = accountHelper.notaryListStorageAccount.accountId
            override val notaryListSetterAccount = accountHelper.notaryAccount.accountId
            override val mstRegistrationAccount =
                accountHelper.createCredentialRawConfig(accountHelper.mstRegistrationAccount)
            override val pubKeyTriggerAccount = btcPkPreGenConfig.pubKeyTriggerAccount
            override val expansionTriggerCreatorAccountId = accountHelper.superuserAccount.accountId
            override val notaryAccount = accountHelper.notaryAccount.accountId
            override val iroha = createIrohaConfig()
            override val registrationAccount =
                accountHelper.createCredentialRawConfig(accountHelper.registrationAccount)
        }
    }

    /**
     * Creates wallet file config
     * @param walletNamePostfix - wallet file name postfix
     * @return config
     */
    fun createWalletConfig(walletNamePostfix: String = "test"): WalletConfig {
        return object : WalletConfig {
            override val btcKeysWalletPath = createWalletFile("keys.$walletNamePostfix")
        }
    }

    /**
     * Creates config for Bitcoin withdrawal
     * @param testName - name of the test. used to create folder for block storage and queue name
     * @return configuration
     */
    fun createBtcWithdrawalConfig(testName: String = ""): BtcWithdrawalConfig {
        val btcWithdrawalConfig =
            loadLocalConfigs(
                "btc-withdrawal",
                BtcWithdrawalConfig::class.java,
                "withdrawal.properties"
            ).get()
        return object : BtcWithdrawalConfig {
            override val utxoStorageAccount = utxoStorageAccountCredential.accountId
            override val txStorageAccount = txStorageAccountCredential.accountId
            override val btcConsensusCredential =
                accountHelper.createCredentialRawConfig(accountHelper.btcConsensusAccount)
            override val irohaBlockQueue = testName
            override val btcTransfersWalletPath = createWalletFile("transfers.$testName")
            override val notaryListStorageAccount = accountHelper.notaryListStorageAccount.accountId
            override val notaryListSetterAccount = accountHelper.notaryAccount.accountId
            override val signatureCollectorCredential =
                accountHelper.createCredentialRawConfig(accountHelper.btcWithdrawalSignatureCollectorAccount)
            override val changeAddressesStorageAccount =
                accountHelper.changeAddressesStorageAccount.accountId
            override val registrationCredential =
                accountHelper.createCredentialRawConfig(accountHelper.registrationAccount)
            override val mstRegistrationAccount = accountHelper.mstRegistrationAccount.accountId
            override val withdrawalCredential =
                accountHelper.createCredentialRawConfig(accountHelper.btcWithdrawalAccount)
            override val iroha = btcWithdrawalConfig.iroha
        }
    }

    /*
        Creates wallet file just for testing
     */
    fun createWalletFile(walletNamePostfix: String): String {
        val testWalletsFolder = File(TEST_WALLETS_FOLDER)
        if (!testWalletsFolder.exists()) {
            testWalletsFolder.mkdirs()
        }
        val newWalletFilePath = "$TEST_WALLETS_FOLDER/d3.$walletNamePostfix.wallet"
        Wallet(RegTestParams.get()).saveToFile(File(newWalletFilePath))
        return newWalletFilePath
    }

    /**
     * Creates config for Bitcoin deposit
     * @param testName - name of the test. used to create folder for block storage
     * @param notaryIrohaCredential - notary Iroha credential. Taken from account helper by default
     * @return configuration
     */
    fun createBtcDepositConfig(
        testName: String = "",
        notaryIrohaCredential: IrohaCredential = accountHelper.notaryAccount
    ): BtcDepositConfig {
        val btcDepositConfig = loadLocalConfigs(
            "btc-deposit",
            BtcDepositConfig::class.java,
            "deposit.properties"
        ).get()
        return object : BtcDepositConfig {
            override val mstRegistrationAccount = accountHelper.mstRegistrationAccount.accountId
            override val changeAddressesStorageAccount =
                accountHelper.changeAddressesStorageAccount.accountId
            override val btcTransferWalletPath = createWalletFile("transfers.$testName")
            override val registrationAccount = accountHelper.registrationAccount.accountId
            override val iroha = createIrohaConfig()
            override val notaryCredential =
                accountHelper.createCredentialRawConfig(notaryIrohaCredential)
        }
    }

    /**
     * Creates Bitcoin config
     */
    fun createBitcoinConfig(testName: String = ""): BitcoinConfig {
        val btcDwBridgeConfig =
            loadLocalConfigs(
                "btc-dw-bridge",
                BtcDWBridgeConfig::class.java,
                "dw-bridge.properties"
            ).get()
        return createBitcoinConfig(btcDwBridgeConfig.bitcoin, testName)
    }

    private fun createBitcoinConfig(bitcoinConfig: BitcoinConfig, testName: String): BitcoinConfig {
        return object : BitcoinConfig {
            override val blockStoragePath =
                createTempBlockStorageFolder(bitcoinConfig.blockStoragePath, testName)
            override val confidenceLevel = bitcoinConfig.confidenceLevel
            override val hosts = bitcoinConfig.hosts
        }
    }

    /*
    Creates temporary folder for Bitcoin block storage
    */
    fun createTempBlockStorageFolder(btcBlockStorageFolder: String, postFix: String): String {
        val newBlockStorageFolder =
            if (postFix.isEmpty()) {
                btcBlockStorageFolder
            } else {
                "${btcBlockStorageFolder}_$postFix"
            }
        val blockStorageFolder = File(newBlockStorageFolder)
        if (!blockStorageFolder.exists()) {
            if (!blockStorageFolder.mkdirs()) {
                throw IllegalStateException("Cannot create folder '$blockStorageFolder' for block storage")
            }
        }
        return newBlockStorageFolder
    }

    fun createBtcRegistrationConfig(): BtcRegistrationConfig {
        return object : BtcRegistrationConfig {
            override val nodeId = NODE_ID
            override val notaryAccount = accountHelper.notaryAccount.accountId
            override val mstRegistrationAccount = accountHelper.mstRegistrationAccount.accountId
            override val port = portCounter.incrementAndGet()
            override val registrationCredential =
                accountHelper.createCredentialRawConfig(accountHelper.registrationAccount)
            override val iroha = createIrohaConfig()
        }
    }
}
