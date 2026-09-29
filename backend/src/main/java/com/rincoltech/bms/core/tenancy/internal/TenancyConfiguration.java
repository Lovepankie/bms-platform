package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.TenantModules;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenancyProperties.class)
class TenancyConfiguration {

    /** Filter order: after the request id filter, before authentication. */
    static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    TenancyConfiguration(TenancyProperties properties, Environment environment) {
        if (properties.allowTenantHeader() && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException(
                    "bms.tenancy.allow-tenant-header (ALLOW_TENANT_HEADER) is only allowed in the dev and test profiles");
        }
    }

    /** Replaces Spring Boot's transaction manager, so every transaction binds the tenant. */
    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new TenantBindingTransactionManager(dataSource);
    }

    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(
            TenancyProperties properties, JdbcClient jdbc, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new TenantResolutionFilter(properties, jdbc, mapper));
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    WebMvcConfigurer moduleGate(List<ModuleManifest> manifests, TenantModules tenantModules) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ModuleGateInterceptor(manifests, tenantModules))
                        .addPathPatterns("/api/v1/**")
                        .order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }
}
