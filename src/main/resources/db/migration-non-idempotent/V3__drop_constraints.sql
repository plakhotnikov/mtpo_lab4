-- Удаляем ограничения для демонстрации поведения без идемпотентности
ALTER TABLE orders DROP CONSTRAINT IF EXISTS uk_order_description_amount;
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_sku_key;
