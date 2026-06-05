package com.example.idempotency.idempotency;

import com.example.idempotency.config.IdempotencyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP-фильтр проверки идемпотентности.
 *
 * Транспорт-агностичен: применяется к REST (JSON), SOAP (text/xml) и GraphQL
 * (application/json), сохраняя оригинальный Content-Type ответа и тело в виде
 * байтов. На реплее восстанавливает исходный формат - клиенту не видна
 * разница между «первым» и «повторным» ответом.
 *
 * Алгоритм:
 *   1. Извлечь заголовок Idempotency-Key
 *   2. Если ключ отсутствует и метод POST -> 400
 *   3. Найти ключ в БД:
 *      a. найден + есть ответ -> реплей кэшированного снэпшота
 *      b. найден без ответа -> 409 (concurrent)
 *      c. не найден -> зарегистрировать и пропустить дальше
 *   4. После обработки сохранить (status, contentType, body) для будущих повторов
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

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
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            writeError(response, HttpStatus.BAD_REQUEST,
                    "{\"error\":\"Idempotency-Key header is required for POST requests\"}");
            return;
        }

        var existing = idempotencyService.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            var cached = existing.get();
            if (cached.getResponseStatus() != null) {
                log.info("Повторный запрос с ключом {}: возвращаем кэшированный ответ", idempotencyKey);
                replay(response, cached);
                return;
            }
            writeError(response, HttpStatus.CONFLICT,
                    "{\"error\":\"Request with this key is already being processed\"}");
            return;
        }

        try {
            idempotencyService.registerKey(idempotencyKey, request.getMethod(), request.getRequestURI());
        } catch (DataIntegrityViolationException e) {
            writeError(response, HttpStatus.CONFLICT,
                    "{\"error\":\"Request with this key is already being processed\"}");
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, responseWrapper);

        byte[] bodyBytes = responseWrapper.getContentAsByteArray();
        String contentType = resolveContentType(responseWrapper);
        // Все HTTP-транспорты пишут текст (JSON/XML), бинарную ветку используем
        // только для gRPC, который этот фильтр не обслуживает.
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        idempotencyService.saveTextResponse(idempotencyKey, responseWrapper.getStatus(), contentType, body);

        responseWrapper.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, IdempotencyKeyEntity cached) throws IOException {
        response.setStatus(cached.getResponseStatus());
        String contentType = cached.getResponseContentType() != null
                ? cached.getResponseContentType()
                : JSON_UTF8;
        response.setContentType(contentType);
        byte[] bytes;
        if (cached.getResponseKind() == ResponseKind.BINARY && cached.getResponseBodyBytes() != null) {
            bytes = cached.getResponseBodyBytes();
        } else if (cached.getResponseBody() != null) {
            bytes = cached.getResponseBody().getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = new byte[0];
        }
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String json) throws IOException {
        response.setStatus(status.value());
        response.setContentType(JSON_UTF8);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    private static String resolveContentType(ContentCachingResponseWrapper wrapper) {
        String header = wrapper.getHeader(HttpHeaders.CONTENT_TYPE);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String ct = wrapper.getContentType();
        return (ct != null && !ct.isBlank()) ? ct : JSON_UTF8;
    }
}
