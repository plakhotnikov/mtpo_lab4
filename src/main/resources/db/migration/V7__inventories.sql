-- Таблица инвентаря (домен GraphQL-транспорта)
CREATE TABLE inventories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_name   VARCHAR(255) NOT NULL,
    quantity    INTEGER NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_inventory_item_name UNIQUE (item_name)
);

CREATE INDEX idx_inventory_item_name ON inventories(item_name);
