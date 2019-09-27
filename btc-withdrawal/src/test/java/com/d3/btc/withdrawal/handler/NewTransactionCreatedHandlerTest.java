package com.d3.btc.withdrawal.handler;

import com.d3.btc.handler.SetAccountDetailEvent;
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.transaction.SignCollector;
import com.d3.btc.withdrawal.transaction.TransactionsStorage;
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.bitcoinj.core.Transaction;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NewTransactionCreatedHandlerTest {

    private SignCollector signCollector;
    private BtcWithdrawalConfig btcWithdrawalConfig;
    private BtcRollbackService btcRollbackService;
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
        broadcastsProvider = mock(BroadcastsProvider.class);
        transactionsStorage = mock(TransactionsStorage.class);
        newTransactionCreatedHandler = spy(new NewTransactionCreatedHandler(
                signCollector,
                transactionsStorage,
                btcWithdrawalConfig,
                btcRollbackService,
                broadcastsProvider));
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then transactions are ignored and not signed
     */
    @Test
    public void testHandleHasBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account",
                "destination address",
                1,
                System.currentTimeMillis(),
                0);

        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalConsensus, transaction);
        }));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector, never()).signAndSave(eq(withdrawalConsensus), any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns false whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then transactions are signed
     */
    @Test
    public void testHandleHasNotBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account",
                "destination address",
                1,
                System.currentTimeMillis(),
                0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalConsensus, transaction);
        }));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doReturn(Result.Companion.of(() -> Unit.INSTANCE)).when(signCollector).signAndSave(any(), any(), any());
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector).signAndSave(eq(withdrawalConsensus), any(), any());
    }

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then transactions are not signed, rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleBroadcastFail() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account",
                "destination address",
                1,
                System.currentTimeMillis(),
                0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalConsensus, transaction);
        }));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Broadcast failure");
        }));
        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(createdTxCommand, withdrawalAccountId);
        newTransactionCreatedHandler.handle(event);
        verify(signCollector, never()).signAndSave(eq(withdrawalConsensus), any(), any());
        verify(btcRollbackService).rollback(any(WithdrawalDetails.class), any(), any());
    }
}