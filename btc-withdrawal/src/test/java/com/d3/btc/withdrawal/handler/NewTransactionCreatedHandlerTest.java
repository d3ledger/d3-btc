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
import org.bitcoinj.core.Transaction;
import org.junit.Test;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class NewTransactionCreatedHandlerTest {

    /**
     * @given instance of NewTransactionCreatedHandler with BroadcastsProvider that returns true whenever hasBeenBroadcasted() is called
     * @when handleCreateTransactionCommand() is called
     * @then transactions are ignored and not signed
     */
    @Test
    public void testHandleCreateTransactionCommandHasBeenBroadcasted() {
        SignCollector signCollector = mock(SignCollector.class);
        TransactionsStorage transactionsStorage = mock(TransactionsStorage.class);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                    "source account",
                    "destination address",
                    1,
                    System.currentTimeMillis());
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalDetails, transaction);
        }));
        BtcWithdrawalConfig btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        UTXOProvider utxoProvider = mock(UTXOProvider.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> true));
        NewTransactionCreatedHandler newTransactionCreatedHandler = new NewTransactionCreatedHandler(
                signCollector,
                transactionsStorage,
                btcWithdrawalConfig,
                btcRollbackService,
                utxoProvider,
                broadcastsProvider);

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
        SignCollector signCollector = mock(SignCollector.class);
        TransactionsStorage transactionsStorage = mock(TransactionsStorage.class);
        when(transactionsStorage.get(anyString())).thenReturn(Result.Companion.of(() -> {
            WithdrawalDetails withdrawalDetails = new WithdrawalDetails(
                    "source account",
                    "destination address",
                    1,
                    System.currentTimeMillis());
            Transaction transaction = mock(Transaction.class);
            return new Pair<>(withdrawalDetails, transaction);
        }));
        BtcWithdrawalConfig btcWithdrawalConfig = mock(BtcWithdrawalConfig.class);
        BtcRollbackService btcRollbackService = mock(BtcRollbackService.class);
        UTXOProvider utxoProvider = mock(UTXOProvider.class);
        BroadcastsProvider broadcastsProvider = mock(BroadcastsProvider.class);
        when(broadcastsProvider.hasBeenBroadcasted(any(WithdrawalDetails.class))).thenReturn(Result.Companion.of(() -> false));
        NewTransactionCreatedHandler newTransactionCreatedHandler = new NewTransactionCreatedHandler(
                signCollector,
                transactionsStorage,
                btcWithdrawalConfig,
                btcRollbackService,
                utxoProvider,
                broadcastsProvider);

        Commands.SetAccountDetail createdTxCommand = Commands.SetAccountDetail.newBuilder().setKey("abc").build();
        newTransactionCreatedHandler.handleCreateTransactionCommand(createdTxCommand);
        verify(signCollector, times(1)).signAndSave(any(), any());
    }
}
