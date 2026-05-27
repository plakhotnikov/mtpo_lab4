-- Таблица уведомлений (домен gRPC-транспорта)
CREATE TABLE notifications (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient     VARCHAR(255) NOT NULL,
    message       TEXT NOT NULL,
    message_hash  VARCHAR(64) NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'SENT',
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_notification_recipient_hash UNIQUE (recipient, message_hash)
);

CREATE INDEX idx_notification_recipient ON notifications(recipient);
