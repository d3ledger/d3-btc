/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.currency.satToBtc
import com.d3.commons.sidechain.iroha.FEE_DESCRIPTION
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.getRandomString
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import integration.btc.environment.BtcWithdrawalTestEnvironment
import integration.helper.BTC_PRECISION
import integration.helper.BtcIntegrationHelperUtil
import integration.helper.D3_DOMAIN
import integration.helper.NODE_ID
import integration.registration.RegistrationServiceTestEnvironment
import mu.KLogging
import org.bitcoinj.core.Address
import org.junit.Assert.assertNotNull
import org.junit.jupiter.api.*
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals

private const val TOTAL_TESTS = 1

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcWithdrawalFailResistanceIntegrationTest {

    private val integrationHelper = BtcIntegrationHelperUtil()

    private val environment =
        BtcWithdrawalTestEnvironment(
            integrationHelper,
            "fail_resistance_${String.getRandomString(5)}"
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
        // This call simulates that the service stopped
        environment.bindQueueWithExchange(
            environment.btcWithdrawalConfig.irohaBlockQueue,
            environment.rmqConfig.irohaExchange
        )
        val blockStorageFolder = File(environment.bitcoinConfig.blockStoragePath)
        //Clear bitcoin blockchain folder
        blockStorageFolder.deleteRecursively()
        //Recreate folder
        blockStorageFolder.mkdirs()
        integrationHelper.generateBtcInitialBlocks()
        integrationHelper.genChangeBtcAddress(environment.btcWithdrawalConfig.btcKeysWalletPath)
            .fold({ address -> changeAddress = address }, { ex -> throw  ex })
        integrationHelper.preGenFreeBtcAddresses(
            environment.btcWithdrawalConfig.btcKeysWalletPath,
            TOTAL_TESTS * 2,
            NODE_ID
        )
        environment.utxoProvider.addToBlackList(changeAddress.toBase58())
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet. btc-withdrawal service is off
     * @when 1st client sends SAT 10000 to 2nd client, btc-withdrawal service is turned on
     * @then new well constructed BTC transaction and one signature appear.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testWithdrawalBeforeServiceStart() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val initTxCount = environment.createdTransactions.size
        val amount = satToBtc(10000L)
        val randomNameSrc = String.getRandomString(9)
        val testClientSrcKeypair = ModelUtil.generateKeypair()
        val testClientSrc = "$randomNameSrc@$D3_DOMAIN"
        var res = registrationServiceEnvironment.register(
            randomNameSrc,
            testClientSrcKeypair.public.toHexString()
        )
        assertEquals(200, res.statusCode)
        val btcAddressSrc =
            integrationHelper.registerBtcAddressNoPreGen(
                randomNameSrc,
                D3_DOMAIN,
                testClientSrcKeypair
            )
        integrationHelper.sendBtc(
            btcAddressSrc,
            BigDecimal(1),
            environment.bitcoinConfig.confidenceLevel
        )
        val randomNameDest = String.getRandomString(9)
        res = registrationServiceEnvironment.register(randomNameDest)
        assertEquals(200, res.statusCode)
        val btcAddressDest =
            integrationHelper.registerBtcAddressNoPreGen(randomNameDest, D3_DOMAIN)
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

        // Start withdrawal service after transfer
        environment.btcWithdrawalInitialization.init().failure { ex -> throw ex }
        environment.reverseChainAdapter.init().failure { ex -> throw ex }

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

    companion object : KLogging()
}
