package com.example.idempotency.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Сервис управления ключами идемпотентности.
 * Хранит ключи и кэшированные ответы в PostgreSQL.
 *
 * Единая точка интеграции для всех транспортов: HTTP-фильтр (REST, SOAP,
 * GraphQL) и gRPC ServerInterceptor одинаково обращаются к этому бину.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public Optional<IdempotencyKeyEntity> findByKey(String key) {
        return repository.findByIdempotencyKey(key);
    }

    @Transactional
    public IdempotencyKeyEntity registerKey(String key, String method, String path) {
        log.info("Регистрация ключа идемпотентности: {} {} {}", key, method, path);
        var entity = new IdempotencyKeyEntity(key, method, path);
        return repository.save(entity);
    }

    /** Сохранение текстового ответа (REST/SOAP/GraphQL). */
    @Transactional
    public void saveTextResponse(String key, int status, String contentType, String responseBody) {
        repository.findByIdempotencyKey(key).ifPresent(entity -> {
            entity.setResponseStatus(status);
            entity.setResponseContentType(contentType);
            entity.setResponseKind(ResponseKind.TEXT);
            entity.setResponseBody(responseBody);
            entity.setResponseBodyBytes(null);
            repository.save(entity);
            log.info("Сохранён текстовый ответ для ключа {}: status={}, contentType={}", key, status, contentType);
        });
    }

    /** Сохранение бинарного ответа (gRPC protobuf). */
    @Transactional
    public void saveBinaryResponse(String key, int status, String contentType, byte[] bytes) {
        repository.findByIdempotencyKey(key).ifPresent(entity -> {
            entity.setResponseStatus(status);
            entity.setResponseContentType(contentType);
            entity.setResponseKind(ResponseKind.BINARY);
            entity.setResponseBody(null);
            entity.setResponseBodyBytes(bytes);
            repository.save(entity);
            log.info("Сохранён бинарный ответ для ключа {}: status={}, {} байт", key, status, bytes != null ? bytes.length : 0);
        });
    }
}
