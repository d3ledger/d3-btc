/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package integration.btc

import com.d3.btc.config.BTC_ASSET
import com.d3.btc.helper.address.outPutToBase58Address
import com.d3.btc.helper.currency.satToBtc
import com.d3.btc.model.BtcAddressType
import com.d3.chainadapter.client.RMQConfig
import com.d3.commons.model.IrohaCredential
import com.d3.commons.sidechain.iroha.CLIENT_DOMAIN
import com.d3.commons.sidechain.iroha.FEE_DESCRIPTION
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.getRandomString
import com.d3.commons.util.toHexString
import com.github.kittinunf.result.failure
import com.rabbitmq.client.ConnectionFactory
import integration.btc.environment.BtcAddressGenerationTestEnvironment
import integration.btc.environment.BtcWithdrawalTestEnvironment
import integration.helper.BTC_PRECISION
import integration.helper.BtcIntegrationHelperUtil
import integration.registration.RegistrationServiceTestEnvironment
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KLogging
import org.bitcoinj.core.Address
import org.bitcoinj.params.RegTestParams
import org.junit.jupiter.api.*
import java.io.File
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals

//TODO refactor tests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BtcMultiWithdrawalFailToleranceIntegrationTest {

    private val peers = 3
    private val integrationHelper = BtcIntegrationHelperUtil(peers)
    private val withdrawalEnvironments = ArrayList<BtcWithdrawalTestEnvironment>()
    private val addressGenerationEnvironments = ArrayList<BtcAddressGenerationTestEnvironment>()
    private val registrationServiceEnvironment =
        RegistrationServiceTestEnvironment(integrationHelper)
    private val testNames = ArrayList<String>()

    @AfterAll
    fun dropDown() {
        registrationServiceEnvironment.close()

        withdrawalEnvironments.forEach { environment ->
            environment.close()
        }
        addressGenerationEnvironments.forEach { environment ->
            environment.close()
        }
    }

    @BeforeAll
    fun setUp() {
        registrationServiceEnvironment.registrationInitialization.init()
        integrationHelper.generateBtcInitialBlocks()
        repeat(peers) { peer ->
            testNames.add("multi_withdrawal_${String.getRandomString(5)}_$peer")
            integrationHelper.addBtcNotary("test_notary_$peer", "test")
        }
        var peerCount = 0

        integrationHelper.accountHelper.btcWithdrawalAccounts
            .forEach { withdrawalAccount ->
                val environment = createWithdrawalEnvironment(testNames[peerCount++], withdrawalAccount)
                bindQueue(integrationHelper.rmqConfig, environment.btcWithdrawalConfig.irohaBlockQueue)
            }

        peerCount = 0
        integrationHelper.accountHelper.mstRegistrationAccounts.forEach { mstRegistrationAccount ->
            val testName = testNames[peerCount++]
            val environment = BtcAddressGenerationTestEnvironment(
                integrationHelper,
                btcGenerationConfig = integrationHelper.configHelper.createBtcAddressGenerationConfig(
                    0,
                    testName
                ),
                mstRegistrationCredential = mstRegistrationAccount
            )
            addressGenerationEnvironments.add(environment)
            GlobalScope.launch {
                environment.btcAddressGenerationInitialization.init().failure { ex -> throw ex }
            }
        }
        //Wait services to init
        Thread.sleep(WAIT_PREGEN_PROCESS_MILLIS)
        // Run all withdrawal services except the last one
        withdrawalEnvironments
            .filter { env -> env != withdrawalEnvironments.last() }
            .forEach { environment ->
                GlobalScope.launch {
                    environment.btcWithdrawalInitialization.init().failure { ex -> throw ex }
                }
            }
    }

    /**
     * Note: Iroha and bitcoind must be deployed to pass the test.
     * @given two registered BTC clients. 1st client has 1 BTC in wallet. 2 out 3 withdrawal services are running
     * @when 1st client sends SAT 10000 to 2nd client before the last withdrawal service being started
     * @then new well constructed BTC transaction and one signature appear after the last withdrawal service start.
     * Transaction is properly signed, sent to Bitcoin network and not considered unsigned anymore
     */
    @Test
    fun testFailToleranceWithdrawal() {
        val feeInitialAmount = integrationHelper.getWithdrawalFees()
        val environment = withdrawalEnvironments.first()
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
        addressGenerationEnvironments.first().generateAddress(BtcAddressType.FREE)
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
        // Little pause
        Thread.sleep(5_000)
        // Start the missing withdrawal service
        GlobalScope.launch {
            withdrawalEnvironments.last().btcWithdrawalInitialization.init().failure { ex -> throw ex }
        }
        logger.info("Starting the missing withdrawal service")
        // Let me start
        Thread.sleep(5_000)
        Thread.sleep(WITHDRAWAL_WAIT_MILLIS)
        assertEquals(initTxCount + 1, environment.createdTransactions.size)
        val createdWithdrawalTx = environment.getLastCreatedTxHash()
        environment.signCollector.getSignatures(createdWithdrawalTx).fold({ signatures ->
            logger.info { "signatures $signatures" }
            assertEquals(peers, signatures[0]!!.size)
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
        Assertions.assertNotNull(environment.getLastCreatedTx().outputs.firstOrNull { transactionOutput ->
            outPutToBase58Address(
                transactionOutput
            ) == btcAddressDest
        })
        assertEquals(
            BigDecimal.valueOf(0).setScale(BTC_PRECISION),
            integrationHelper.getWithdrawalAccountBalance(environment.btcWithdrawalConfig)
        )
        assertEquals((feeInitialAmount + getFee(amount)).setScale(BTC_PRECISION), integrationHelper.getWithdrawalFees())
        //Check that change addresses are watched
        withdrawalEnvironments.forEach { withdrawalEnvironment ->
            val changeAddresses =
                withdrawalEnvironment.btcChangeAddressProvider.getAllChangeAddresses().get()
            changeAddresses.forEach { changeAddress ->
                withdrawalEnvironment.transferWallet.isAddressWatched(
                    Address.fromBase58(
                        RegTestParams.get(),
                        changeAddress.address
                    )
                )
            }
        }
    }

    /**
     * Binds given [irohaQueue] to exchange
     * @param rmqConfig - RMQ configuration object
     * @param irohaQueue - queue to bind
     */
    private fun bindQueue(rmqConfig: RMQConfig, irohaQueue: String) {
        val factory = ConnectionFactory()
        factory.host = rmqConfig.host
        factory.port = rmqConfig.port
        factory.newConnection().use { conn ->
            conn.createChannel().use { channel ->
                channel.exchangeDeclare(rmqConfig.irohaExchange, "fanout", true)
                channel.queueDeclare(irohaQueue, true, false, false, null)
                channel.queueBind(irohaQueue, rmqConfig.irohaExchange, "")
            }
        }
    }

    /**
     * Creates withdrawal environment
     * @param serviceName - unique service name
     * @param withdrawalAccount - withdrawal account
     * @return withdrawal environment
     */
    private fun createWithdrawalEnvironment(
        serviceName: String,
        withdrawalAccount: IrohaCredential
    ): BtcWithdrawalTestEnvironment {
        val withdrawalConfig =
            integrationHelper.configHelper.createBtcWithdrawalConfig(serviceName)
        val environment =
            BtcWithdrawalTestEnvironment(
                integrationHelper,
                serviceName,
                btcWithdrawalConfig = withdrawalConfig,
                withdrawalCredential = withdrawalAccount
            )
        withdrawalEnvironments.add(environment)
        val blockStorageFolder =
            File(environment.bitcoinConfig.blockStoragePath)
        //Clear bitcoin blockchain folder
        blockStorageFolder.deleteRecursively()
        //Recreate folder
        blockStorageFolder.mkdirs()
        return environment
    }

    companion object : KLogging()
}