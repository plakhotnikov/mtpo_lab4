package com.example.idempotency.profile;

import com.example.idempotency.order.OrderDto;
import com.example.idempotency.order.OrderRepository;
import com.example.idempotency.payment.PaymentDto;
import com.example.idempotency.payment.PaymentRepository;
import com.example.idempotency.order.Order;
import com.example.idempotency.product.Product;
import com.example.idempotency.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты с профилем non-idempotent.
 * Демонстрируют НЕКОРРЕКТНОЕ поведение системы без защиты идемпотентности:
 * - Дублирование при повторных POST
 * - Отсутствие проверки Idempotency-Key
 * - Создание дубликатов SKU
 *
 * Эти тесты проходят ЗЕЛЁНЫМИ, подтверждая наличие проблем.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles({"test", "test-non-idempotent"})
@DisplayName("Профиль non-idempotent: демонстрация проблем без идемпотентности")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NonIdempotentProfileTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("idempotency_test_noidemp")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("BUG: POST без Idempotency-Key проходит (фильтр отключён)")
    void postWithoutKey_shouldSucceed_whenFilterDisabled() throws Exception {
        var dto = new OrderDto("Заказ без ключа", new BigDecimal("1000.00"), null);

        // Без идемпотентности POST без ключа проходит (это проблема!)
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("BUG: Повторный POST с тем же Idempotency-Key создаёт дубликат")
    void postWithSameKey_shouldCreateDuplicate_whenFilterDisabled() throws Exception {
        var dto = new OrderDto("Дублирующийся заказ", new BigDecimal("5000.00"), null);
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        // Оба запроса проходят — фильтр отключён
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        // Меняем описание, чтобы не попасть на unique constraint (description, amount)
        var dto2 = new OrderDto("Дублирующийся заказ (копия)", new BigDecimal("5000.01"), null);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(dto2)))
                .andExpect(status().isCreated());

        Assertions.assertTrue(orderRepository.count() >= 2,
                "Без идемпотентности повторный POST создаёт дубликат — это баг!");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("BUG: Дубликат SKU создаётся (unique constraint удалён)")
    void duplicateSku_shouldBeCreated_whenConstraintRemoved() throws Exception {
        String productJson = """
                {
                    "sku": "DUP-SKU-001",
                    "name": "Товар 1",
                    "price": 100.00,
                    "quantity": 10
                }
                """;

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson))
                .andExpect(status().isCreated());

        // Тот же SKU — без constraint проходит (это проблема!)
        String productJson2 = """
                {
                    "sku": "DUP-SKU-001",
                    "name": "Товар 1 дубликат",
                    "price": 100.00,
                    "quantity": 10
                }
                """;

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson2))
                .andExpect(status().isCreated());

        Assertions.assertTrue(productRepository.count() >= 2,
                "Без unique constraint создаются дубликаты SKU — это баг!");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("BUG: Retry платежа создаёт множественные списания")
    void retryPayment_shouldCreateMultiple_whenFilterDisabled() throws Exception {
        var order = orderRepository.save(new Order("Заказ для retry", new BigDecimal("10000.00")));

        var dto = new PaymentDto(order.getId(), new BigDecimal("10000.00"));
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        // 3 retry — создаются 3 платежа (тройное списание!)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", key)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        Assertions.assertEquals(3, paymentRepository.count(),
                "Без идемпотентности 3 retry создают 3 платежа — тройное списание!");
    }
}
