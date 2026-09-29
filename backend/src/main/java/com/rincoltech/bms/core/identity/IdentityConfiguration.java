package com.rincoltech.bms.core.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {

    /** After tenant resolution (the token's tenant must match the host's, section 7.4.2). */
    static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    IdentityConfiguration(@Value("${bms.auth.mode:none}") String mode, Environment environment) {
        if (!mode.equals("none") && !mode.equals("dev")) {
            throw new IllegalStateException("bms.auth.mode must be 'none' or 'dev', not '" + mode + "'");
        }
        if (mode.equals("dev") && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("AUTH_MODE=dev is only allowed in the dev and test profiles");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "bms.auth.mode", havingValue = "dev")
    FilterRegistrationBean<DevAuthenticationFilter> devAuthenticationFilter() {
        var registration = new FilterRegistrationBean<>(new DevAuthenticationFilter());
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    WebMvcConfigurer permissionEnforcement() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new PermissionInterceptor()).addPathPatterns("/**");
            }
        };
    }
}
