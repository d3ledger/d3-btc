package com.d3.btc.withdrawal.handler;

import com.d3.btc.handler.SetAccountDetailEvent;
import com.d3.btc.withdrawal.config.BtcWithdrawalConfig;
import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.service.WithdrawalTransferService;
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.d3.commons.config.IrohaCredentialRawConfig;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.d3.btc.config.BitcoinConfigKt.BTC_CONSENSUS_DOMAIN;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NewConsensusDataHandlerTest {

    private NewConsensusDataHandler newConsensusDataHandler;
    private WithdrawalDetails withdrawalDetails;
    private WithdrawalTransferService withdrawalTransferService;
    private BtcRollbackService btcRollbackService;
    private WithdrawalConsensusProvider withdrawalConsensusProvider;
    private String consensusCredentialAccountId = "consensus@btc";
    private BtcWithdrawalConfig btcWithdrawalConfig;

    @Before
    public void setUp() {
        IrohaCredentialRawConfig irohaCredential = mock(IrohaCredentialRawConfig.class);
        doReturn(consensusCredentialAccountId).when(irohaCredential).getAccountId();
        btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        when(btcWithdrawalConfig.getWithdrawalCredential()).thenReturn(irohaCredential);
        doReturn(irohaCredential).when(btcWithdrawalConfig).getWithdrawalCredential();
        withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        btcRollbackService = mock(BtcRollbackService.class);
        withdrawalTransferService = mock(WithdrawalTransferService.class);
        withdrawalDetails = new WithdrawalDetails(
                "src account id",
                "to address",
                0,
                System.currentTimeMillis(),
                0);
        newConsensusDataHandler = new NewConsensusDataHandler(
                withdrawalTransferService,
                withdrawalConsensusProvider,
                btcRollbackService,
                btcWithdrawalConfig);

    }

    /**
     * @given instance of NewConsensusDataHandler with WithdrawalConsensusProvider returning true whenever hasBeenEstablished() is called
     * @when handle() is called
     * @then withdraw() is not called
     */
    @Test
    public void testHandleHasBeenEstablished() {
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(0, 0);
        List<WithdrawalConsensus> withdrawalConsensusList = Arrays.asList(withdrawalConsensus, withdrawalConsensus, withdrawalConsensus);
        Pair<WithdrawalDetails, List<WithdrawalConsensus>> consensus = new Pair<>(withdrawalDetails, withdrawalConsensusList);
        when(withdrawalConsensusProvider.getConsensus(anyString())).thenReturn(Result.Companion.of(() -> consensus));
        when(withdrawalConsensusProvider.hasBeenEstablished(anyString())).thenReturn(Result.Companion.of(() -> true));
        Commands.SetAccountDetail newConsensusCommand = Commands.SetAccountDetail.newBuilder()
                .setAccountId("test@" + BTC_CONSENSUS_DOMAIN)
                .setValue(withdrawalConsensus.toJson())
                .build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newConsensusCommand, consensusCredentialAccountId);
        newConsensusDataHandler.handle(event);
        verify(withdrawalTransferService, never()).withdraw(any(), any());
    }

    /**
     * @given instance of NewConsensusDataHandler with WithdrawalConsensusProvider returning false whenever hasBeenEstablished() is called
     * @when handle() is called
     * @then withdraw() is called
     */
    @Test
    public void testHandleHasNotBeenEstablished() {
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(0, 0);
        List<WithdrawalConsensus> withdrawalConsensusList = Arrays.asList(withdrawalConsensus, withdrawalConsensus, withdrawalConsensus);
        Pair<WithdrawalDetails, List<WithdrawalConsensus>> consensus = new Pair<>(withdrawalDetails, withdrawalConsensusList);
        when(withdrawalConsensusProvider.getConsensus(anyString())).thenReturn(Result.Companion.of(() -> consensus));
        when(withdrawalConsensusProvider.hasBeenEstablished(anyString())).thenReturn(Result.Companion.of(() -> false));
        Commands.SetAccountDetail newConsensusCommand = Commands.SetAccountDetail.newBuilder()
                .setAccountId("test@" + BTC_CONSENSUS_DOMAIN)
                .setValue(withdrawalConsensus.toJson())
                .build();
        SetAccountDetailEvent event = new SetAccountDetailEvent(newConsensusCommand, consensusCredentialAccountId);
        newConsensusDataHandler.handle(event);
        verify(withdrawalTransferService).withdraw(any(), any());
    }
}
