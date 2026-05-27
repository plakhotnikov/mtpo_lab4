package com.example.idempotency.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Тип контроллера №1: классический @RestController.
 * Аннотационный стиль, как в Spring Guides "Building REST services with Spring".
 *
 * Идемпотентность POST обеспечивается через IdempotencyFilter (заголовок Idempotency-Key).
 * PUT идемпотентен по определению HTTP (повторное применение не меняет результат).
 * DELETE идемпотентен: первый вызов удаляет, второй возвращает 404.
 * GET - safe и идемпотентен.
 *
 * @see <a href="https://spring.io/guides/tutorials/rest">Spring Guides: REST</a>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<Order> list() {
        return orderService.findAll();
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) {
        return orderService.findById(id);
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody OrderDto dto) {
        var order = orderService.create(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PutMapping("/{id}")
    public Order update(@PathVariable UUID id, @Valid @RequestBody OrderDto dto) {
        return orderService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        orderService.delete(id);
    }
}
