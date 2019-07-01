package com.d3.btc.keypair.securosys

import com.d3.commons.config.loadRawLocalConfigs
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile


@Profile("securosys")
@Configuration
class SecuroSysAppConfiguration {

    private val securoSysConfig = loadRawLocalConfigs("securosys", SecuroSysConfig::class.java, "securosys.properties")

    @Bean
    fun securoSysConfig() = securoSysConfig
}