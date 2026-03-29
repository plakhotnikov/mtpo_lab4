package com.example.idempotency.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO для создания платежа.
 */
public record PaymentDto(
        UUID orderId,
        BigDecimal amount
) {}
