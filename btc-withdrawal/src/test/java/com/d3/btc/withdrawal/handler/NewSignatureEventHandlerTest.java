package com.d3.btc.withdrawal.handler;

import com.d3.btc.handler.SetAccountDetailEvent;
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.UTXOProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics;
import com.d3.btc.withdrawal.transaction.SignCollector;
import com.d3.btc.withdrawal.transaction.TransactionsStorage;
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
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
    private NewSignatureEventHandler newSignatureEventHandler;
    private Wallet transferWallet;
    private BtcWithdrawalConfig btcWithdrawalConfig;
    private String signatureCollectorAccountId = "sig_collect@d3";

    @Before
    public void setUp() {
        btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        IrohaCredentialRawConfig irohaCredential = mock(IrohaCredentialRawConfig.class);
        doReturn(signatureCollectorAccountId).when(irohaCredential).getAccountId();
        doReturn(irohaCredential).when(btcWithdrawalConfig).getSignatureCollectorCredential();
        transferWallet = mock(Wallet.class);
        withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        signCollector = mock(SignCollector.class);
        transactionsStorage = mock(TransactionsStorage.class);
        peerGroup = mock(PeerGroup.class);
        broadcastsProvider = mock(BroadcastsProvider.class);
        btcRollbackService = mock(BtcRollbackService.class);

        newSignatureEventHandler = spy(new NewSignatureEventHandler(
                transferWallet,
                btcWithdrawalConfig,
                withdrawalStatistics,
                signCollector,
                transactionsStorage,
                btcRollbackService,
                peerGroup,
                broadcastsProvider));
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then broadcastIfEnoughSignatures() is not called
     */
    @Test
    public void testHandleHasBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalConsensus, Transaction> withdrawal = new Pair<>(withdrawalConsensus, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newSignatureDetail, signatureCollectorAccountId);
        newSignatureEventHandler.handle(event);
        verify(newSignatureEventHandler, never()).broadcastIfEnoughSignatures(any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that returns false whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then broadcastIfEnoughSignatures() is called
     */
    @Test
    public void testHandleHasNotBeenBroadcasted() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalConsensus, Transaction> withdrawal = new Pair<>(withdrawalConsensus, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newSignatureDetail, signatureCollectorAccountId);
        newSignatureEventHandler.handle(event);
        verify(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handle() is called
     * @then broadcastIfEnoughSignatures() is not called, rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleBroadcastFailure() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        Transaction transaction = mock(Transaction.class);
        Pair<WithdrawalConsensus, Transaction> withdrawal = new Pair<>(withdrawalConsensus, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Broadcast failure");
        }));
        doNothing().when(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any());
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newSignatureDetail, signatureCollectorAccountId);
        newSignatureEventHandler.handle(event);
        verify(newSignatureEventHandler, never()).broadcastIfEnoughSignatures(any(), any());
        verify(btcRollbackService).rollback(any(), any(), any());
    }

    /**
     * @given instance of NewSignatureEventHandler with SignCollector that fails whenever getSignatures() is called
     * @when handle() is called
     * @then rollback() and unregisterUnspents() are called
     */
    @Test
    public void testHandleGetSignaturesFail() {
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails("src account id", "to address", 0, System.currentTimeMillis(), 0);
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(new ArrayList<>(), withdrawalDetails,"random id");
        Transaction transaction = mock(Transaction.class);
        when(transaction.getHashAsString()).thenReturn("abc");
        Pair<WithdrawalConsensus, Transaction> withdrawal = new Pair<>(withdrawalConsensus, transaction);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> withdrawal));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        when(signCollector.getSignatures(anyString())).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Cannot get signatures");
        }));
        Commands.SetAccountDetail newSignatureDetail = Commands.SetAccountDetail.newBuilder().setAccountId("test@" + BTC_SIGN_COLLECT_DOMAIN).build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newSignatureDetail, signatureCollectorAccountId);
        newSignatureEventHandler.handle(event);
        verify(newSignatureEventHandler).broadcastIfEnoughSignatures(any(), any());
        verify(btcRollbackService).rollback(any(), any(), any());
    }
}