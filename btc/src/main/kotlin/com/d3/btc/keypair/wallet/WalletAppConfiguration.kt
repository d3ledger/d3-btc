package com.d3.btc.keypair.wallet

import com.d3.btc.provider.network.BtcNetworkConfigProvider
import com.d3.btc.wallet.createWalletIfAbsent
import com.d3.commons.config.loadLocalConfigs
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("wallet")
@Configuration
class WalletAppConfiguration {

    private val walletConfig = loadLocalConfigs("wallet", WalletConfig::class.java, "wallet.properties").get()

    @Bean
    fun keysWalletPath(networkProvider: BtcNetworkConfigProvider): String {
        createWalletIfAbsent(walletConfig.btcKeysWalletPath, networkProvider)
        return walletConfig.btcKeysWalletPath
    }
}