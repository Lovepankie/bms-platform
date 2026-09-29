package com.rincoltech.bms.kernel;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    /**
     * Makes the OpenAPI schemas show the snake_case field names the API actually sends (chapter 7
     * section 7.5). The application serialises with Jackson 3 and the global SNAKE_CASE strategy
     * ({@code spring.jackson.property-naming-strategy}); swagger-core, which builds the schemas,
     * is still on Jackson 2, so it gets its own mapper with the same strategy. This is the only
     * Jackson 2 code in the application.
     */
    @Bean
    ModelResolver openApiModelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}
