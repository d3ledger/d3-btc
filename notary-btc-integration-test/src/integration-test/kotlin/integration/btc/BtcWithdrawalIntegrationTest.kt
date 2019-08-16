/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.fee.CurrentFeeRate
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.currency.satToBtc
import com.d3.commons.sidechain.iroha.CLIENT_DOMAIN
import com.d3.commons.sidechain.iroha.FEE_DESCRIPTION
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.getRandomString
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcWithdrawalTestEnvironment
import integration.helper.BTC_PRECISION
import integration.helper.BtcIntegrationHelperUtil
import integration.registration.RegistrationServiceTestEnvironment
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals

val MINIMUM_FEE = BigDecimal("0.00000001")
const val WITHDRAWAL_WAIT_MILLIS = 20_000L
private const val TOTAL_TESTS = 16
const val FAILED_WITHDRAW_AMOUNT = 6666L
private const val FAILED_BROADCAST_AMOUNT = 7777L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcWithdrawalIntegrationTest {

    private val integrationHelper = BtcIntegrationHelperUtil()

    private val environment =
        BtcWithdrawalTestEnvironment(
            integrationHelper,
            "withdrawal_test_${String.getRandomString(5)}"
        )

    private val registrationServiceEnvironment =
        RegistrationServiceTestEnvironment(integrationHelper)

    private lateinit var changeAddress: Address

    @AfterAll
    fun dropDown() {
        registrationServiceEnvironment.close()
        environment.close()
    }

    @BeforeAll
    fun setUp() {
        registrationServiceEnvironment.registrationInitialization.init()
        val blockStorageFolder = File(environment.bitcoinConfig.blockStoragePath)
        //Clear bitcoin blockchain folder
        blockStorageFolder.deleteRecursively()
        //Recreate folder
        blockStorageFolder.mkdirs()
        integrationHelper.addBtcNotary("test", "test")
        integrationHelper.generateBtcInitialBlocks()
        integrationHelper.genChangeBtcAddress(environment.btcWithdrawalConfig.btcKeysWalletPath)
            .fold({ address -> changeAddress = address }, { ex -> throw  ex })
        integrationHelper.preGenFreeBtcAddresses(
            environment.btcWithdrawalConfig.btcKeysWalletPath,
            TOTAL_TESTS
        )
        environment.newSignatureEventHandler.addBroadcastTransactionListeners { tx ->
            if (tx.outputs.any { output -> output.value.value == FAILED_BROADCAST_AMOUNT }) {
                throw Exception("Failed broadcast test")
            }
        }
        environment.btcWithdrawalInitialization.init().failure { ex -> throw ex }
        environment.reverseChainAdapter.init().failure { ex -> throw ex }
        environment.utxoProvider.addToBlackList(changeAddress.toBase58())
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet.
     * @when 1st client sends SAT 10000 to 2nd client
     * @then new well constructed BTC transaction and one signature appear.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore.
     * Billing account has 10% of amount as a fee.
     */
    @Test
    fun testWithdrawal() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        // Generate one block to broadcast all pending transactions
        integrationHelper.generateBtcBlocks(1)
        Thread.sleep(2_000)
        val initUTXOCount = environment.transferWallet.unspents.size
        assertEquals(
            initUTXOCount,
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath)).unspents.size
        )
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(1, signatures[0]!!.size)
        }, { ex -> fail(ex) })
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                BigDecimal(integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET))
            )
        )
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        // Append withdrawal tx to the next block
        integrationHelper.generateBtcBlocks(1)
        // Wait a little
        Thread.sleep(2_000)
        val walletFromFile =
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath))
        // 1 more UTXO must be stored in the wallet(UTXO for change )
        // Check in-memory wallet
        assertEquals(initUTXOCount + 1, environment.transferWallet.unspents.size)
        // Check wallet from file
        assertEquals(
            initUTXOCount + 1,
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath)).unspents.size
        )
        // Check that we have got UTXO associated with change address
        assertTrue(walletFromFile.unspents.any { utxo -> utxo.getAddressFromP2SH(RegTestParams.get()) == changeAddress })
        // Check that change address is watched
        assertTrue(walletFromFile.isAddressWatched(changeAddress))
        assertEquals((feeInitialAmount + getFee(amount)).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given one registered BTC client. 1st client has 1 BTC in wallet.
     * @when 1st client sends SAT 10000 to invalid address
     * @then no Bitcoin transaction is created, transferred money(including fee) is restored
     */
    @Test
    fun testWithdrawalInvalidAddress() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        // Generate one block to broadcast all pending transactions
        integrationHelper.generateBtcBlocks(1)
        Thread.sleep(2_000)
        val initUTXOCount = environment.transferWallet.unspents.size
        assertEquals(
            initUTXOCount,
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath)).unspents.size
        )
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val initialTestClientAmount = getAmountWithFee(amount)
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, initialTestClientAmount)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            "invalid btc address",
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        assertEquals(
            initialTestClientAmount.setScale(BTC_PRECISION).toPlainString(),
            BigDecimal(
                integrationHelper.getIrohaAccountBalance(
                    testClientSrc,
                    BTC_ASSET
                )
            ).setScale(BTC_PRECISION).toPlainString()
        )
        // Append withdrawal tx to the next block
        integrationHelper.generateBtcBlocks(1)
        // Wait a little
        Thread.sleep(2_000)
        assertEquals((feeInitialAmount).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet.
     * @when 1st client sends SAT 10000 to 2nd client with no Iroha fee
     * @then new well constructed BTC transaction and one signature appear.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore.
     * Billing account has 0% of amount as a fee because no fee was set
     */
    @Test
    fun testWithdrawalNoIrohaFee() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        // Generate one block to broadcast all pending transactions
        integrationHelper.generateBtcBlocks(1)
        Thread.sleep(2_000)
        val initUTXOCount = environment.transferWallet.unspents.size
        assertEquals(
            initUTXOCount,
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath)).unspents.size
        )
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, amount)
        integrationHelper.transferAssetIrohaFromClient(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString()
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(1, signatures[0]!!.size)
        }, { ex -> fail(ex) })
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                BigDecimal(integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET))
            )
        )
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        // Append withdrawal tx to the next block
        integrationHelper.generateBtcBlocks(1)
        // Wait a little
        Thread.sleep(2_000)
        val walletFromFile =
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath))
        // 1 more UTXO must be stored in the wallet(UTXO for change )
        // Check in-memory wallet
        assertEquals(initUTXOCount + 1, environment.transferWallet.unspents.size)
        // Check wallet from file
        assertEquals(
            initUTXOCount + 1,
            Wallet.loadFromFile(File(environment.btcWithdrawalConfig.btcTransfersWalletPath)).unspents.size
        )
        // Check that we have got UTXO associated with change address
        assertTrue(walletFromFile.unspents.any { utxo -> utxo.getAddressFromP2SH(RegTestParams.get()) == changeAddress })
        // Check that change address is watched
        assertTrue(walletFromFile.isAddressWatched(changeAddress))
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC as one UTXO in wallet.
     * @when 1st client makes transaction that will fail(exception will be thrown)
     * @then 1st transaction is considered failed, but it doesn't affect next transaction,
     * meaning that UTXO that was used in failed transaction is free to use even after failure.
     */
    @Test
    fun testWithdrawalFailResistance() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        var amount = satToBtc(FAILED_WITHDRAW_AMOUNT)
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        amount = satToBtc(10000L)
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        assertEquals(feeInitialAmount + getFee(amount), integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC as one UTXO in wallet.
     * @when 1st client makes transaction that will cause broadcast failure(exception will be thrown)
     * @then 1st transaction is considered failed, but it doesn't affect next transaction,
     * meaning that UTXO that was used in failed transaction is free to use even after failure.
     * 1st transaction money is restored.
     */
    @Test
    fun testWithdrawalFailedNetworkResistance() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        var amount = satToBtc(FAILED_BROADCAST_AMOUNT)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        amount = satToBtc(10_000L)
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 2, environment.createdTransactions.size)
        assertEquals(feeInitialAmount + getFee(amount), integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two BTC clients are registered after withdrawal service being started. 1st client has 1 BTC in wallet.
     * @when 1st client sends SAT 10000 to 2nd client
     * @then new well constructed BTC transaction and one signature appear.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testWithdrawalAddressGenerationOnFly() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc = integrationHelper.registerBtcAddress(
            environment.btcWithdrawalConfig.btcKeysWalletPath,
            randomNameSrc,
            CLIENT_DOMAIN,
            testClientSrcKeypair
        )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(1, signatures[0]!!.size)
        }, { ex -> fail(ex) })
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                BigDecimal(integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET))
            )
        )
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        assertEquals((feeInitialAmount + getFee(amount)).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 2 BTC as 2 unspents(1+1) in wallet
     * @when 1st client sends 2 BTC-minimum fee(0.00000001) to 2nd client
     * @then no tx is created, because 1st client has no money for Bitcoin fee
     */
    @Test
    fun testWithdrawalNoMoneyLeftForFee() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = BigDecimal(2)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )

        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )

        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, amount)
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.subtract(MINIMUM_FEE).toPlainString(),
            BTC_ASSET,
            MINIMUM_FEE.toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            BigDecimal(initialSrcBalance).setScale(BTC_PRECISION).toPlainString(),
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(feeInitialAmount.setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 2 BTC in wallet
     * @when 1st client sends 1 BTC to 2nd client without having enough confirmations
     * @then no tx is created, because nobody can use unconfirmed money
     */
    @Test
    fun testWithdrawalNoConfirmedMoney() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = BigDecimal(2)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )

        integrationHelper.sendBtc(
            btcAddressSrc,
            amount,
            environment.bitcoinConfig.confidenceLevel - 1
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, amount)
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            BigDecimal(1).toPlainString(),
            BTC_ASSET,
            MINIMUM_FEE.toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            BigDecimal(initialSrcBalance).setScale(BTC_PRECISION).toPlainString(),
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 10 BTC as 2 unspents(5+5) in wallet.
     * @when 1st client sends BTC 6 to 2nd client
     * @then new well constructed BTC transaction and 2 signatures appears.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testWithdrawalMultipleInputs() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = BigDecimal(6)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(5),
            environment.bitcoinConfig.confidenceLevel
        )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(5),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(1, signatures[0]!!.size)
            assertEquals(1, signatures[1]!!.size)
        }, { ex -> fail(ex) })
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals((feeInitialAmount + getFee(amount)).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet. 2nd client is in 1st client white list
     * @when 1st client sends SAT 10000 to 2nd client
     * @then new well constructed BTC transaction appears.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testWithdrawalWhiteListed() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val btcAddressDest = integrationHelper.createBtcAddress()
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc = integrationHelper.registerBtcAddressNoPreGen(
            randomNameSrc,
            CLIENT_DOMAIN,
            testClientSrcKeypair
        )
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val amountInWalletBtc = BigDecimal(1)
        integrationHelper.sendBtc(btcAddressSrc, amountInWalletBtc)
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        assertEquals(
            0,
            BigDecimal.ZERO.compareTo(
                BigDecimal(integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET))
            )
        )
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals((feeInitialAmount + getFee(amount)).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC as one unspent in wallet.
     * @when 1st client sends SAT 10000 to 2nd client twice
     * @then only first transaction is well constructed, because there is no unspent transactions left.
     * First transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testWithdrawalNoUnspentsLeft() {
        var feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        assertEquals(feeInitialAmount + getFee(amount), integrationHelper.getWithdrawalFees())
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(1, signatures[0]!!.size)
        }, { ex -> fail(ex) })
        assertEquals(2, environment.getLastCreatedTx().outputs.size)
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == changeAddress.toBase58()
        })
        feeInitialAmount = integrationHelper.getWithdrawalFees()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        assertEquals(
            BigDecimal(initialSrcBalance).setScale(BTC_PRECISION).toPlainString(),
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has no BTC
     * @when 1st client sends SAT 10000 to 2nd client
     * @then no transaction is created, because 1st client has no money at all
     */
    @Test
    fun testWithdrawalNoMoneyWasSentPreviously() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        integrationHelper.registerBtcAddressNoPreGen(
            randomNameSrc,
            CLIENT_DOMAIN,
            testClientSrcKeypair
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC
     * @when 1st client sends BTC 100 to 2nd client
     * @then no transaction is created, because 1st client hasn't got enough BTC to spend
     */
    @Test
    fun testWithdrawalNotEnoughMoney() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = BigDecimal(100)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 100 000 SAT as 100 UTXO in wallet.
     * @when 1st client sends SAT 10000 to 2nd client
     * @then transaction fails, because wallet fully consists of 'dusty' UTXOs
     */
    @Test
    fun testWithdrawalOnlyDustMoney() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        repeat(100) {
            integrationHelper.sendSat(btcAddressSrc, 1000, 0)
        }
        integrationHelper.generateBtcBlocks(environment.bitcoinConfig.confidenceLevel)
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet.
     * @when 1st client sends SAT 1 to 2nd client
     * @then transaction fails, because SAT 1 is too small to spend
     */
    @Test
    fun testWithdrawalSmallAmount() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(1)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        val amountInWalletBtc = getAmountWithFee(BigDecimal(1))
        integrationHelper.sendBtc(btcAddressSrc, amountInWalletBtc)
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, amount + MINIMUM_FEE)
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            MINIMUM_FEE.toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet. Fee rate was not set.
     * @when 1st client sends SAT 10000 to 2nd client
     * @then transaction fails, because fee rate was not set
     */
    @Test
    fun testWithdrawalFeeRateWasNotSet() {
        CurrentFeeRate.clear()
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$CLIENT_DOMAIN"
        val res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                CLIENT_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(btcAddressSrc, BigDecimal(1))
        val btcAddressDest = integrationHelper.createBtcAddress()
        integrationHelper.addIrohaAssetTo(testClientSrc, BTC_ASSET, getAmountWithFee(amount))
        val initialSrcBalance = integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        integrationHelper.transferAssetIrohaFromClientWithFee(
            testClientSrc,
            testClientSrcKeypair,
            testClientSrc,
            environment.btcWithdrawalConfig.withdrawalCredential.accountId,
            BTC_ASSET,
            btcAddressDest,
            amount.toPlainString(),
            BTC_ASSET,
            getFee(amount).toPlainString(),
            FEE_DESCRIPTION
        )
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount, environment.createdTransactions.size)
        assertEquals(
            initialSrcBalance,
            integrationHelper.getIrohaAccountBalance(testClientSrc, BTC_ASSET)
        )
        assertEquals(feeInitialAmount, integrationHelper.getWithdrawalFees())
        environment.utxoProvider.addToBlackList(btcAddressSrc)
        environment.utxoProvider.addToBlackList(btcAddressDest)
        CurrentFeeRate.setMinimum()
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
    }

    /**
     * Logger
     */
    companion object : KLogging()
}

/**
 * Calculates fee
 * @param amount - amount that is used to calculate fee
 * @return fee
 */
fun getFee(amount: BigDecimal): BigDecimal =
    amount.multiply(BigDecimal("0.1")).setScale(BTC_PRECISION, RoundingMode.DOWN)

/**
 * Calculates amount plus fee
 * @param amount - amount that will be used in calculation process
 * @return amount + fee
 */
fun getAmountWithFee(amount: BigDecimal) = (amount + getFee(amount)).setScale(BTC_PRECISION, RoundingMode.DOWN)!!