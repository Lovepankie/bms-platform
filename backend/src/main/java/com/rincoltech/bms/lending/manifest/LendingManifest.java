package com.rincoltech.bms.lending.manifest;

import com.rincoltech.bms.core.tenancy.ModuleManifest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LendingManifest {

    static final String MODULE_KEY = "lending";

    @Bean
    ModuleManifest lendingModule() {
        return new ModuleManifest(MODULE_KEY);
    }
}
