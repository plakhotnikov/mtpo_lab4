package com.example.idempotency.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;

/**
 * Spring Data REST автоматически создаёт CRUD endpoints для товаров.
 * Это кардинально отличается от @RestController и Functional Endpoints:
 * контроллер не пишется вручную - всё генерируется из репозитория.
 *
 * @see <a href="https://spring.io/guides/gs/accessing-data-rest">Accessing JPA Data with REST</a>
 */
@RepositoryRestResource(collectionResourceRel = "products", path = "products")
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);
}
