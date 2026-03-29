package com.example.idempotency.config;

import com.example.idempotency.idempotency.IdempotencyFilter;
import com.example.idempotency.idempotency.IdempotencyService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class WebMvcConfig {

    /**
     * Регистрация фильтра идемпотентности для путей /api/orders и /api/payments.
     * Spring Data REST (/products) использует @Version, а не заголовок.
     */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyService idempotencyService,
            IdempotencyProperties properties) {
        var registration = new FilterRegistrationBean<IdempotencyFilter>();
        registration.setFilter(new IdempotencyFilter(idempotencyService, properties));
        registration.addUrlPatterns("/api/orders/*", "/api/orders", "/api/payments/*", "/api/payments");
        registration.setOrder(1);
        return registration;
    }
}
