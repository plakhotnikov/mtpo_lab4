package com.example.idempotency.idempotency;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Хранение ключей идемпотентности в БД.
 * При повторном запросе с тем же ключом возвращается кэшированный ответ.
 *
 * Поля response_content_type / response_kind / response_body_bytes расширяют
 * исходный «JSON-only» дизайн до транспорт-агностичного снэпшота ответа,
 * пригодного для SOAP (text/xml) и gRPC (бинарный protobuf).
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "response_content_type", length = 150)
    private String responseContentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_kind", nullable = false, length = 16)
    private ResponseKind responseKind = ResponseKind.TEXT;

    // BYTEA в PostgreSQL - без @Lob (иначе Hibernate ожидает OID/Types#BLOB)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "response_body_bytes", columnDefinition = "BYTEA")
    private byte[] responseBodyBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

    public IdempotencyKeyEntity() {}

    public IdempotencyKeyEntity(String idempotencyKey, String method, String path) {
        this.idempotencyKey = idempotencyKey;
        this.method = method;
        this.path = path;
    }

    public Long getId() { return id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public String getResponseContentType() { return responseContentType; }
    public void setResponseContentType(String responseContentType) { this.responseContentType = responseContentType; }

    public ResponseKind getResponseKind() { return responseKind; }
    public void setResponseKind(ResponseKind responseKind) { this.responseKind = responseKind; }

    public byte[] getResponseBodyBytes() { return responseBodyBytes; }
    public void setResponseBodyBytes(byte[] responseBodyBytes) { this.responseBodyBytes = responseBodyBytes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
