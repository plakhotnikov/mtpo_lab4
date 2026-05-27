-- Таблица отправлений (домен SOAP-транспорта)
CREATE TABLE shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_number VARCHAR(64) NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_shipment_tracking_number UNIQUE (tracking_number)
);

CREATE INDEX idx_shipment_tracking ON shipments(tracking_number);
