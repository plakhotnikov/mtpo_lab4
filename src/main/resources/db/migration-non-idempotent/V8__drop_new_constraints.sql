-- Удаляем UNIQUE-ограничения трёх новых доменов: без них и без активного
-- IdempotencyFilter/Interceptor транспорты SOAP/gRPC/GraphQL создают дубликаты
-- на повторных запросах - что и демонстрируется в NonIdempotentProfileTest.
ALTER TABLE shipments     DROP CONSTRAINT IF EXISTS uk_shipment_tracking_number;
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS uk_notification_recipient_hash;
ALTER TABLE inventories   DROP CONSTRAINT IF EXISTS uk_inventory_item_name;
