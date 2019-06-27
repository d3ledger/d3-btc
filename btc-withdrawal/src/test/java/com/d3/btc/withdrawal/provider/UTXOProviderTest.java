/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.withdrawal.provider;

import com.d3.btc.peer.SharedPeerGroup;
import com.d3.btc.provider.BtcChangeAddressProvider;
import com.d3.btc.provider.BtcRegisteredAddressesProvider;
import com.d3.btc.provider.network.BtcRegTestConfigProvider;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer;
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper;
import com.github.kittinunf.result.Result;
import org.bitcoinj.core.*;
import org.bitcoinj.wallet.Wallet;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anySet;
import static org.mockito.Mockito.*;

public class UTXOProviderTest {

    private static final int CONFIDENCE_LEVEL = 6;
    private static Wallet wallet = mock(Wallet.class);
    private static Map<Sha256Hash, Integer> txHashAppearance = new HashMap<>();
    private static SharedPeerGroup peerGroup = mock(SharedPeerGroup.class);
    private static UsedUTXOProvider usedUTXOProvider = new UsedUTXOProvider(mock(IrohaQueryHelper.class), mock(IrohaConsumer.class), "abc") {

        private final Set<String> usedUTXO = new HashSet<>();

        @Override
        public Result<Boolean, Exception> isUsed(WithdrawalDetails withdrawalDetails, TransactionOutput output) {
            return Result.Companion.of(() -> usedUTXO.contains(output.getHash().toString()));
        }
    };
    private static UTXOProvider bitcoinUTXOProvider = spy(
            new UTXOProvider(
                    wallet,
                    peerGroup,
                    new BtcRegTestConfigProvider(),
                    mock(BtcRegisteredAddressesProvider.class),
                    mock(BtcChangeAddressProvider.class),
                    usedUTXOProvider
            ));

    @BeforeClass
    public static void setUp() {
        Sha256Hash mockHash = mock(Sha256Hash.class);
        txHashAppearance.put(mockHash, 1);
        doReturn(true).when(bitcoinUTXOProvider).isAvailableOutput(anySet(), any(TransactionOutput.class));
        StoredBlock storedBlock = mock(StoredBlock.class);
        when(storedBlock.getHeight()).thenReturn(0);
        when(peerGroup.getBlock(mockHash)).thenReturn(storedBlock);
    }

    /**
     * @given wallet with 1_000_000 SAT as one unspent
     * @when try to collect 1000 SAT
     * @then one unspent with 1_000_000 SAT is collected
     */
    @Test
    public void testCollectUnspents() {
        long amountToSpendSat = 1000;
        List<TransactionOutput> unspents = new ArrayList<>();
        TransactionOutput output = createTransactionOutput();
        Transaction transaction = mock(Transaction.class);
        when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(output.getParentTransaction()).thenReturn(transaction);
        when(output.getValue()).thenReturn(Coin.valueOf(1_000_000L));
        when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);
        unspents.add(output);
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            assertEquals(1, transactionOutputs.size());
            assertTrue(transactionOutputs.contains(output));
            return null;
        }, Assertions::fail);
    }

    /**
     * @given wallet with 1_000_000 SAT as one unspent
     * @when try to collect 1000 SAT with available max UTXO height as -1
     * @then exception is thrown because there is no UTXO with height less than -1
     */
    @Test
    public void testCollectUnspentsBadHeight() {
        long amountToSpendSat = 1000;
        List<TransactionOutput> unspents = new ArrayList<>();
        TransactionOutput output = createTransactionOutput();
        Transaction transaction = mock(Transaction.class);
        when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(output.getParentTransaction()).thenReturn(transaction);
        when(output.getValue()).thenReturn(Coin.valueOf(1_000_000L));
        when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);
        unspents.add(output);
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, -1, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            fail();
            return null;
        }, e -> null);
    }

    /**
     * @given wallet with 36_000 SAT as 3 unspents(9_000*3)
     * @when try to collect 10_000 SAT
     * @then two unspents with total amount 18_000 SAT are collected
     */
    @Test
    public void testCollectMultipleUnspents() {
        long amountToSpendSat = 10_000;
        List<TransactionOutput> unspents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {

            TransactionOutput output = createTransactionOutput();
            Transaction transaction = mock(Transaction.class);
            when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
            when(output.getParentTransaction()).thenReturn(transaction);
            when(output.getValue()).thenReturn(Coin.valueOf(9_000));
            when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);
            unspents.add(output);
        }
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            assertEquals(2, transactionOutputs.size());
            assertEquals(18_000, bitcoinUTXOProvider.getTotalUnspentValue(transactionOutputs));
            return null;
        }, Assertions::fail);
    }

    /**
     * @given wallet with 1_000_001 SAT as 2 unspents(1_000_000 + 1)
     * @when try to collect 1000 SAT
     * @then one unspent with amount 1_000_000 SAT is collected
     */
    @Test
    public void testCollectUnspentsOrder() {
        long amountToSpendSat = 1000;


        TransactionOutput outputBigValue = createTransactionOutput();
        Transaction transactionBigValue = mock(Transaction.class);
        when(transactionBigValue.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(outputBigValue.getParentTransaction()).thenReturn(transactionBigValue);
        when(outputBigValue.getValue()).thenReturn(Coin.valueOf(1_000_000L));
        when(outputBigValue.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);

        TransactionOutput outputSmallValue = createTransactionOutput();
        Transaction transactionSmallValue = mock(Transaction.class);
        when(transactionSmallValue.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(outputBigValue.getParentTransaction()).thenReturn(transactionSmallValue);
        when(outputSmallValue.getValue()).thenReturn(Coin.valueOf(1L));
        when(outputSmallValue.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);

        List<TransactionOutput> unspents = new ArrayList<>(Arrays.asList(outputSmallValue, outputBigValue));
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            assertEquals(1, transactionOutputs.size());
            assertTrue(transactionOutputs.contains(outputBigValue));
            return null;
        }, Assertions::fail);
    }

    /**
     * @given wallet with 1_000 SAT as 1 unspent
     * @when try to collect 1_000 SAT
     * @then exception is thrown, because no money is left to pay fees
     */
    @Test
    public void testCollectUnspentsNoFee() {
        long amountToSpendSat = 1000;

        TransactionOutput output = createTransactionOutput();
        Transaction transaction = mock(Transaction.class);
        when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(output.getParentTransaction()).thenReturn(transaction);
        when(output.getValue()).thenReturn(Coin.valueOf(amountToSpendSat));
        when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);

        List<TransactionOutput> unspents = new ArrayList<>();
        unspents.add(output);
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            fail();
            return null;
        }, e -> null);
    }

    /**
     * @given wallet with 1_000_000 SAT as 1 not properly confirmed unspent
     * @when try to collect 1_000 SAT
     * @then exception is thrown, because there is no confirmed money in wallet
     */
    @Test
    public void testCollectUnspentsBadConfidenceLevel() {
        long amountToSpendSat = 1000;

        TransactionOutput output = createTransactionOutput();
        Transaction transaction = mock(Transaction.class);
        when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
        when(output.getParentTransaction()).thenReturn(transaction);
        when(output.getValue()).thenReturn(Coin.valueOf(1_000_000));
        when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL - 1);

        List<TransactionOutput> unspents = new ArrayList<>();
        unspents.add(output);
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            fail();
            return null;
        }, e -> null);
    }

    /**
     * @given wallet with 1_001 SAT as 2 unspent (1_000 + 1)
     * @when try to collect 1_000 SAT
     * @then exception is thrown, because there is no money to pay fees
     */
    @Test
    public void testCollectUnspentsMultipleOutputsNoFee() {
        long amountToSpendSat = 1000;

        TransactionOutput smallOutput = createTransactionOutput();
        when(smallOutput.getValue()).thenReturn(Coin.valueOf(amountToSpendSat));
        when(smallOutput.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);


        TransactionOutput evenSmallerOutput = createTransactionOutput();
        when(evenSmallerOutput.getValue()).thenReturn(Coin.valueOf(1));
        when(evenSmallerOutput.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);

        List<TransactionOutput> unspents = new ArrayList<>(Arrays.asList(smallOutput, evenSmallerOutput));
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            fail();
            return null;
        }, e -> null);
    }

    /**
     * @given wallet with 100_000 SAT as 100 unspent (1_000 * 100)
     * @when try to collect 1_000 SAT
     * @then exception is thrown, because every unspent takes 2000 SAT fee on average,
     * meaning that you can never collect 1_000 SAT using 100 little unspents,
     * even if total value in a wallet is 100_000 SAT
     */
    @Test
    public void testCollectUnspentsMultipleLittleOutputsNoFee() {
        long amountToSpendSat = 1000;
        List<TransactionOutput> unspents = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            TransactionOutput output = createTransactionOutput();
            Transaction transaction = mock(Transaction.class);
            when(transaction.getAppearsInHashes()).thenReturn(txHashAppearance);
            when(output.getParentTransaction()).thenReturn(transaction);
            when(output.getValue()).thenReturn(Coin.valueOf(amountToSpendSat));
            when(output.getParentTransactionDepthInBlocks()).thenReturn(CONFIDENCE_LEVEL);
            unspents.add(output);
        }
        when(wallet.getUnspents()).thenReturn(unspents);
        Result<List<TransactionOutput>, Exception> result = bitcoinUTXOProvider.collectUnspents(
                createDetails(), new HashSet<>(), amountToSpendSat, Integer.MAX_VALUE, CONFIDENCE_LEVEL);
        result.fold(transactionOutputs -> {
            fail();
            return null;
        }, e -> null);
    }

    private TransactionOutput createTransactionOutput() {
        TransactionOutput output = mock(TransactionOutput.class);
        Random random = new Random();
        byte[] utxoHash = new byte[256];
        random.nextBytes(utxoHash);
        when(output.getHash()).thenReturn(Sha256Hash.of(utxoHash));
        return output;
    }

    private WithdrawalDetails createDetails() {
        return new WithdrawalDetails("test@account",
                "test address",
                666,
                System.currentTimeMillis());
    }
}
