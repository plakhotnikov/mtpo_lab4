package com.example.idempotency.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Сервис управления ключами идемпотентности.
 * Хранит ключи и кэшированные ответы в PostgreSQL.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Проверяет, существует ли ключ идемпотентности.
     * Если да — возвращает кэшированный ответ.
     */
    public Optional<IdempotencyKeyEntity> findByKey(String key) {
        return repository.findByIdempotencyKey(key);
    }

    /**
     * Регистрирует новый ключ идемпотентности (до обработки запроса).
     * Используется для «захвата» ключа при concurrent запросах.
     */
    @Transactional
    public IdempotencyKeyEntity registerKey(String key, String method, String path) {
        log.info("Регистрация ключа идемпотентности: {} {} {}", key, method, path);
        var entity = new IdempotencyKeyEntity(key, method, path);
        return repository.save(entity);
    }

    /**
     * Сохраняет результат обработки запроса для повторного использования.
     */
    @Transactional
    public void saveResponse(String key, int status, String responseBody) {
        repository.findByIdempotencyKey(key).ifPresent(entity -> {
            entity.setResponseStatus(status);
            entity.setResponseBody(responseBody);
            repository.save(entity);
            log.info("Сохранён ответ для ключа {}: status={}", key, status);
        });
    }
}
