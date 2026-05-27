package com.example.idempotency.graphql.inventory;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL-контроллер (spring-boot-starter-graphql).
 * Идемпотентность мутаций обеспечивается тем же IdempotencyFilter - путь
 * /api/graphql добавлен в URL-паттерны бина. Мутации (HTTP POST) требуют
 * заголовок Idempotency-Key; запросы могут идти через GET и в фильтр не
 * попадают (фильтр пропускает не-POST).
 */
@Controller
public class InventoryGraphqlController {

    private final InventoryRepository repository;

    public InventoryGraphqlController(InventoryRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public List<Inventory> inventories() {
        return repository.findAll();
    }

    @QueryMapping
    public Inventory inventory(@Argument String id) {
        return repository.findById(UUID.fromString(id)).orElse(null);
    }

    /**
     * Идемпотентность гарантируется ВНЕШНЕ: IdempotencyFilter на /graphql +
     * UNIQUE(item_name). В non-idempotent профиле оба слоя выключены -
     * демонстрируется тестом graphqlDuplicate_whenFilterDisabled.
     */
    @MutationMapping
    @Transactional
    public Inventory reserveInventory(@Argument ReserveInput input) {
        return repository.save(new Inventory(input.itemName(), input.quantity()));
    }

    public record ReserveInput(String itemName, int quantity) {}
}
