package com.example.idempotency.profile;

import com.example.idempotency.graphql.inventory.InventoryRepository;
import com.example.idempotency.grpc.notification.NotificationRepository;
import com.example.idempotency.grpc.notification.proto.NotificationServiceGrpc;
import com.example.idempotency.grpc.notification.proto.SendRequest;
import com.example.idempotency.order.OrderDto;
import com.example.idempotency.order.OrderRepository;
import com.example.idempotency.payment.PaymentDto;
import com.example.idempotency.payment.PaymentRepository;
import com.example.idempotency.order.Order;
import com.example.idempotency.product.ProductRepository;
import com.example.idempotency.soap.shipment.ShipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Тесты с профилем non-idempotent.
 * Демонстрируют НЕКОРРЕКТНОЕ поведение системы без защиты идемпотентности:
 *   - Дублирование при повторных POST (REST)
 *   - Отсутствие проверки Idempotency-Key
 *   - Создание дубликатов SKU
 *   - Дубликаты SOAP-отправлений с одинаковым tracking_number
 *   - Повторные gRPC Send создают дубликаты Notification
 *   - Повторные GraphQL-мутации создают дубликаты Inventory
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

    private static final String GRPC_IN_PROCESS_NAME = "idempotency-test-non-idempotent";

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

    @LocalServerPort
    int port;

    @Autowired private MockMvc mockMvc;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        shipmentRepository.deleteAll();
        notificationRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("BUG REST: POST без Idempotency-Key проходит (фильтр отключён)")
    void postWithoutKey_shouldSucceed_whenFilterDisabled() throws Exception {
        var dto = new OrderDto("Заказ без ключа", new BigDecimal("1000.00"), null);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("BUG REST: Повторный POST с тем же Idempotency-Key создаёт дубликат")
    void postWithSameKey_shouldCreateDuplicate_whenFilterDisabled() throws Exception {
        var dto = new OrderDto("Дублирующийся заказ", new BigDecimal("5000.00"), null);
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isCreated());

        var dto2 = new OrderDto("Дублирующийся заказ (копия)", new BigDecimal("5000.01"), null);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(objectMapper.writeValueAsString(dto2)))
                .andExpect(status().isCreated());

        Assertions.assertTrue(orderRepository.count() >= 2,
                "Без идемпотентности повторный POST создаёт дубликат - это баг!");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("BUG Data REST: Дубликат SKU создаётся (unique constraint удалён)")
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
                "Без unique constraint создаются дубликаты SKU - это баг!");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("BUG Functional: Retry платежа создаёт множественные списания")
    void retryPayment_shouldCreateMultiple_whenFilterDisabled() throws Exception {
        var order = orderRepository.save(new Order("Заказ для retry", new BigDecimal("10000.00")));

        var dto = new PaymentDto(order.getId(), new BigDecimal("10000.00"));
        String body = objectMapper.writeValueAsString(dto);
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", key)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        Assertions.assertEquals(3, paymentRepository.count(),
                "Без идемпотентности 3 retry создают 3 платежа - тройное списание!");
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("BUG SOAP: повторный createShipment с тем же tracking_number создаёт дубликат")
    void soapDuplicate_whenConstraintAndFilterDisabled() {
        String tracking = "TR-DUP-" + UUID.randomUUID();
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <ns:createShipmentRequest xmlns:ns="http://example.com/idempotency/shipments">
                      <ns:trackingNumber>%s</ns:trackingNumber>
                      <ns:recipient>X</ns:recipient>
                    </ns:createShipmentRequest>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(tracking);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        HttpEntity<String> entity = new HttpEntity<>(envelope, headers);
        String url = "http://localhost:" + port + "/api/soap/";

        ResponseEntity<String> r1 = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        ResponseEntity<String> r2 = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        Assertions.assertTrue(r1.getStatusCode().is2xxSuccessful());
        Assertions.assertTrue(r2.getStatusCode().is2xxSuccessful());
        Assertions.assertEquals(2, shipmentRepository.count(),
                "Без UNIQUE на tracking_number и без фильтра - SOAP создаёт два Shipment с одним номером!");
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("BUG gRPC: повторный Send с тем же ключом создаёт дубликат (interceptor отключён)")
    void grpcDuplicate_whenInterceptorDisabled() throws InterruptedException {
        ManagedChannel channel = InProcessChannelBuilder.forName(GRPC_IN_PROCESS_NAME)
                .usePlaintext().build();
        try {
            var stub = NotificationServiceGrpc.newBlockingStub(channel);
            SendRequest req = SendRequest.newBuilder()
                    .setRecipient("retry-user").setMessage("duplicate message").build();
            stub.send(req);
            stub.send(req);
            Assertions.assertEquals(2, notificationRepository.count(),
                    "Без UNIQUE на (recipient,message_hash) и без interceptor - два дубликата!");
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("BUG GraphQL: повторная мутация с тем же ключом создаёт дубликат")
    void graphqlDuplicate_whenFilterDisabled() {
        String itemName = "duplicate-item-" + UUID.randomUUID();
        String body = """
                {"query":"mutation { reserveInventory(input:{itemName:\\"%s\\",quantity:1}) { id itemName } }"}"""
                .formatted(itemName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        String url = "http://localhost:" + port + "/graphql";

        ResponseEntity<String> r1 = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        ResponseEntity<String> r2 = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        Assertions.assertTrue(r1.getStatusCode().is2xxSuccessful());
        Assertions.assertTrue(r2.getStatusCode().is2xxSuccessful());
        Assertions.assertEquals(2, inventoryRepository.count(),
                "Без UNIQUE на item_name и без фильтра - GraphQL создаёт две записи Inventory!");
    }
}
