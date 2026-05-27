package com.example.idempotency.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.servlet.function.RequestPredicates.*;

/**
 * Тип контроллера №2: Functional Endpoints (RouterFunction).
 * Маршрутизация определяется программно через RouterFunction,
 * а не декларативно через аннотации @GetMapping/@PostMapping.
 *
 * Это кардинально отличается от @RestController:
 * - Нет аннотаций маршрутизации
 * - Композиция маршрутов через функциональные комбинаторы
 * - Обработчики - чистые функции ServerRequest -> ServerResponse
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html">
 *     Spring Framework: Functional Endpoints</a>
 */
@Configuration
public class PaymentRouter {

    @Bean
    public RouterFunction<ServerResponse> paymentRoutes(PaymentHandler handler) {
        return RouterFunctions.route()
                .path("/api/payments", builder -> builder
                        .GET("", accept(APPLICATION_JSON), handler::list)
                        .GET("/{id}", accept(APPLICATION_JSON), handler::getById)
                        .POST("", contentType(APPLICATION_JSON), handler::create)
                )
                .build();
    }
}
