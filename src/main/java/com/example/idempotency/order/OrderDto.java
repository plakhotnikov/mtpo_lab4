package com.example.idempotency.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * DTO для создания/обновления заказа.
 */
public record OrderDto(
        @NotBlank(message = "Описание заказа обязательно")
        String description,

        @NotNull(message = "Сумма заказа обязательна")
        @DecimalMin(value = "0.01", message = "Сумма должна быть положительной")
        BigDecimal amount,

        OrderStatus status
) {}
