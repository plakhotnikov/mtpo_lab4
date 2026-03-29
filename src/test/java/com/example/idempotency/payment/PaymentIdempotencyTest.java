package com.example.idempotency.payment;

import com.example.idempotency.BaseIntegrationTest;
import com.example.idempotency.order.Order;
import com.example.idempotency.order.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты идемпотентности для Functional Endpoints (PaymentRouter/Handler).
 * Демонстрируют работу Idempotency-Key с RouterFunction-стилем.
 */
@DisplayName("Идемпотентность Functional Endpoints (Payments)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private com.example.idempotency.idempotency.IdempotencyKeyRepository idempotencyKeyRepository;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
        testOrder = orderRepository.save(new Order("Заказ для оплаты", new BigDecimal("5000.00")));
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("POST платежа с Idempotency-Key дважды — создаётся только один платёж")
    void postPaymentWithSameKey_shouldCreateOnlyOne() throws Exception {
        var dto = new PaymentDto(testOrder.getId(), new BigDecimal("5000.00"));
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        // Первый запрос
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        // Повтор с тем же ключом
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        Assertions.assertEquals(1, paymentRepository.count(),
                "Повторный POST с тем же ключом не должен создавать второй платёж");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("POST платежей с разными ключами — создаются два платежа")
    void postPaymentsWithDifferentKeys_shouldCreateTwo() throws Exception {
        var dto = new PaymentDto(testOrder.getId(), new BigDecimal("2500.00"));
        String body = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(body))
                .andExpect(status().isCreated());

        Assertions.assertEquals(2, paymentRepository.count());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("POST без Idempotency-Key — ошибка 400")
    void postWithoutKey_shouldReturn400() throws Exception {
        var dto = new PaymentDto(testOrder.getId(), new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("GET платежа — идемпотентен, возвращает одинаковый результат")
    void getPayment_isIdempotent() throws Exception {
        var dto = new PaymentDto(testOrder.getId(), new BigDecimal("3000.00"));
        String result = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String paymentId = objectMapper.readTree(result).get("id").asText();

        // Три GET — одинаковый результат
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/payments/" + paymentId)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amount").value(3000.00));
        }
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Retry после создания — безопасный повтор возвращает тот же результат")
    void retryAfterCreate_shouldBeSafe() throws Exception {
        var dto = new PaymentDto(testOrder.getId(), new BigDecimal("7500.00"));
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        // Имитируем retry: отправляем 3 запроса подряд с одним ключом
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", key)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // Создан ровно один платёж, несмотря на 3 попытки
        Assertions.assertEquals(1, paymentRepository.count(),
                "3 retry с одним ключом должны создать только один платёж");
    }
}
