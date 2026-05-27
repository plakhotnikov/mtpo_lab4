package com.example.idempotency.product;

import com.example.idempotency.BaseIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты идемпотентности для Spring Data REST (ProductRepository).
 * Демонстрируют @Version (optimistic locking), unique constraints, ETag.
 */
@DisplayName("Идемпотентность Spring Data REST (Products)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductIdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("POST дубликата SKU - 409 Conflict (unique constraint)")
    void postDuplicateSku_shouldReturn409() throws Exception {
        String productJson = """
                {
                    "sku": "UNIQUE-001",
                    "name": "Первый товар",
                    "price": 1000.00,
                    "quantity": 10
                }
                """;

        // Первый POST - успех
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson))
                .andExpect(status().isCreated());

        // Второй POST с тем же SKU - конфликт
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson))
                .andExpect(status().isConflict());

        Assertions.assertEquals(1, productRepository.count(),
                "Unique constraint на SKU предотвращает создание дубликата");
    }

    @Test
    @Order(2)
    @DisplayName("PUT с корректной версией - успешное обновление")
    void putWithCorrectVersion_shouldSucceed() throws Exception {
        var product = productRepository.save(
                new Product("VER-001", "Товар с версией", new BigDecimal("500.00"), 5));

        // Spring Data REST использует ETag из @Version
        String updateJson = String.format("""
                {
                    "sku": "VER-001",
                    "name": "Обновлённый товар",
                    "price": 600.00,
                    "quantity": 15,
                    "version": %d
                }
                """, product.getVersion());

        mockMvc.perform(put("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", product.getVersion().toString())
                        .content(updateJson))
                .andExpect(status().isNoContent());

        var updated = productRepository.findById(product.getId()).orElseThrow();
        Assertions.assertEquals("Обновлённый товар", updated.getName());
        Assertions.assertEquals(product.getVersion() + 1, updated.getVersion(),
                "Версия должна увеличиться после обновления");
    }

    @Test
    @Order(3)
    @DisplayName("Concurrent PUT - optimistic locking предотвращает lost update")
    void concurrentPut_shouldDetectConflict() throws Exception {
        var product = productRepository.save(
                new Product("CONC-001", "Конкурентный товар", new BigDecimal("1000.00"), 100));
        int originalVersion = product.getVersion();

        // Первый пользователь обновляет (версия 0 -> 1)
        String update1 = """
                {
                    "sku": "CONC-001",
                    "name": "Обновление от пользователя 1",
                    "price": 1100.00,
                    "quantity": 90
                }
                """;
        mockMvc.perform(put("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", String.valueOf(originalVersion))
                        .content(update1))
                .andExpect(status().isNoContent());

        // Второй пользователь пытается обновить со старой версией - конфликт
        String update2 = """
                {
                    "sku": "CONC-001",
                    "name": "Обновление от пользователя 2",
                    "price": 1200.00,
                    "quantity": 80
                }
                """;
        mockMvc.perform(put("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", String.valueOf(originalVersion))
                        .content(update2))
                .andExpect(status().isPreconditionFailed());

        // Данные первого пользователя сохранены
        var saved = productRepository.findById(product.getId()).orElseThrow();
        Assertions.assertEquals("Обновление от пользователя 1", saved.getName(),
                "Optimistic locking предотвращает lost update");
    }

    @Test
    @Order(4)
    @DisplayName("PATCH идемпотентен - повторное применение не меняет состояние")
    void patchIsIdempotent_shouldNotChangeState() throws Exception {
        var product = productRepository.save(
                new Product("PATCH-001", "Товар для PATCH", new BigDecimal("300.00"), 50));

        String patchJson = """
                {
                    "name": "Пропатченный товар"
                }
                """;

        // Первый PATCH
        mockMvc.perform(patch("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson))
                .andExpect(status().isNoContent());

        var afterFirst = productRepository.findById(product.getId()).orElseThrow();
        String nameAfterFirst = afterFirst.getName();
        int versionAfterFirst = afterFirst.getVersion();

        // Второй PATCH с теми же данными
        mockMvc.perform(patch("/api/products/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchJson))
                .andExpect(status().isNoContent());

        var afterSecond = productRepository.findById(product.getId()).orElseThrow();
        Assertions.assertEquals(nameAfterFirst, afterSecond.getName(),
                "Повторный PATCH с теми же данными не должен менять имя");
    }

    @Test
    @Order(5)
    @DisplayName("GET идемпотентен - многократные запросы возвращают одинаковый результат")
    void getIsIdempotent_shouldReturnConsistentResult() throws Exception {
        var product = productRepository.save(
                new Product("GET-001", "Стабильный товар", new BigDecimal("999.99"), 42));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/products/" + product.getId())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Стабильный товар"))
                    .andExpect(jsonPath("$.sku").value("GET-001"));
        }
    }
}
