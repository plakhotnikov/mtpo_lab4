package com.example.idempotency.graphql;

import com.example.idempotency.BaseIntegrationTest;
import com.example.idempotency.graphql.inventory.InventoryRepository;
import com.example.idempotency.idempotency.IdempotencyKeyRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@DisplayName("Идемпотентность GraphQL (spring-boot-starter-graphql)")
class GraphqlIdempotencyTest extends BaseIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    @DisplayName("Mutation с одним Idempotency-Key дважды - одна запись Inventory")
    void sameKeyOnMutation_returnsCached() {
        String key = UUID.randomUUID().toString();
        String body = mutation("widget-" + UUID.randomUUID(), 10);

        ResponseEntity<String> first = sendGraphql(body, key);
        ResponseEntity<String> second = sendGraphql(body, key);

        Assertions.assertEquals(HttpStatus.OK, first.getStatusCode());
        Assertions.assertEquals(HttpStatus.OK, second.getStatusCode());
        Assertions.assertEquals(first.getBody(), second.getBody(),
                "GraphQL-ответ при реплее должен быть идентичен");
        Assertions.assertEquals(1, inventoryRepository.count());
    }

    @Test
    @DisplayName("Mutation с разными Idempotency-Key - две записи")
    void differentKeys_createTwoEntries() {
        sendGraphql(mutation("a-" + UUID.randomUUID(), 1), UUID.randomUUID().toString());
        sendGraphql(mutation("b-" + UUID.randomUUID(), 2), UUID.randomUUID().toString());

        Assertions.assertEquals(2, inventoryRepository.count());
    }

    @Test
    @DisplayName("Mutation без Idempotency-Key - 400 Bad Request")
    void missingKeyOnMutation_returns400() {
        String body = mutation("no-key-" + UUID.randomUUID(), 5);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/graphql",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(0, inventoryRepository.count());
    }

    @Test
    @DisplayName("Query без Idempotency-Key проходит - фильтр пропускает POST-запросы только с ключом, но GET не требует ключа; здесь POST-query всё равно требует ключ - поэтому проверяем что после создания через mutation запрос query возвращает данные")
    void queryAfterMutation_works() {
        String key = UUID.randomUUID().toString();
        String itemName = "queryable-" + UUID.randomUUID();
        sendGraphql(mutation(itemName, 7), key);

        String queryBody = """
                {"query":"{ inventories { itemName quantity } }"}""";
        ResponseEntity<String> response = sendGraphql(queryBody, UUID.randomUUID().toString());

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertTrue(response.getBody() != null && response.getBody().contains(itemName),
                "Query должен вернуть только что созданную запись");
    }

    private ResponseEntity<String> sendGraphql(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange(
                "http://localhost:" + port + "/graphql",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static String mutation(String itemName, int quantity) {
        return """
                {"query":"mutation { reserveInventory(input:{itemName:\\"%s\\",quantity:%d}) { id itemName quantity } }"}"""
                .formatted(itemName, quantity);
    }
}
