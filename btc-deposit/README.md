## Bitcoin deposit service
The Bitcoin deposit service(or simply `btc-deposit`) is here to listen to Bitcoin blockchain transactions and increase clients balances in Iroha blockchain. 

### Simplified flow
1) The service creates Bitcoin blockchain listeners that listen to transactions where coins were sent to our clients. The listener waits until the transaction of interest reaches at least 6 (or another amount of blocks which may be specified) blocks in depth. Once it happens, Iroha 'increase balance' transaction is created. This 'increase' works in a multisignature fashion. So every node must create the same Iroha transaction. Bitcoin block time is used as a source of time for the Iroha 'increase balance' transaction. 
2) Then it starts Bitcoin blockchain downloading process (only headers are stored on the disk). This process triggers the listeners.

### Configuration overview (deposit.properties)
* `btc-deposit.registrationAccount` - this account stores registered Bitcoin addresses associated with D3 clients. This information is used to check if a Bitcoin transaction is related to our clients.
* `btc-deposit.healthCheckPort` - port of health check endpoint. A health check is available on `http://host:healthCheckPort/actuator/health`. This service checks if `btc-deposit` is connected to one Bitcoin peer at least.
* `btc-deposit.btcTransferWalletPath` - a path of wallet file where deposit transactions are stored. We need this wallet to use deposit transactions as UTXO (Unspent Transaction Output) in the withdrawal service.
* `btc-deposit.notaryCredential` - credentials of the Notary account. This account is used to create 'increase balance' transactions in Iroha. Must be a multisignature one.
* `btc-deposit.irohaBlockQueue` - name of the RabbitMQ queue to read Iroha blocks from

### How to deploy
The service runs as a part of the `btc-dw-bridge`. 