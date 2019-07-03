package com.d3.btc.withdrawal.handler;

import com.d3.btc.withdrawal.provider.WithdrawalConsensusProvider;
import com.d3.btc.withdrawal.service.BtcRollbackService;
import com.d3.btc.withdrawal.service.WithdrawalTransferService;
import com.d3.btc.withdrawal.transaction.WithdrawalConsensus;
import com.d3.btc.withdrawal.transaction.WithdrawalDetails;
import com.github.kittinunf.result.Result;
import iroha.protocol.Commands;
import kotlin.Pair;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static com.d3.commons.sidechain.iroha.IrohaValsKt.BTC_CONSENSUS_DOMAIN;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NewConsensusDataHandlerTest {

    /**
     * @given instance of NewConsensusDataHandler with WithdrawalConsensusProvider returning true whenever hasBeenEstablished() is called
     * @when handleNewConsensusCommand() is called
     * @then withdraw() is not called
     */
    @Test
    public void testHandleNewConsensusCommandHasBeenEstablished() {
        WithdrawalTransferService withdrawalTransferService = mock(WithdrawalTransferService.class);
        WithdrawalConsensusProvider withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "src account id",
                "to address",
                0,
                System.currentTimeMillis());
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(0, 0);
        List<WithdrawalConsensus> withdrawalConsensusList = Arrays.asList(withdrawalConsensus, withdrawalConsensus, withdrawalConsensus);
        Pair<WithdrawalDetails, List<WithdrawalConsensus>> consensus = new Pair<>(withdrawalDetails, withdrawalConsensusList);
        when(withdrawalConsensusProvider.getConsensus(anyString())).thenReturn(Result.Companion.of(() -> consensus));
        when(withdrawalConsensusProvider.hasBeenEstablished(anyString())).thenReturn(Result.Companion.of(() -> true));
        NewConsensusDataHandler newConsensusDataHandler = new NewConsensusDataHandler(
                withdrawalTransferService,
                withdrawalConsensusProvider,
                btcRollbackService);
        Commands.SetAccountDetail newConsensusCommand = Commands.SetAccountDetail.newBuilder()
                .setAccountId("test@" + BTC_CONSENSUS_DOMAIN)
                .setValue(withdrawalConsensus.toJson())
                .build();
        newConsensusDataHandler.handleNewConsensusCommand(newConsensusCommand);
        verify(withdrawalTransferService, never()).withdraw(any(), any());
    }

    /**
     * @given instance of NewConsensusDataHandler with WithdrawalConsensusProvider returning false whenever hasBeenEstablished() is called
     * @when handleNewConsensusCommand() is called
     * @then withdraw() is called
     */
    @Test
    public void testHandleNewConsensusCommandHasNotBeenEstablished() {
        WithdrawalTransferService withdrawalTransferService = mock(WithdrawalTransferService.class);
        WithdrawalConsensusProvider withdrawalConsensusProvider = mock(WithdrawalConsensusProvider.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                "src account id",
                "to address",
                0,
                System.currentTimeMillis());
        WithdrawalConsensus withdrawalConsensus = new WithdrawalConsensus(0, 0);
        List<WithdrawalConsensus> withdrawalConsensusList = Arrays.asList(withdrawalConsensus, withdrawalConsensus, withdrawalConsensus);
        Pair<WithdrawalDetails, List<WithdrawalConsensus>> consensus = new Pair<>(withdrawalDetails, withdrawalConsensusList);
        when(withdrawalConsensusProvider.getConsensus(anyString())).thenReturn(Result.Companion.of(() -> consensus));
        when(withdrawalConsensusProvider.hasBeenEstablished(anyString())).thenReturn(Result.Companion.of(() -> false));
        NewConsensusDataHandler newConsensusDataHandler = new NewConsensusDataHandler(
                withdrawalTransferService,
                withdrawalConsensusProvider,
                btcRollbackService);
        Commands.SetAccountDetail newConsensusCommand = Commands.SetAccountDetail.newBuilder()
                .setAccountId("test@" + BTC_CONSENSUS_DOMAIN)
                .setValue(withdrawalConsensus.toJson())
                .build();
        newConsensusDataHandler.handleNewConsensusCommand(newConsensusCommand);
        verify(withdrawalTransferService).withdraw(any(), any());
    }
}
