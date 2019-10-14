## Bitcoin address generation service
The Bitcoin address generation service (or simply `btc-address-generation`) is used to create Bitcoin MultiSig addresses that may be registered by D3 clients later. 
The service is also responsible for creating change addresses (addresses that store BTC change). 

### Glossary
 Free address - Bitcoin MultiSig address that may be registered by any D3 client.
 
 Change address - Bitcoin MultiSig address that is used by withdrawal service to store BTC change.
 
### Simplified flow
1) The service waits for a special command that triggers the address generation process. Free addresses are also generated on D3 client registration because one registration takes exactly one MultiSig address. 
2) Once this command appears, the service starts key pair creation and saves it in `keys.d3.wallet` file and Iroha(only public keys go to Iroha).  
3) Then, the service waits until enough public keys are collected in Iroha. Every node must create one key pair and save its public key in Iroha. If enough key pairs are created, the service generates a new Bitcoin MultiSig address using public keys created by all nodes. Then, MultiSig address is saved in `keys.d3.wallet` file and in Iroha key-value storage. 

### Configuration overview (address_generation.properties)

* `btc-address-generation.mstRegistrationAccount` - Iroha account that is responsible for Bitcoin MultiSig addresses creation. The account must be multisignature.
* `btc-address-generation.btcKeysWalletPath` - path to wallet file where key pairs will be stored. This file contains confidential information. 
* `btc-address-generation.notaryAccount` -  Iroha account that is responsible for Bitcoin MultiSig addresses storage. Addresses are stored in this account details. Probably, this is not a very good candidate for that purpose.
* `btc-address-generation.changeAddressesStorageAccount` -  Iroha account that is responsible for change addresses storage. Addresses are stored in this account details.
* `btc-address-generation.healthCheckPort` - port of health check endpoint. A health check is available on `http://host:healthCheckPort/actuator/health`. This service checks if `btc-address-generation` is able to listen to Iroha blocks.
* `btc-address-generation.threshold` - a number of MultiSig addresses that must be created in advance.
* `btc-address-generation.nodeId` - identifier of the node. This identifier must correlate to an identifier that is set in `btc-registration` configuration file on the same node. This value must be different on different nodes.
* `btc-address-generation.irohaBlockQueue` - name of the RabbitMQ queue to read Iroha blocks from
* `btc-address-generation.irohaQueryTimeoutMls` - Iroha query timeout in milliseconds. We need this value to be set in order to improve the service liveness. The service re-reads requested data from Iroha if it fails to do so from the first attempt. If the service reaches the specified timeout, it returns an error.  

### How to deploy
``` 
  d3-btc-address-generation:
    image: nexus.iroha.tech:19002/d3-deploy/btc-address-generation:develop
    container_name: d3-btc-address-generation
    restart: on-failure
    ports:
      - 7071:7071
    volumes:
      - ..{folder where Bitcoin keys are stored on a host machine}:/{folder where Bitcoin keys are stored inside a container}
    networks:
```