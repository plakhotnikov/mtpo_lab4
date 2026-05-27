package com.example.idempotency.soap;

import com.example.idempotency.BaseIntegrationTest;
import com.example.idempotency.idempotency.IdempotencyKeyRepository;
import com.example.idempotency.soap.shipment.ShipmentRepository;
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

@DisplayName("Идемпотентность SOAP (Spring-WS)")
class SoapIdempotencyTest extends BaseIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("POST с одним Idempotency-Key дважды - создаётся одна запись Shipment")
    void sameKey_returnsCachedEnvelope() {
        String tracking = "TR-" + UUID.randomUUID();
        String envelope = createEnvelope(tracking, "Иванов И.И.");
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = sendSoap(envelope, key);
        ResponseEntity<String> second = sendSoap(envelope, key);

        Assertions.assertEquals(HttpStatus.OK, first.getStatusCode());
        Assertions.assertEquals(HttpStatus.OK, second.getStatusCode());
        Assertions.assertEquals(first.getBody(), second.getBody(),
                "Реплей должен вернуть байт-в-байт тот же SOAP-envelope");
        Assertions.assertEquals(1, shipmentRepository.count(),
                "Повторный SOAP-вызов с тем же Idempotency-Key не должен создавать дубликат");
        Assertions.assertTrue(first.getHeaders().getContentType() != null
                        && first.getHeaders().getContentType().toString().contains("xml"),
                "Content-Type SOAP-ответа должен быть xml");
    }

    @Test
    @DisplayName("POST с разными Idempotency-Key - создаются две записи")
    void differentKeys_createTwoShipments() {
        String env1 = createEnvelope("TR-" + UUID.randomUUID(), "A");
        String env2 = createEnvelope("TR-" + UUID.randomUUID(), "B");

        sendSoap(env1, UUID.randomUUID().toString());
        sendSoap(env2, UUID.randomUUID().toString());

        Assertions.assertEquals(2, shipmentRepository.count());
    }

    @Test
    @DisplayName("POST без Idempotency-Key - 400 Bad Request, ничего не создано")
    void missingKey_returns400() {
        String envelope = createEnvelope("TR-NO-KEY", "X");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/soap/",
                HttpMethod.POST,
                new HttpEntity<>(envelope, headers),
                String.class);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(0, shipmentRepository.count());
    }

    private ResponseEntity<String> sendSoap(String envelope, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("SOAPAction", "");
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/soap/",
                HttpMethod.POST,
                new HttpEntity<>(envelope, headers),
                String.class);
    }

    private static String createEnvelope(String trackingNumber, String recipient) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <ns:createShipmentRequest xmlns:ns="http://example.com/idempotency/shipments">
                      <ns:trackingNumber>%s</ns:trackingNumber>
                      <ns:recipient>%s</ns:recipient>
                    </ns:createShipmentRequest>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(trackingNumber, recipient);
    }
}
