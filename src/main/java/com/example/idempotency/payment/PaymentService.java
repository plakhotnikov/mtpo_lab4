package com.example.idempotency.payment;

import com.example.idempotency.order.OrderRepository;
import com.example.idempotency.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
    }

    public List<Payment> findAll() {
        return paymentRepository.findAll();
    }

    public Payment findById(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional
    public Payment create(PaymentDto dto) {
        var order = orderRepository.findById(dto.orderId())
                .orElseThrow(() -> new OrderService.OrderNotFoundException(dto.orderId()));
        log.info("Создание платежа для заказа {} на сумму {}", dto.orderId(), dto.amount());
        var payment = new Payment(order, dto.amount());
        return paymentRepository.save(payment);
    }

    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID id) {
            super("Платёж не найден: " + id);
        }
    }
}
