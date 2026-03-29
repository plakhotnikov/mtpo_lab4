# Лабораторная работа №4 — Тестирование идемпотентности REST API

## Контекст

Дисциплина: "Методы тестирования ПО" (СПбПУ, ИКНТ ВШ ПИ).
Студент: Плахотников Владимир Александрович, группа 5130903/30303.
Руководитель: старший преподаватель В. А. Пархоменко.
Тема: "Исследование и демонстрация тестирования идемпотентности и повторяемости запросов в REST API на Java (Spring Boot)".

## Требования преподавателя

- Использовать **кардинально разные типы контроллеров** (не только PetClinic)
- Взять другие открытые и хрестоматийные для Spring примеры с разной организацией контроллеров
- Отчёт по IMRAD, оформление в SPBPU-BCI-template, библиография через biber
- Обязательно: мат. постановка, псевдокод в `algorithm`, иллюстративный пример, пошаговое выполнение

## Архитектура приложения

### Три типа контроллеров

| Тип | Пакет | Домен | Каноническое вдохновение |
|-----|-------|-------|--------------------------|
| `@RestController` (аннотации) | `order/` | Заказы | Spring Guides "Building REST services with Spring" |
| `RouterFunction` (WebMvc.fn) | `payment/` | Платежи | Spring Framework "Functional Endpoints" |
| `@RepositoryRestResource` (Spring Data REST) | `product/` | Товары | Spring Guides "Accessing JPA Data with REST" |

### Механизмы идемпотентности

- **Idempotency-Key** заголовок — `IdempotencyFilter` (OncePerRequestFilter), ключи хранятся в PostgreSQL
- **Optimistic locking** — `@Version` на Product (Spring Data REST генерирует ETag)
- **Unique constraints** — на `orders(description, amount)` и `products(sku)`

### Профили Spring

- `default` / `test` — идемпотентность **включена**, все тесты проходят
- `non-idempotent` / `test-non-idempotent` — фильтр отключён, constraints удалены (Flyway V3), тесты демонстрируют баги

## Стек

- Java 17, Spring Boot 3.3.6, PostgreSQL 16
- Flyway (миграции), Jackson CSV (импорт CSV)
- Testcontainers (singleton-паттерн), JUnit 5, MockMvc
- Docker Compose для PostgreSQL

## Тесты (22 шт, все зелёные)

| Класс | Кол-во | Что тестирует |
|-------|--------|---------------|
| `OrderIdempotencyTest` | 6 | @RestController: Idempotency-Key, PUT/DELETE/GET идемпотентность |
| `PaymentIdempotencyTest` | 5 | Functional Endpoints: Idempotency-Key, retry safety |
| `ProductIdempotencyTest` | 5 | Spring Data REST: @Version, ETag, unique SKU, PATCH |
| `NonIdempotentProfileTest` | 4 | Демонстрация багов без идемпотентности |
| `DataImportTest` | 2 | Импорт JSON/CSV, сравнение с эталоном |

Запуск: `mvn test` (нужен Docker для Testcontainers).

## Структура проекта

```
src/main/java/com/example/idempotency/
├── config/              # IdempotencyProperties, WebMvcConfig
├── idempotency/         # Filter, Service, Entity, Repository
├── order/               # @RestController — Order, OrderService, OrderController, OrderDto
├── payment/             # Functional — Payment, PaymentService, PaymentHandler, PaymentRouter
├── product/             # Data REST — Product, ProductRepository, ProductEventHandler
└── exception/           # GlobalExceptionHandler

src/main/resources/
├── application.yml / application-non-idempotent.yml
├── db/migration/        # V1 (schema), V2 (idempotency_keys)
├── db/migration-non-idempotent/  # V3 (drop constraints)
└── data/                # orders.json, payments.json, products.csv

src/test/                # BaseIntegrationTest + 5 тестовых классов
report/                  # LaTeX отчёт (IMRAD, SPBPU-BCI-template)
presentation/            # Beamer презентация (11 слайдов)
docker-compose.yml       # PostgreSQL 16
```

## Известные особенности

- Java 17 (не 21) — ограничение JDK на машине
- Payment.order — `FetchType.EAGER` (чтобы Jackson мог сериализовать в функциональных эндпоинтах)
- В тестах `@Order` аннотация из JUnit использует полный путь `@org.junit.jupiter.api.Order` чтобы не конфликтовать с entity `Order`
- Testcontainers используют singleton-паттерн (static block `POSTGRES.start()`) для переиспользования контейнера
- BigDecimal сравнение через `compareTo` (не `equals`) из-за разницы scale
- В `setUp()` тестов удаление идёт в порядке: payments → idempotency_keys → orders (FK constraints)

## Отчёт и презентация

- **LaTeX не установлен** на машине — компилировать через Overleaf или TeXLive
- Отчёт: `report/main.tex`, библиография `references.bib` (14 источников)
- Презентация: `presentation/presentation.tex`, Beamer, 11 слайдов
- ФИО и группа уже заполнены в обоих файлах
