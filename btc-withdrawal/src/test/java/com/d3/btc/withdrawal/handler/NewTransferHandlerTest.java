package com.d3.btc.withdrawal.handler;

import com.d3.btc.fee.CurrentFeeRate;
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.BroadcastsProvider;
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.statistics.WithdrawalStatistics;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Unit;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class NewTransferHandlerTest {

    private static final String VALID_BTC_ADDRESS = "2N5jiMBpfntoyrXi969mPxK3yzMDAnFpNHb";

    private WithdrawalStatistics withdrawalStatistics;
    private WithdrawalConsensusProvider withdrawalConsensusProvider;
    private BtcRollbackService btcRollbackService;
    private BroadcastsProvider broadcastsProvider;
    private BtcWithdrawalConfig btcWithdrawalConfig;
    private NewTransferHandler handler;
    private static final String BTC_SERVICE_ACCOUNT = "btcService@btc";
    private static final BigDecimal feeValue = new BigDecimal("0.1");

    @Before
    public void setUp() {
        withdrawalStatistics = new WithdrawalStatistics(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        btcRollbackService = mock(BtcRollbackService.class);
        broadcastsProvider = mock(BroadcastsProvider.class);
        btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        handler = spy(new NewTransferHandler(
                withdrawalStatistics,
                btcWithdrawalConfig,
                withdrawalConsensusProvider,
                btcRollbackService,
                broadcastsProvider));
    }

    /**
     * @given instance of NewTransferHandler with mocked BroadcastsProvider that always returns true whenever hasBeenBroadcasted() is called
     * @when handleTransferCommand() is called
     * @then checkAndStartConsensus() is not called
     */
    @Test
    public void testHandleTransferCommandHasBeenBroadcasted() {
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(BTC_SERVICE_ACCOUNT);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAsset = Commands.TransferAsset.newBuilder()
                .setDestAccountId(BTC_SERVICE_ACCOUNT)
                .setAmount("1")
                .build();
        handler.handleTransferCommand(transferAsset, feeValue, System.currentTimeMillis());
        verify(handler, never()).checkAndStartConsensus(any());
        verify(btcRollbackService, never()).rollback(any(WithdrawalDetails.class), anyString());
    }


    /**
     * @given instance of NewTransferHandler with mocked BroadcastsProvider that always returns false whenever hasBeenBroadcasted() is called
     * @when handleTransferCommand() is called
     * @then checkAndStartConsensus() is called
     */
    @Test
    public void testHandleTransferCommandHasNotBeenBroadcasted() {
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(BTC_SERVICE_ACCOUNT);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAsset = Commands.TransferAsset.newBuilder()
                .setDestAccountId(BTC_SERVICE_ACCOUNT)
                .setAmount("1")
                .build();
        handler.handleTransferCommand(transferAsset, feeValue, System.currentTimeMillis());
        verify(handler).checkAndStartConsensus(any());
        verify(btcRollbackService, never()).rollback(any(WithdrawalDetails.class), anyString());
    }

    /**
     * @given instance of NewTransferHandler that fails whenever createConsensusData() is called
     * @when startConsensusProcess() is called
     * @then rollback() is called
     */
    @Test
    public void testStartConsensusProcessFailed() {
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Failed consensus");
        }));
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", VALID_BTC_ADDRESS, 0, System.currentTimeMillis(), 0);
        handler.startConsensusProcess(withdrawalDetails);
        verify(btcRollbackService).rollback(eq(withdrawalDetails), anyString());
    }

    /**
     * @given instance of NewTransferHandler that succeeds whenever createConsensusData() is called
     * @when startConsensusProcess() is called
     * @then rollback() is not called
     */
    @Test
    public void testStartConsensusProcess() {
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", VALID_BTC_ADDRESS, 0, System.currentTimeMillis(), 0);
        handler.startConsensusProcess(withdrawalDetails);
        verify(btcRollbackService, never()).rollback(eq(withdrawalDetails), anyString());
    }

    /**
     * @given instance of NewTransferHandler
     * @when checkAndStartConsensus() is called with no fee being set
     * @then startConsensusProcess() is not called and rollback() is called
     */
    @Test
    public void testCheckAndStartConsensusNoFee() {
        CurrentFeeRate.INSTANCE.clear();
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).startConsensusProcess(any());
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", VALID_BTC_ADDRESS, 10_000, System.currentTimeMillis(), 0);
        handler.checkAndStartConsensus(withdrawalDetails);
        verify(btcRollbackService).rollback(eq(withdrawalDetails), anyString());
        verify(handler, never()).startConsensusProcess(any());
    }

    /**
     * @given instance of NewTransferHandler
     * @when checkAndStartConsensus() is called with invalid destination address
     * @then startConsensusProcess() is not called and rollback() is called
     */
    @Test
    public void testCheckAndStartConsensusInvalidAddress() {
        CurrentFeeRate.INSTANCE.set(10);
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).startConsensusProcess(any());
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", "invalid address", 10_000, System.currentTimeMillis(), 0);
        handler.checkAndStartConsensus(withdrawalDetails);
        verify(btcRollbackService).rollback(eq(withdrawalDetails), anyString());
        verify(handler, never()).startConsensusProcess(any());
    }

    /**
     * @given instance of NewTransferHandler
     * @when checkAndStartConsensus() is called with too little BTC amount
     * @then startConsensusProcess() is not called and rollback() is called
     */
    @Test
    public void testCheckAndStartConsensusDustyAmount() {
        CurrentFeeRate.INSTANCE.set(10);
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).startConsensusProcess(any());
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", VALID_BTC_ADDRESS, 0, System.currentTimeMillis(), 0);
        handler.checkAndStartConsensus(withdrawalDetails);
        verify(btcRollbackService).rollback(eq(withdrawalDetails), anyString());
        verify(handler, never()).startConsensusProcess(any());
    }

    /**
     * @given instance of NewTransferHandler
     * @when checkAndStartConsensus() is called with valid withdrawal details
     * @then startConsensusProcess() is called and rollback() is not called
     */
    @Test
    public void testCheckAndStartConsensus() {
        CurrentFeeRate.INSTANCE.set(10);
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).startConsensusProcess(any());
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "source account id", VALID_BTC_ADDRESS, 10_000, System.currentTimeMillis(), 0);
        handler.checkAndStartConsensus(withdrawalDetails);
        verify(btcRollbackService, never()).rollback(eq(withdrawalDetails), anyString());
        verify(handler).startConsensusProcess(any());
    }

    /**
     * @given instance of NewTransferHandler with BroadcastsProvider that fails whenever hasBeenBroadcasted() is called
     * @when handleTransferCommand() is called
     * @then checkAndStartConsensus() is not called and rollback() is called
     */
    @Test
    public void testHandleTransferCommandBroadcastFailure() {
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(BTC_SERVICE_ACCOUNT);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> {
            throw new RuntimeException("Broadcast provider failure");
        }));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAssetCommand = Commands.TransferAsset.newBuilder()
                .setAmount("10000")
                .setDescription(VALID_BTC_ADDRESS)
                .setSrcAccountId("source account id")
                .setDestAccountId(BTC_SERVICE_ACCOUNT).build();
        handler.handleTransferCommand(transferAssetCommand, feeValue, System.currentTimeMillis());
        verify(btcRollbackService).rollback(any(WithdrawalDetails.class), anyString());
        verify(handler, never()).checkAndStartConsensus(any());
    }

    /**
     * @given instance of NewTransferHandler
     * @when handleTransferCommand() is called with account that is not related to withdrawal
     * @then nothing is called, operation is ignored
     */
    @Test
    public void testHandleTransferCommandBadWithdrawalAccount() {
        String btcServiceAccount = "btcService@btc";
        IrohaCredentialRawConfig irohaCredentialRawConfig = mock(IrohaCredentialRawConfig.class);
        when(irohaCredentialRawConfig.getAccountId()).thenReturn(btcServiceAccount);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredentialRawConfig);
        when(withdrawalConsensusProvider.createConsensusData(any())).thenReturn(Result.Companion.of(() -> Unit.INSTANCE));
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        doNothing().when(handler).checkAndStartConsensus(any());
        Commands.TransferAsset transferAssetCommand = Commands.TransferAsset.newBuilder()
                .setAmount("10000")
                .setDescription(VALID_BTC_ADDRESS)
                .setSrcAccountId("source account id")
                .setDestAccountId("another@account").build();
        handler.handleTransferCommand(transferAssetCommand, feeValue, System.currentTimeMillis());
        verify(handler, never()).checkAndStartConsensus(any());
        verify(btcRollbackService, never()).rollback(any(WithdrawalDetails.class), anyString());
    }
}