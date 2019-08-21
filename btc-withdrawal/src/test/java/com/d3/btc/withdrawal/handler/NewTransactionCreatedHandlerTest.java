package com.d3.btc.withdrawal.handler;

import com.d3.btc.handler.SetAccountDetailEvent;
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.UTXOProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.transaction.SignCollector;
import com.d3.btc.withdrawal.transaction.TransactionsStorage;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
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
    private String withdrawalAccountId = "withdrawal@bitcoin";

    @Before
    public void setUp() {
        signCollector = mock(SignCollector.class);
        IrohaCredentialRawConfig irohaCredential = mock(IrohaCredentialRawConfig.class);
        doReturn(withdrawalAccountId).when(irohaCredential).getAccountId();
        btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        doReturn(irohaCredential).when(btcWithdrawalConfig).getWithdrawalCredential();
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
     * @when handle() is called
     * @then transactions are ignored and not signed
     */
    @Test
    public void testHandleHasBeenBroadcasted() {
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
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector, never()).signAndSave(any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns false whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then transactions are signed
     */
    @Test
    public void testHandleHasNotBeenBroadcasted() {
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
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector).signAndSave(any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then transactions are not signed, rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleBroadcastFail() {
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
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector, never()).signAndSave(any(), any());
        verify(btcRollbackService).rollback(any(WithdrawalDetails.class), any());
        verify(utxoProvider).unregisterUnspents(any(), any());
    }
}