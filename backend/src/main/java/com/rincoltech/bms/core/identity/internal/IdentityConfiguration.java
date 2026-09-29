package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.BusinessClock;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class IdentityConfiguration {

    /** After tenant resolution (the token's tenant must match the host's, section 7.4.2). */
    static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    IdentityConfiguration(AuthProperties properties, Environment environment) {
        String mode = properties.mode();
        if (!mode.equals("none") && !mode.equals("dev")) {
            throw new IllegalStateException("bms.auth.mode must be 'none' or 'dev', not '" + mode + "'");
        }
        if (mode.equals("dev") && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("AUTH_MODE=dev is only allowed in the dev and test profiles");
        }
    }

    @Bean
    FilterRegistrationBean<AuthenticationFilter> authenticationFilter(
            AuthProperties properties,
            AccessTokens tokens,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Grants grants,
            PlatformHost hosts,
            ObjectMapper mapper,
            BusinessClock clock) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        var registration = new FilterRegistrationBean<>(new AuthenticationFilter(
                tokens,
                jdbc,
                readOnly,
                grants,
                hosts,
                mapper,
                clock,
                properties.mode().equals("dev")));
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    WebMvcConfigurer permissionEnforcement(
            JdbcClient jdbc, AuditLog audit, PlatformTransactionManager transactionManager) {
        // Reference data (no tenant policy), read once on first use.
        Supplier<Set<String>> moneyMoving =
                SingletonSupplier.of(() -> Set.copyOf(jdbc.sql("SELECT key FROM permissions WHERE is_money_moving")
                        .query(String.class)
                        .list()));
        PermissionInterceptor interceptor =
                new PermissionInterceptor(moneyMoving, audit, new TransactionTemplate(transactionManager));
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**");
            }
        };
    }
}
