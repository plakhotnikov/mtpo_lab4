package com.example.idempotency.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.rest.core.annotation.HandleBeforeCreate;
import org.springframework.data.rest.core.annotation.HandleBeforeSave;
import org.springframework.data.rest.core.annotation.RepositoryEventHandler;
import org.springframework.stereotype.Component;

/**
 * Тип контроллера №3 (часть): обработчик событий Spring Data REST.
 *
 * Spring Data REST автоматически создаёт CRUD endpoints из ProductRepository.
 * Для добавления бизнес-логики используются @RepositoryEventHandler,
 * что кардинально отличается от написания контроллера вручную.
 *
 * Идемпотентность обеспечивается:
 * - @Version (optimistic locking) - предотвращает lost updates при concurrent PUT
 * - UNIQUE constraint на sku - предотвращает создание дубликатов
 * - ETag автоматически генерируется Spring Data REST из @Version
 *
 * @see <a href="https://docs.spring.io/spring-data/rest/reference/events.html">
 *     Spring Data REST: Events</a>
 */
@Component
@RepositoryEventHandler
public class ProductEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductEventHandler.class);

    @HandleBeforeCreate
    public void handleBeforeCreate(Product product) {
        log.info("Создание товара: SKU={}, name={}", product.getSku(), product.getName());
        if (product.getQuantity() == null) {
            product.setQuantity(0);
        }
    }

    @HandleBeforeSave
    public void handleBeforeSave(Product product) {
        log.info("Обновление товара: id={}, SKU={}, version={}",
                product.getId(), product.getSku(), product.getVersion());
    }
}
