package com.d3.btc.withdrawal.handler;

import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class NewTransferHandlerTest {

    /**
     * @given instance of NewTransferHandler with mocked BroadcastsProvider that always returns true whenever hasBeenBroadcasted() is called
     * @when handleTransferCommand() is called
     * @then checkAndStartConsensus() is not called
     */
    @Test
    public void testHandleTransferCommandHasBeenBroadcasted() {
        String btcServiceAccount = "btcService@btc";
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(btcServiceAccount);
        WithdrawalStatistics withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        BtcWithdrawalConfig btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        WithdrawalConsensusProvider withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        NewTransferHandler handler = spy(new NewTransferHandler(
                withdrawalStatistics,
                btcWithdrawalConfig,
                withdrawalConsensusProvider,
                btcRollbackService,
                broadcastsProvider));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAsset = Commands.TransferAsset.newBuilder()
                .setDestAccountId(btcServiceAccount)
                .setAmount("1")
                .build();
        handler.handleTransferCommand(transferAsset, System.currentTimeMillis());
        verify(handler, never()).checkAndStartConsensus(any());
    }


    /**
     * @given instance of NewTransferHandler with mocked BroadcastsProvider that always returns false whenever hasBeenBroadcasted() is called
     * @when handleTransferCommand() is called
     * @then checkAndStartConsensus() is called
     */
    @Test
    public void testHandleTransferCommandHasNotBeenBroadcasted() {
        String btcServiceAccount = "btcService@btc";
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(btcServiceAccount);
        WithdrawalStatistics withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        BtcWithdrawalConfig btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        WithdrawalConsensusProvider withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        NewTransferHandler handler = spy(new NewTransferHandler(
                withdrawalStatistics,
                btcWithdrawalConfig,
                withdrawalConsensusProvider,
                btcRollbackService,
                broadcastsProvider));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAsset = Commands.TransferAsset.newBuilder()
                .setDestAccountId(btcServiceAccount)
                .setAmount("1")
                .build();
        handler.handleTransferCommand(transferAsset, System.currentTimeMillis());
        verify(handler).checkAndStartConsensus(any());
    }
}
