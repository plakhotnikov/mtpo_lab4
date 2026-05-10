package com.example.idempotency.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Order findById(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public Order create(OrderDto dto) {
        log.info("Создание заказа: {} на сумму {}", dto.description(), dto.amount());
        var order = new Order(dto.description(), dto.amount());
        return orderRepository.save(order);
    }

    @Transactional
    public Order update(UUID id, OrderDto dto) {
        var order = findById(id);
        order.setDescription(dto.description());
        order.setAmount(dto.amount());
        if (dto.status() != null) {
            order.setStatus(dto.status());
        }
        log.info("Обновление заказа {}: {} на сумму {}", id, dto.description(), dto.amount());
        return orderRepository.save(order);
    }

    @Transactional
    public void delete(UUID id) {
        var order = findById(id);
        log.info("Удаление заказа {}", id);
        orderRepository.delete(order);
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("Order not found: " + id);
        }
    }
}
