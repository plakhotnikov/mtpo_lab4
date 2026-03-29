package com.example.idempotency.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.util.UUID;

/**
 * Тип контроллера №2: Functional Endpoints (HandlerFunction).
 * Обработчик запросов в функциональном стиле Spring WebMvc.fn.
 *
 * В отличие от @RestController, здесь нет аннотаций маршрутизации.
 * Маршруты определяются в PaymentRouter через RouterFunction.
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/web/webmvc-functional.html">
 *     Spring Framework: Functional Endpoints</a>
 */
@Component
public class PaymentHandler {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentHandler(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    /** GET /api/payments — список всех платежей */
    public ServerResponse list(ServerRequest request) {
        var payments = paymentService.findAll();
        return ServerResponse.ok().body(payments);
    }

    /** GET /api/payments/{id} — получить платёж по ID */
    public ServerResponse getById(ServerRequest request) {
        var id = UUID.fromString(request.pathVariable("id"));
        try {
            var payment = paymentService.findById(id);
            return ServerResponse.ok().body(payment);
        } catch (PaymentService.PaymentNotFoundException e) {
            return ServerResponse.notFound().build();
        }
    }

    /** POST /api/payments — создать платёж (идемпотентность через IdempotencyFilter) */
    public ServerResponse create(ServerRequest request) throws Exception {
        var dto = objectMapper.readValue(request.servletRequest().getInputStream(), PaymentDto.class);
        var payment = paymentService.create(dto);
        return ServerResponse
                .created(URI.create("/api/payments/" + payment.getId()))
                .body(payment);
    }
}
