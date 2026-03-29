-- Таблица хранения ключей идемпотентности
CREATE TABLE idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_key ON idempotency_keys(idempotency_key);
CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
