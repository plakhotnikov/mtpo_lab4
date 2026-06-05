-- Расширение idempotency_keys для транспорт-агностичности.
-- Исходный фильтр кодировал реплей как application/json - это ломает SOAP (text/xml)
-- и gRPC (binary protobuf). Сохраняем оригинальный Content-Type и тело
-- либо как текст, либо как байты (для бинарных протоколов).
ALTER TABLE idempotency_keys
    ADD COLUMN response_content_type VARCHAR(150),
    ADD COLUMN response_kind         VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN response_body_bytes   BYTEA;
