package com.example.idempotency.idempotency;

import com.example.idempotency.config.IdempotencyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-фильтр проверки идемпотентности.
 *
 * Алгоритм:
 * 1. Извлечь заголовок Idempotency-Key из запроса
 * 2. Если ключ отсутствует и метод POST — вернуть 400
 * 3. Найти ключ в БД:
 *    a. Если найден и есть кэшированный ответ — вернуть его
 *    b. Если не найден — зарегистрировать и продолжить обработку
 * 4. После обработки — сохранить ответ для будущих повторов
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyService idempotencyService;
    private final IdempotencyProperties properties;

    public IdempotencyFilter(IdempotencyService idempotencyService, IdempotencyProperties properties) {
        this.idempotencyService = idempotencyService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Фильтр применяется только к POST-запросам
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Если идемпотентность отключена (профиль non-idempotent) — пропускаем
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        // POST без ключа — ошибка
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Idempotency-Key header is required for POST requests\"}");
            return;
        }

        // Проверяем, есть ли ключ в БД
        var existing = idempotencyService.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            var cached = existing.get();
            if (cached.getResponseStatus() != null) {
                // Возвращаем кэшированный ответ (повторный запрос — идемпотентен)
                log.info("Повторный запрос с ключом {}: возвращаем кэшированный ответ", idempotencyKey);
                response.setStatus(cached.getResponseStatus());
                response.setContentType("application/json;charset=UTF-8");
                if (cached.getResponseBody() != null) {
                    response.getWriter().write(cached.getResponseBody());
                }
                return;
            }
            // Ключ зарегистрирован, но ответ ещё не готов (concurrent запрос)
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Request with this key is already being processed\"}");
            return;
        }

        // Регистрируем ключ
        try {
            idempotencyService.registerKey(idempotencyKey, request.getMethod(), request.getRequestURI());
        } catch (DataIntegrityViolationException e) {
            // Concurrent запрос уже зарегистрировал этот ключ
            response.setStatus(HttpStatus.CONFLICT.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Request with this key is already being processed\"}");
            return;
        }

        // Оборачиваем response для перехвата тела ответа
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, responseWrapper);

        // Сохраняем результат
        String responseBody = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        idempotencyService.saveResponse(idempotencyKey, responseWrapper.getStatus(), responseBody);

        responseWrapper.copyBodyToResponse();
    }
}
