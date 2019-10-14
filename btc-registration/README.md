## Bitcoin registration service
The Bitcoin registration service (or simply `btc-registration`) is a REST based service responsible for assigning Bitcoin MultiSig addresses to Iroha clients.
### Configuration overview (registration.properties)
* `btc-registration.registrationAccount` - Iroha account that registers clients in D3
* `btc-registration.mstRegistrationAccount` - Iroha account that creates Bitcoin MultiSig addresses
* `btc-registration.freeAddressesStorageAccount` - Iroha account that stores free to register Bitcoin MultiSig addresses. 
* `btc-registration.nodeId` - identifier of the node. This identifier must correlate to an identifier that is set in `btc-address-generation` configuration file on the same node. This value must be different on different nodes.
* `btc-registration.irohaQueryTimeoutMls` - Iroha query timeout in milliseconds. We need this value to be set in order to improve the service liveness. The service re-reads requested data from Iroha if it fails to do so from the first attempt. If the service reaches the specified timeout, it returns an error.  
* `btc-registration.port` - HTTP port of the service 

### How to deploy
```
d3-btc-registration:
    image: nexus.iroha.tech:19002/d3-deploy/btc-registration:develop
    container_name: d3-btc-registration
    restart: on-failure
    ports:
      - 8086:8086
```