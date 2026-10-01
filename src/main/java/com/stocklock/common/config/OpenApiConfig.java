package com.stocklock.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockLockOpenApi() {
        return new OpenAPI().info(new Info()
                .title("StockLock API")
                .version("v1")
                .description("""
                        Inventory reservation and order API. Reservations hold stock for a limited time and \
                        are created with an Idempotency-Key so that client retries never reserve twice."""));
    }
}
