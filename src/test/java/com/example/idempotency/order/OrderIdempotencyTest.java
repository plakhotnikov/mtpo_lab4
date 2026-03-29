package com.example.idempotency.order;

import com.example.idempotency.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты идемпотентности для @RestController (OrderController).
 * Демонстрируют работу Idempotency-Key заголовка.
 */
@DisplayName("Идемпотентность @RestController (Orders)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("unused")
class OrderIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private com.example.idempotency.payment.PaymentRepository paymentRepository;

    @Autowired
    private com.example.idempotency.idempotency.IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("POST с Idempotency-Key дважды — создаётся только один заказ")
    void postWithSameIdempotencyKey_shouldReturnCachedResponse() throws Exception {
        var dto = new OrderDto("Тестовый ноутбук", new BigDecimal("89999.99"), null);
        String body = objectMapper.writeValueAsString(dto);
        String idempotencyKey = UUID.randomUUID().toString();

        // Первый запрос — создание
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Тестовый ноутбук"));

        // Второй запрос с тем же ключом — должен вернуть кэшированный ответ
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Тестовый ноутбук"));

        // Проверяем, что создан только один заказ
        Assertions.assertEquals(1, orderRepository.count(),
                "Повторный POST с тем же Idempotency-Key не должен создавать дубликат");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("POST с разными Idempotency-Key — создаются два заказа")
    void postWithDifferentKeys_shouldCreateTwoOrders() throws Exception {
        var dto1 = new OrderDto("Заказ А", new BigDecimal("1000.00"), null);
        var dto2 = new OrderDto("Заказ Б", new BigDecimal("2000.00"), null);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(dto1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(dto2)))
                .andExpect(status().isCreated());

        Assertions.assertEquals(2, orderRepository.count());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("POST без Idempotency-Key — ошибка 400")
    void postWithoutIdempotencyKey_shouldReturn400() throws Exception {
        var dto = new OrderDto("Заказ без ключа", new BigDecimal("500.00"), null);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("PUT идемпотентен — повторное обновление даёт тот же результат")
    void putIsIdempotent_shouldReturnSameResult() throws Exception {
        // Создаём заказ
        var createDto = new OrderDto("Оригинальный заказ", new BigDecimal("1000.00"), null);
        String createResult = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(createResult).get("id").asText();

        // Обновляем дважды с одинаковыми данными
        var updateDto = new OrderDto("Обновлённый заказ", new BigDecimal("1500.00"), OrderStatus.CONFIRMED);
        String updateBody = objectMapper.writeValueAsString(updateDto);

        String firstUpdate = mockMvc.perform(put("/api/orders/" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String secondUpdate = mockMvc.perform(put("/api/orders/" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Результаты PUT должны совпадать (идемпотентность)
        var first = objectMapper.readTree(firstUpdate);
        var second = objectMapper.readTree(secondUpdate);
        Assertions.assertEquals(first.get("description").asText(), second.get("description").asText());
        Assertions.assertEquals(first.get("amount").asDouble(), second.get("amount").asDouble());
        Assertions.assertEquals(first.get("status").asText(), second.get("status").asText());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("DELETE идемпотентен — первый раз 204, второй раз 404")
    void deleteIsIdempotent_shouldReturn204Then404() throws Exception {
        // Создаём заказ
        var dto = new OrderDto("Заказ на удаление", new BigDecimal("500.00"), null);
        String result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(result).get("id").asText();

        // Первый DELETE — успех
        mockMvc.perform(delete("/api/orders/" + orderId))
                .andExpect(status().isNoContent());

        // Второй DELETE — ресурс уже удалён
        mockMvc.perform(delete("/api/orders/" + orderId))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("GET идемпотентен — многократные запросы возвращают одинаковый результат")
    void getIsIdempotent_shouldReturnSameResult() throws Exception {
        var dto = new OrderDto("Стабильный заказ", new BigDecimal("7777.77"), null);
        String result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(result).get("id").asText();

        // Три последовательных GET — результат одинаков
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/orders/" + orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value("Стабильный заказ"))
                    .andExpect(jsonPath("$.amount").value(7777.77));
        }
    }
}
