package com.d3.btc.withdrawal.handler;

import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.UTXOProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics;
import com.d3.btc.withdrawal.transaction.SignCollector;
import com.d3.btc.withdrawal.transaction.TransactionsStorage;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import kotlin.Unit;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.d3.btc.config.BitcoinConfigKt.BTC_SIGN_COLLECT_DOMAIN;
import static org.mockito.Mockito.*;

public class NewSignatureEventHandlerTest {

    private WithdrawalStatistics withdrawalStatistics;
    private SignCollector signCollector;
    private TransactionsStorage transactionsStorage;
    private PeerGroup peerGroup;
    private BroadcastsProvider broadcastsProvider;
    private BtcRollbackService btcRollbackService;
    private UTXOProvider utxoProvider;
    private NewSignatureEventHandler newSignatureEventHandler;

    @Before
    public void setUp() {
        withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        signCollector = mock(SignCollector.class);
        transactionsStorage = mock(TransactionsStorage.class);
        peerGroup = mock(PeerGroup.class);
        broadcastsProvider = mock(BroadcastsProvider.class);
        btcRollbackService = mock(BtcRollbackService.class);
        utxoProvider = mock(UTXOProvider.class);
        newSignatureEventHandler = spy(new NewSignatureEventHandler(
                withdrawalStatistics,
                signCollector,
                transactionsStorage,
                utxoProvider,
                btcRollbackService,
                peerGroup,
                broadcastsProvider));
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handleNewSignatureCommand() is called
     * @then broadcastIfEnoughSignatures() is not called
     */
    @Test
    public void testHandleNewSignatureCommandHasBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        newSignatureEventHandler.handleNewSignatureCommand(newSignatureDetail, () -> null);
        verify(newSignatureEventHandler, never()).broadcastIfEnoughSignatures(any(), any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that returns false whenever hasBeenBroadcasted() is called
     * @when handleNewSignatureCommand() is called
     * @then broadcastIfEnoughSignatures() is called
     */
    @Test
    public void testHandleNewSignatureCommandHasNotBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        newSignatureEventHandler.handleNewSignatureCommand(newSignatureDetail, () -> null);
        verify(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handleNewSignatureCommand() is called
     * @then broadcastIfEnoughSignatures() is not called, rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleNewSignatureCommandBroadcastFailure() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(utxoProvider.unregisterUnspents(any(), any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Broadcast failure");
        }));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        newSignatureEventHandler.handleNewSignatureCommand(newSignatureDetail, () -> null);
        verify(newSignatureEventHandler, never()).broadcastIfEnoughSignatures(any(), any(), any());
        verify(btcRollbackService).rollback(any(), any());
        verify(utxoProvider).unregisterUnspents(any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with SignCollector that fails whenever getSignatures() is called
     * @when handleNewSignatureCommand() is called
     * @then rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleNewSignatureCommandGetSignaturesFail() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        Transaction transaction = mock(Transaction.class);
        when(transaction.getHashAsString()).thenReturn("abc");
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(utxoProvider.unregisterUnspents(any(), any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        when(signCollector.getSignatures(anyString())).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Cannot get signatures");
        }));
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        newSignatureEventHandler.handleNewSignatureCommand(newSignatureDetail, () -> null);
        verify(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
        verify(btcRollbackService).rollback(any(), any());
        verify(utxoProvider).unregisterUnspents(any(), any());
    }
}