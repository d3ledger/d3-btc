package com.d3.btc.withdrawal.handler;

import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.UTXOProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.transaction.SignCollector;
import com.d3.btc.withdrawal.transaction.TransactionsStorage;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import kotlin.Unit;
import org.bitcoinj.core.Transaction;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NewTransactionCreatedHandlerTest {

    private SignCollector signCollector;
    private BtcWithdrawalConfig btcWithdrawalConfig;
    private BtcRollbackService btcRollbackService;
    private UTXOProvider utxoProvider;
    private BroadcastsProvider broadcastsProvider;
    private TransactionsStorage transactionsStorage;
    private NewTransactionCreatedHandler newTransactionCreatedHandler;

    @Before
    public void setUp() {
        signCollector = mock(SignCollector.class);
        btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        btcRollbackService = mock(BtcRollbackService.class);
        utxoProvider = mock(UTXOProvider.class);
        broadcastsProvider = mock(BroadcastsProvider.class);
        transactionsStorage = mock(TransactionsStorage.class);
        newTransactionCreatedHandler = spy(new NewTransactionCreatedHandler(
                signCollector,
                transactionsStorage,
                btcWithdrawalConfig,
                btcRollbackService,
                utxoProvider,
                broadcastsProvider));
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handleCreateTransactionCommand() is called
     * @then transactions are ignored and not signed
     */
    @Test
    public void testHandleCreateTransactionCommandHasBeenBroadcasted() {
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                    "source account",
                    "destination address",
                    1,
                    System.currentTimeMillis(),
                    0);
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalDetails, transaction);
        }));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        newTransactionCreatedHandler.handleCreateTransactionCommand(createdTxCommand);
        verify(signCollector, never()).signAndSave(any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns false whenever hasBeenBroadcasted() is called
     * @when handleCreateTransactionCommand() is called
     * @then transactions are signed
     */
    @Test
    public void testHandleCreateTransactionCommandHasNotBeenBroadcasted() {
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                    "source account",
                    "destination address",
                    1,
                    System.currentTimeMillis(),
                    0);
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalDetails, transaction);
        }));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        newTransactionCreatedHandler.handleCreateTransactionCommand(createdTxCommand);
        verify(signCollector).signAndSave(any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handleCreateTransactionCommand() is called
     * @then transactions are not signed, rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleCreateTransactionCommandBroadcastFail() {
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                    "source account",
                    "destination address",
                    1,
                    System.currentTimeMillis(),
                    0);
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalDetails, transaction);
        }));
        when(utxoProvider.unregisterUnspents(any(), any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Broadcast failure");
        }));
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        newTransactionCreatedHandler.handleCreateTransactionCommand(createdTxCommand);
        verify(signCollector, never()).signAndSave(any(), any());
        verify(btcRollbackService).rollback(any(WithdrawalDetails.class), any());
        verify(utxoProvider).unregisterUnspents(any(), any());
    }
}