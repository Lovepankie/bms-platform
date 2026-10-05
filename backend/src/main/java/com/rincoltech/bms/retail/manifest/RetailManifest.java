package com.rincoltech.bms.retail.manifest;

import com.rincoltech.bms.core.tenancy.ModuleManifest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetailManifest {

    static final String MODULE_KEY = "retail";

    @Bean
    ModuleManifest retailModule() {
        return new ModuleManifest(MODULE_KEY);
    }
}
