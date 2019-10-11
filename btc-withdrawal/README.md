## Bitcoin withdrawal service
The Bitcoin withdrawal service(or simply `btc-withdrawal`) is a service dedicated to creating and signing Bitcoin withdrawal transactions.

### Simplified flow
1) The service listens to transfer commands in Iroha blockchain
2) If a transfer command is valid (destination address is whitelisted and it has a `base58` format), then the service tries to establish 'withdrawal consensus' among all the nodes. They must decide what UTXO may be spent.
3) Once the 'withdrawal consensus' is reached, the service starts creating a Bitcoin withdrawal transaction.
4) The next step is signing. All the nodes must sign a newly created transaction.
5) If all the nodes(or the majority of them) signed a Bitcoin withdrawal transaction properly, it will be sent to the Bitcoin network.

Assets will be rolled back to the initiator of transfer in case of error or failure.    
### Configuration overview (withdrawal.properties)
* `btc-withdrawal.withdrawalCredential` - D3 clients send assets to this account in order to execute withdrawal. The service reacts to the account's transfers and starts the withdrawal process. The account is also used to perform rollbacks. This account must be multisignature.
* `btc-withdrawal.signatureCollectorCredential` - this account is used to collect Bitcoin withdrawal transaction signatures. The account creates other accounts named after transaction hashes in `btcSignCollect` domain and saves signatures in it.
* `btc-withdrawal.btcConsensusCredential` - this account is used to create 'withdrawal consensus'.
* `btc-withdrawal.registrationCredential` - the account that was used to register D3 clients in the Bitcoin network. This account is needed to get clients whitelists.
* `btc-withdrawal.changeAddressesStorageAccount` - Iroha account that we use to store Bitcoin change addresses.
* `btc-withdrawal.irohaBlockQueue` - name of a RabbitMQ queue that will be used to read Iroha blocks from
* `btc-withdrawal.btcTransfersWalletPath` - a path to wallet file where UTXOs from `btc-deposit` service are stored. 
* `btc-withdrawal.btcKeysWalletPath` - a path to wallet file full of Bitcoin MultiSig addresses private keys. The wallet is used to sign Bitcoin withdrawal transactions.
* `btc-withdrawal.healthCheckPort` - port of health check endpoint. A health check is available on `http://host:healthCheckPort/actuator/health`. This service checks if `btc-withdrawal` is connected to one Bitcoin peer at least. 
* `btc-withdrawal.mstRegistrationAccount` - an account that creates all the Bitcoin MultiSig addresses in D3. Used to get Bitcoin change address.

### How to deploy

The service runs as a part of the `btc-dw-bridge`.