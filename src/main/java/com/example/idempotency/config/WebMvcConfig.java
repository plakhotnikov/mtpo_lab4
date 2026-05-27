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
     * Регистрация единого фильтра идемпотентности для всех HTTP-транспортов.
     *   - /api/orders, /api/payments  - REST (@RestController, Functional Endpoints)
     *   - /api/soap/*                 - SOAP (Spring-WS MessageDispatcherServlet)
     *   - /graphql                    - GraphQL (spring-boot-starter-graphql, дефолт)
     * Spring Data REST (/api/products) использует @Version + ETag, поэтому
     * сюда не включён. gRPC обслуживается отдельным ServerInterceptor,
     * но через тот же IdempotencyService.
     */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyService idempotencyService,
            IdempotencyProperties properties) {
        var registration = new FilterRegistrationBean<IdempotencyFilter>();
        registration.setFilter(new IdempotencyFilter(idempotencyService, properties));
        registration.addUrlPatterns(
                "/api/orders/*", "/api/orders",
                "/api/payments/*", "/api/payments",
                "/api/soap/*",
                "/graphql"
        );
        registration.setOrder(1);
        return registration;
    }
}
