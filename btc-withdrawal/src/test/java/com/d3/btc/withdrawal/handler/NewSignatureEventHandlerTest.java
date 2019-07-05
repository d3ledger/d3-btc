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
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.d3.commons.sidechain.iroha.IrohaValsKt.BTC_SIGN_COLLECT_DOMAIN;
import static org.mockito.Mockito.*;

public class NewSignatureEventHandlerTest {

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handleNewSignatureCommand() is called
     * @then broadcastIfEnoughSignatures() is not called
     */
    @Test
    public void testHandleNewSignatureCommandHasBeenBroadcasted() {
        WithdrawalStatistics withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        SignCollector signCollector = mock(SignCollector.class);
        TransactionsStorage transactionsStorage = mock(TransactionsStorage.class);
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis());
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        UTXOProvider utxoProvider = mock(UTXOProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        PeerGroup peerGroup = mock(PeerGroup.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        NewSignatureEventHandler newSignatureEventHandler = spy(new NewSignatureEventHandler(
                withdrawalStatistics,
                signCollector,
                transactionsStorage,
                utxoProvider,
                btcRollbackService,
                peerGroup,
                broadcastsProvider));
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
        WithdrawalStatistics withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        SignCollector signCollector = mock(SignCollector.class);
        TransactionsStorage transactionsStorage = mock(TransactionsStorage.class);
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis());
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalDetails, Transaction> withdrawal = new Pair<>(withdrawalDetails, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        UTXOProvider utxoProvider = mock(UTXOProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        PeerGroup peerGroup = mock(PeerGroup.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        NewSignatureEventHandler newSignatureEventHandler = spy(new NewSignatureEventHandler(
                withdrawalStatistics,
                signCollector,
                transactionsStorage,
                utxoProvider,
                btcRollbackService,
                peerGroup,
                broadcastsProvider));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        newSignatureEventHandler.handleNewSignatureCommand(newSignatureDetail, () -> null);
        verify(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any(), any());
    }
}
