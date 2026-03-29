package com.example.idempotency.data;

import com.example.idempotency.BaseIntegrationTest;
import com.example.idempotency.order.Order;
import com.example.idempotency.order.OrderDto;
import com.example.idempotency.order.OrderRepository;
import com.example.idempotency.product.Product;
import com.example.idempotency.product.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тест импорта данных из JSON и CSV.
 * Загружает эталонные входные данные, выполняет операции,
 * сравнивает результат с ожидаемым выходом.
 */
@DisplayName("Импорт данных и сравнение с эталоном")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataImportTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private com.example.idempotency.payment.PaymentRepository paymentRepository;

    @Autowired
    private com.example.idempotency.idempotency.IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Импорт заказов из JSON и сравнение с эталонным выходом")
    void importOrdersFromJson_andCompareWithExpected() throws Exception {
        // Загрузка входных данных из JSON
        InputStream input = getClass().getResourceAsStream("/data/orders-input.json");
        List<OrderDto> orders = objectMapper.readValue(input, new TypeReference<>() {});

        // Создание заказов через API
        for (OrderDto dto : orders) {
            mockMvc.perform(post("/api/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated());
        }

        // Загрузка эталонного выхода
        InputStream expected = getClass().getResourceAsStream("/data/expected-orders-output.json");
        JsonNode expectedOutput = objectMapper.readTree(expected);

        // Сравнение
        List<Order> savedOrders = orderRepository.findAll();
        Assertions.assertEquals(
                expectedOutput.get("totalOrders").asInt(),
                savedOrders.size(),
                "Количество заказов должно совпадать с эталоном"
        );

        for (int i = 0; i < savedOrders.size(); i++) {
            JsonNode expectedOrder = expectedOutput.get("orders").get(i);
            Order actual = savedOrders.stream()
                    .filter(o -> o.getDescription().equals(expectedOrder.get("description").asText()))
                    .findFirst()
                    .orElseThrow();

            Assertions.assertEquals(
                    expectedOrder.get("status").asText(),
                    actual.getStatus().name(),
                    "Статус заказа '" + actual.getDescription() + "' должен совпадать с эталоном"
            );
            Assertions.assertTrue(
                    new BigDecimal(expectedOrder.get("amount").asText())
                            .compareTo(actual.getAmount()) == 0,
                    "Сумма заказа '" + actual.getDescription() + "' должна совпадать с эталоном"
            );
        }
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Импорт товаров из CSV")
    void importProductsFromCsv() throws Exception {
        // Чтение CSV с помощью Jackson CSV
        CsvMapper csvMapper = new CsvMapper();
        CsvSchema schema = CsvSchema.emptySchema().withHeader();

        InputStream csvInput = getClass().getResourceAsStream("/data/products-input.csv");
        var iterator = csvMapper.readerFor(java.util.Map.class)
                .with(schema)
                .readValues(csvInput);

        int count = 0;
        while (iterator.hasNext()) {
            @SuppressWarnings("unchecked")
            var row = (java.util.Map<String, String>) iterator.next();

            var product = new Product(
                    row.get("sku"),
                    row.get("name"),
                    new BigDecimal(row.get("price")),
                    Integer.parseInt(row.get("quantity"))
            );
            productRepository.save(product);
            count++;
        }

        Assertions.assertEquals(3, count, "Должны быть импортированы 3 товара из CSV");
        Assertions.assertEquals(3, productRepository.count());

        // Проверка идемпотентности: повторный импорт не должен создать дубликатов
        // (unique constraint на sku предотвратит)
        csvInput = getClass().getResourceAsStream("/data/products-input.csv");
        var iterator2 = csvMapper.readerFor(java.util.Map.class)
                .with(schema)
                .readValues(csvInput);

        int duplicateAttempts = 0;
        while (iterator2.hasNext()) {
            @SuppressWarnings("unchecked")
            var row = (java.util.Map<String, String>) iterator2.next();
            try {
                var product = new Product(
                        row.get("sku"),
                        row.get("name"),
                        new BigDecimal(row.get("price")),
                        Integer.parseInt(row.get("quantity"))
                );
                productRepository.save(product);
            } catch (Exception e) {
                duplicateAttempts++;
            }
        }

        Assertions.assertEquals(3, duplicateAttempts,
                "Все 3 повторных импорта должны быть отклонены unique constraint");
        Assertions.assertEquals(3, productRepository.count(),
                "Количество товаров не должно измениться после повторного импорта");
    }
}
