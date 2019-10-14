## Bitcoin deposit-withdrawal bridge
The Bitcoin deposit-withdrawal bridge(or simply `btc-dw-bridge`) is a module that combines the Bitcoin deposit and the Bitcoin withdrawal services in a single deployment unit. 
We have to run these service together due to several technical issues and bitcoinj library capabilities.  

### Configuration overview (dw-bridge.properties)
* `btc-dw-bridge.bitcoin.blockStoragePath` - a path to Bitcoin blockchain storage. Only headers are stored. Typically, this folder takes a little amount of disk space (approximately 50-60mb on MainNet).
* `btc-dw-bridge.bitcoin.confidenceLevel` - the minimum depth of deposit transaction in Bitcoin blockchain to be considered as available to spend.
* `btc-dw-bridge.bitcoin.hosts` - a list of Bitcoin full node hosts. Hosts are separated by a comma(`,`) symbol. These hosts are used as a source of Bitcoin blockchain. 
* `btc-dw-bridge.dnsSeedAddresses` - a list of Bitcoin DNS seeds. These addresses are used to discover Bitcoin full nodes. Seeds are separated by a comma(`,`) symbol.
* `btc-dw-bridge.minBlockHeightForPeer` - minimum amount of blocks for a connected Bitcoin node to have. If the connected node has less blocks than specified, it's disconnected.
* `btc-dw-bridge.irohaQueryTimeoutMls` - Iroha query timeout in milliseconds. We need this value to be set in order to improve the service liveness. The service re-reads requested data from Iroha if it fails to do so from the first attempt. If the service reaches the specified timeout, it returns an error.  

### How to deploy
```
  d3-btc-dw-bridge:
    image: nexus.iroha.tech:19002/d3-deploy/btc-dw-bridge:develop
    container_name: d3-btc-dw-bridge
    restart: on-failure
    ports:
      - 7074:7074
    volumes:
      - ..{folder where Bitcoin data(keys,transactions and headers) are stored on a host machine}:/{folder where Bitcoin data is stored inside a container}
```