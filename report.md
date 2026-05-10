# Практическая работа № 4

## Исследование и демонстрация тестирования идемпотентности и повторяемости запросов в REST API на Java (Spring Boot)

**Дисциплина:** Методы тестирования программного обеспечения

**Выполнил:** студент гр. 5130903/30303 — В. А. Плахотников

**Руководитель:** старший преподаватель ВШ ПИ — В. А. Пархоменко

Санкт-Петербургский политехнический университет Петра Великого  
Институт компьютерных наук и кибербезопасности  
Высшая школа программной инженерии

Санкт-Петербург — 2026

---

## Содержание

1. [Введение](#1-введение)
2. [Методы](#2-методы)
3. [Результаты](#3-результаты)
4. [Обсуждение](#4-обсуждение)
5. [Заключение](#5-заключение)
6. [Инструкция по установке и запуску](#6-инструкция-по-установке-и-запуску)
7. [Список литературы](#7-список-литературы)
8. [Приложение А. Листинги исходного кода](#приложение-а-листинги-исходного-кода)

---

## 1. Введение

### 1.1. Актуальность

В современных распределённых системах REST API является основным способом взаимодействия между сервисами. При обработке HTTP-запросов неизбежно возникают ситуации, когда запрос может быть отправлен повторно: из-за сетевых таймаутов, retry-политик, дублирования на уровне балансировщиков нагрузки или ошибок клиента. Без механизмов идемпотентности повторная обработка запроса приводит к критическим последствиям: дублированию платежей, двойному списанию средств, созданию дубликатов записей в базе данных [1].

Тестирование идемпотентности особенно важно в финансовых и e-commerce системах, где повторная обработка транзакции приводит к прямым финансовым потерям. Компании, такие как Stripe, реализуют паттерн Idempotency-Key как обязательный элемент API [14].

### 1.2. Постановка проблемы

Проблема заключается в необходимости обеспечения корректного поведения REST API при повторных запросах. HTTP-спецификация [2] определяет, какие методы должны быть идемпотентными (GET, PUT, DELETE), а какие — нет (POST). Однако на практике реализация идемпотентности требует дополнительных механизмов на уровне приложения.

Цель данной работы — исследовать и продемонстрировать методы тестирования идемпотентности REST API на примере Spring Boot приложения с тремя кардинально различными типами организации контроллеров.

### 1.3. Задачи

1. Формализовать понятие идемпотентности HTTP-запросов математически.
2. Разработать REST API с тремя различными типами контроллеров Spring:
   - аннотационный `@RestController` (по образцу Spring Guides [5]),
   - функциональные эндпоинты `RouterFunction` (по образцу Spring Framework [6]),
   - автогенерируемые эндпоинты Spring Data REST [7].
3. Реализовать механизмы обеспечения идемпотентности (Idempotency-Key, optimistic locking, unique constraints).
4. Разработать набор интеграционных тестов с использованием Testcontainers [11].
5. Продемонстрировать поведение системы без механизмов идемпотентности через отдельный Spring-профиль.

---

## 2. Методы

### 2.1. Математическая постановка задачи идемпотентности

**Определение.** Функция *f: X → X* называется *идемпотентной*, если для любого *x ∈ X*:

```
f(f(x)) = f(x)                                         (1)
```

В контексте HTTP API определим:

- *S* — множество состояний системы (совокупность данных в БД);
- *R* — множество HTTP-запросов;
- *O: S × R → S × Response* — операция обработки запроса;
- *π₁(O(s, r))* — проекция на новое состояние.

**Определение (идемпотентность HTTP-метода).** HTTP-метод *m* называется идемпотентным, если для любого состояния *s ∈ S* и запроса *r* с методом *m*:

```
π₁(Oⁿ(s, r)) = π₁(O(s, r)),  ∀n ≥ 1                   (2)
```

где *Oⁿ* обозначает *n*-кратное применение операции, при этом каждое следующее применение использует состояние, полученное на предыдущем шаге:

```
O¹(s, r) = O(s, r),   Oⁿ⁺¹(s, r) = O(π₁(Oⁿ(s, r)), r) (3)
```

**Свойства.** Согласно RFC 9110 [2]:

- **Идемпотентные методы:** GET, HEAD, PUT, DELETE, OPTIONS
- **Неидемпотентные методы:** POST, PATCH
- **Safe (безопасные) методы:** GET, HEAD, OPTIONS — не изменяют состояние

Формально, для safe-метода *m*:

```
π₁(O(s, r)) = s,  ∀s ∈ S, r с методом m                 (4)
```

Из этого следует, что каждый safe-метод является идемпотентным, но не наоборот: DELETE изменяет состояние при первом вызове (удаляет ресурс), однако повторные вызовы не изменяют его далее — ресурс уже отсутствует.

**Задача.** Для метода POST, который не является идемпотентным по определению, необходимо обеспечить идемпотентность через дополнительные механизмы. Обозначим *k* — ключ идемпотентности. Тогда модифицированная операция *O'*:

```
O'(s, r, k) =
  O(s, r),                      если k ∉ KeyStore(s)
  (s, CachedResponse(k)),       если k ∈ KeyStore(s)     (5)
```

что гарантирует: *π₁(O'ⁿ(s, r, k)) = π₁(O'(s, r, k)), ∀n ≥ 1*.

**Доказательство.** Пусть *s₁ = π₁(O'(s, r, k))*. При первом вызове *k ∉ KeyStore(s)*, поэтому выполняется *O(s, r)* и ключ *k* регистрируется в хранилище. При втором вызове *k ∈ KeyStore(s₁)*, поэтому состояние не изменяется: *π₁(O'(s₁, r, k)) = s₁*. По индукции, для любого *n ≥ 1*: *π₁(O'ⁿ(s, r, k)) = s₁ = π₁(O'(s, r, k))*. ∎

### 2.2. Классификация HTTP-методов по идемпотентности

Спецификация HTTP (RFC 9110 [2]) определяет свойства методов следующим образом:

| Метод  | Safe | Идемп. | Тело | Семантика                          |
|--------|------|--------|------|------------------------------------|
| GET    | Да   | Да     | Нет  | Получение представления ресурса    |
| HEAD   | Да   | Да     | Нет  | Получение заголовков без тела      |
| PUT    | Нет  | Да     | Да   | Полная замена состояния ресурса    |
| DELETE | Нет  | Да     | Нет  | Удаление ресурса                   |
| POST   | Нет  | Нет    | Да   | Создание или произвольная обработка|
| PATCH  | Нет  | Нет    | Да   | Частичное обновление ресурса       |

Различие между PUT и PATCH имеет принципиальное значение для идемпотентности. PUT заменяет ресурс целиком, поэтому повторный вызов с теми же данными не меняет состояние. PATCH применяет дельту: например, операция «увеличить количество на 1» при повторном применении даст другой результат, поэтому PATCH не является идемпотентным в общем случае.

### 2.3. Паттерн Idempotency-Key

Согласно IETF-стандарту [3], клиент передаёт уникальный ключ в заголовке `Idempotency-Key`. Сервер сохраняет этот ключ вместе с кэшированным ответом. При повторном запросе с тем же ключом сервер возвращает кэшированный ответ без повторного выполнения операции.

Данный паттерн широко применяется в production-системах. В частности, платёжная система Stripe требует заголовок `Idempotency-Key` для всех POST-запросов, связанных с финансовыми операциями [14]. Это предотвращает дублирование платежей при retry.

Жизненный цикл ключа идемпотентности:

1. **Генерация** — клиент генерирует UUID перед первым запросом.
2. **Регистрация** — сервер сохраняет ключ в хранилище (БД) до обработки запроса.
3. **Обработка** — выполняется бизнес-логика, результат кэшируется.
4. **Повторное использование** — при повторном запросе возвращается кэш.
5. **Истечение** — по прошествии TTL (обычно 24 часа) ключ удаляется.

### 2.4. Optimistic Locking как механизм идемпотентности

Для метода PUT идемпотентность может быть усилена через *optimistic locking* [8]. Каждая сущность содержит поле версии *v*. При обновлении клиент передаёт текущую версию, и сервер проверяет совпадение:

```
Update(s, r, v) =
  (s', 200 OK),          если v = CurrentVersion(s)
  (s, 409 Conflict),     если v ≠ CurrentVersion(s)     (6)
```

В Spring Data REST это реализуется через аннотацию `@Version`: Hibernate автоматически добавляет проверку версии в SQL-запрос UPDATE, а Spring генерирует HTTP-заголовок ETag из значения версии. Клиент передаёт ETag через заголовок `If-Match`, и при несовпадении версий сервер возвращает `412 Precondition Failed`.

Это предотвращает проблему *lost update* — ситуацию, когда два клиента одновременно читают одну версию ресурса, модифицируют его и сохраняют, при этом изменения первого клиента теряются.

### 2.5. Псевдокод алгоритма проверки идемпотентности

**Алгоритм 1.** Проверка идемпотентности HTTP-запроса

```
Вход: HTTP-запрос request, хранилище ключей KeyStore
Выход: HTTP-ответ response

Функция CheckIdempotency(request):
  1.  если request.method ≠ POST тогда
  2.      вернуть ProcessRequest(request)       // GET/PUT/DELETE идемпотентны
  3.  конец если
  4.  key ← request.headers["Idempotency-Key"]
  5.  если key = ∅ тогда
  6.      вернуть 400 Bad Request               // Ключ обязателен для POST
  7.  конец если
  8.  cached ← KeyStore.find(key)
  9.  если cached ≠ ∅ и cached.response ≠ ∅ тогда
  10.     вернуть cached.response               // Повторный запрос — кэшированный ответ
  11. конец если
  12. если cached ≠ ∅ и cached.response = ∅ тогда
  13.     вернуть 409 Conflict                   // Запрос уже обрабатывается
  14. конец если
  15. KeyStore.register(key, request.method, request.path)
  16. response ← ProcessRequest(request)
  17. KeyStore.saveResponse(key, response)
  18. вернуть response
```

**Сложность алгоритма.** Поиск ключа в хранилище выполняется за O(1) при использовании индекса (B-tree) на поле `idempotency_key`. Регистрация и сохранение ответа — по одной операции INSERT/UPDATE. Общая сложность: O(1) дополнительных операций к каждому POST-запросу.

**Обработка конкурентных запросов.** Строка 15 алгоритма предотвращает гонку (race condition): если два запроса с одним ключом поступают одновременно, только первый успешно зарегистрирует ключ (благодаря UNIQUE constraint в БД), второй получит ошибку `DataIntegrityViolationException` и вернёт 409 Conflict.

### 2.6. Пошаговое выполнение алгоритма на иллюстративных данных

**Сценарий:** клиент создаёт заказ (POST /api/orders) и из-за таймаута повторяет запрос.

**Входные данные:**

- Запрос: `POST /api/orders`, тело: `{description: "Ноутбук", amount: 89999.99}`
- Заголовок: `Idempotency-Key: abc-123`
- Начальное состояние: таблица `orders` пуста, `KeyStore` пуст

**Первый запрос (шаг за шагом):**

1. Метод = POST ⇒ проверяем Idempotency-Key (строка 2 алгоритма)
2. key = "abc-123" ≠ ∅ ⇒ продолжаем (строка 6)
3. KeyStore.find("abc-123") = ∅ ⇒ ключ новый (строка 9)
4. KeyStore.register("abc-123", POST, "/api/orders") (строка 17)
5. Выполняем ProcessRequest: INSERT INTO orders ⇒ заказ создан (id=e7f1...) (строка 18)
6. KeyStore.saveResponse("abc-123", 201, "{id: e7f1...}") (строка 19)
7. Ответ: `201 Created`, тело: `{id: "e7f1...", description: "Ноутбук"}`

**Повторный запрос (retry после таймаута):**

1. Метод = POST ⇒ проверяем Idempotency-Key (строка 2)
2. key = "abc-123" ≠ ∅ ⇒ продолжаем (строка 6)
3. KeyStore.find("abc-123") ≠ ∅, cached.response ≠ ∅ (строка 10)
4. Возвращаем кэшированный ответ: `201 Created`, тело: `{id: "e7f1..."}` (строка 11)
5. **Заказ НЕ дублируется** — в таблице по-прежнему одна запись

**Трассировка состояний:**

| Шаг | Действие                      | KeyStore             | orders    |
|-----|-------------------------------|----------------------|-----------|
| 0   | Начальное состояние           | ∅                    | 0 записей |
| 1   | Получен key="abc-123"         | ∅                    | 0 записей |
| 2   | find("abc-123") = ∅           | ∅                    | 0 записей |
| 3   | register("abc-123")           | {abc-123: pending}   | 0 записей |
| 4   | INSERT INTO orders            | {abc-123: pending}   | 1 запись  |
| 5   | saveResponse("abc-123", 201)  | {abc-123: 201, body} | 1 запись  |
| 6   | Retry: key="abc-123"          | {abc-123: 201, body} | 1 запись  |
| 7   | find("abc-123") ≠ ∅           | {abc-123: 201, body} | 1 запись  |
| 8   | return cached(201)            | {abc-123: 201, body} | **1 запись** |

### 2.7. Типы организации контроллеров в Spring

В данной работе используются три кардинально различных подхода к организации REST-контроллеров в Spring Framework.

| Характеристика      | @RestController        | Functional Endpoints    | Spring Data REST     |
|----------------------|------------------------|-------------------------|----------------------|
| Стиль                | Аннотационный          | Функциональный          | Автогенерация        |
| Маршрутизация        | @GetMapping, @PostMapping | RouterFunction       | Автоматическая       |
| Обработчик           | Метод контроллера      | HandlerFunction         | Репозиторий          |
| Бизнес-логика        | В Service-слое         | В Handler-классе        | EventHandler         |
| Канонический пример  | Spring Guides REST     | Spring Framework docs   | Spring Data REST Guide |

#### @RestController (аннотационный подход)

Классический подход, применяемый в Spring Guides [5]. Контроллер аннотируется `@RestController`, маршруты определяются через `@GetMapping`, `@PostMapping` и т. д. Обработка запроса делегируется сервисному слою. Этот подход является наиболее распространённым в экосистеме Spring и обеспечивает декларативное определение API через аннотации.

Архитектура следует паттерну «контроллер–сервис–репозиторий»: контроллер принимает HTTP-запрос, валидирует DTO, передаёт в сервис, который содержит бизнес-логику и взаимодействует с репозиторием для доступа к данным. Идемпотентность POST обеспечивается внешним HTTP-фильтром (`IdempotencyFilter`), который перехватывает запрос до попадания в контроллер.

#### Functional Endpoints (WebMvc.fn)

Функциональный подход из Spring Framework [6]. Маршруты определяются программно через `RouterFunction<ServerResponse>`, а обработчики — через функции `ServerRequest → ServerResponse`. Полностью отсутствуют аннотации маршрутизации.

Ключевое отличие от `@RestController` — в способе композиции маршрутов. Вместо распределения маршрутов по аннотациям на методах, все маршруты определяются в одном месте (`PaymentRouter`) через цепочку вызовов `.GET()`, `.POST()` и т. д. Обработчики (`PaymentHandler`) — это обычные компоненты Spring, не наследующие от каких-либо контроллерных классов. Такой подход обеспечивает лучшую тестируемость отдельных обработчиков в изоляции.

Идемпотентность POST обеспечивается тем же `IdempotencyFilter`, что и для `@RestController`, поскольку фильтры работают на уровне Servlet API, до маршрутизации запроса.

#### Spring Data REST

Автоматическая генерация CRUD-эндпоинтов из JPA-репозитория [7]. Контроллер не пишется вручную — достаточно аннотировать репозиторий `@RepositoryRestResource`. Spring Data REST автоматически создаёт все CRUD-операции, поддерживает HATEOAS (Hypermedia As The Engine Of Application State), генерирует HAL-формат ответов и предоставляет HAL Explorer для интерактивного исследования API.

Идемпотентность обеспечивается встроенными механизмами:

- `@Version` (optimistic locking) — Hibernate проверяет версию при каждом UPDATE, Spring Data REST генерирует ETag из значения версии и поддерживает заголовки `If-Match` и `If-None-Match`;
- UNIQUE constraints на уровне базы данных предотвращают создание дубликатов;
- `@RepositoryEventHandler` позволяет добавить бизнес-логику через обработчики событий (`HandleBeforeCreate`, `HandleBeforeSave`).

### 2.8. Средства тестирования

- **JUnit 5** — фреймворк модульного тестирования, поддерживающий параметризованные тесты, вложенные классы и расширения [13].
- **Spring Boot Test** — интеграционное тестирование Spring-приложений с автоконфигурацией контекста [12].
- **MockMvc** — тестирование HTTP-запросов без запуска реального HTTP-сервера, с полной поддержкой фильтров и маршрутизации.
- **Testcontainers** — библиотека для запуска Docker-контейнеров в тестах. Используется singleton-паттерн: один контейнер PostgreSQL запускается при старте первого теста и переиспользуется всеми последующими [11].
- **Spring Profiles** — переключение конфигурации для тестирования. Профиль `test` включает идемпотентность, профиль `test-non-idempotent` отключает её, что позволяет запускать один и тот же набор тестов в двух режимах.

---

## 3. Результаты

### 3.1. Описание разработанного программного обеспечения

Разработано Spring Boot приложение, реализующее REST API для трёх доменов:

- **Заказы** (`/api/orders`) — управление заказами через аннотационный `@RestController`;
- **Платежи** (`/api/payments`) — обработка платежей через функциональные эндпоинты (`RouterFunction`);
- **Товары** (`/api/products`) — каталог товаров через автогенерируемые эндпоинты Spring Data REST.

**Стек технологий:** Spring Boot 3.3, Java 17, PostgreSQL 16, Flyway, Testcontainers, Docker Compose.

#### Структура проекта

```
src/main/java/com/example/idempotency/
├── config/         # Конфигурация (свойства, фильтры)
├── idempotency/    # Механизм идемпотентности
├── order/          # @RestController
├── payment/        # Functional Endpoints
├── product/        # Spring Data REST
└── exception/      # Обработка ошибок
```

#### Схема базы данных

База данных состоит из четырёх таблиц, управляемых через миграции Flyway:

| Таблица           | Ключевые поля                                        | Constraints         | Назначение |
|-------------------|------------------------------------------------------|---------------------|------------|
| orders            | id (UUID PK), description, amount, status            | UNIQUE(desc, amount)| Заказы     |
| payments          | id (UUID PK), order_id (FK), amount, status          | FK → orders(id)     | Платежи    |
| products          | id (BIGSERIAL PK), sku, name, price, version         | UNIQUE(sku)         | Товары     |
| idempotency_keys  | id, key, method, path, response_status, response_body| UNIQUE(key)         | Ключи      |

Таблица `idempotency_keys` содержит поле `expires_at` для TTL ключей (по умолчанию 24 часа). Поле `response_body` хранит сериализованный JSON-ответ для повторного использования.

#### Компоненты механизма идемпотентности

Механизм идемпотентности реализован через три компонента:

1. **IdempotencyFilter** — HTTP-фильтр (наследник `OncePerRequestFilter`), перехватывающий POST-запросы к эндпоинтам заказов и платежей. Реализует Алгоритм 1. Может быть отключён через параметр `app.idempotency.enabled=false`.

2. **IdempotencyService** — сервис для работы с хранилищем ключей. Предоставляет методы `findByKey()`, `registerKey()` и `saveResponse()`. Операции выполняются в транзакциях для обеспечения атомарности.

3. **IdempotencyKeyEntity** — JPA-сущность, представляющая запись в таблице `idempotency_keys`. Содержит ключ, HTTP-метод, путь запроса, статус и тело кэшированного ответа, а также временные метки создания и истечения.

#### Профили конфигурации

Приложение поддерживает два режима работы через Spring Profiles:

- **Профиль по умолчанию**: идемпотентность включена (`enabled=true`), Flyway применяет миграции V1 и V2, все constraints активны.
- **Профиль non-idempotent**: идемпотентность отключена (`enabled=false`), Flyway дополнительно применяет миграцию V3, которая удаляет UNIQUE constraints на таблицах `orders` и `products`.

Переключение профилей позволяет запускать одни и те же тесты в двух конфигурациях и наглядно демонстрировать последствия отсутствия механизмов идемпотентности.

### 3.2. Результаты тестирования (профиль idempotent)

Основной набор из 22 интеграционных тестов проходит успешно:

| Тестовый класс              | Тестов | Результат | Тип контроллера        |
|-----------------------------|--------|-----------|------------------------|
| OrderIdempotencyTest        | 6      | PASS      | @RestController        |
| PaymentIdempotencyTest      | 5      | PASS      | Functional Endpoints   |
| ProductIdempotencyTest      | 5      | PASS      | Spring Data REST       |
| DataImportTest              | 2      | PASS      | Импорт JSON/CSV        |
| NonIdempotentProfileTest    | 4      | PASS      | Демонстрация багов     |
| **Итого**                   | **22** | **PASS**  |                        |

### 3.3. Тест-кейсы по контроллерам

#### @RestController (OrderIdempotencyTest)

1. **POST с одинаковым Idempotency-Key дважды** — создаётся только один заказ, второй запрос возвращает кэшированный ответ. Проверяется через утверждение `assertEquals(1, count)`.
2. **POST с разными ключами** — создаются два разных заказа, что подтверждает: идемпотентность привязана к конкретному ключу, а не к содержимому запроса.
3. **POST без Idempotency-Key** — возвращается 400 Bad Request, что предотвращает случайное создание неидемпотентных запросов.
4. **PUT идемпотентен** — повторное обновление с теми же данными не изменяет состояние. Проверяется сравнением полей `description`, `amount`, `status` после двух последовательных PUT.
5. **DELETE идемпотентен** — первый вызов возвращает 204 No Content (ресурс удалён), второй — 404 Not Found (ресурс уже отсутствует). Оба варианта соответствуют идемпотентному поведению.
6. **GET идемпотентен** — три последовательных GET-запроса возвращают одинаковый JSON-ответ.

#### Functional Endpoints (PaymentIdempotencyTest)

1. **POST платежа с одним ключом дважды** — создаётся один платёж. Демонстрирует, что IdempotencyFilter работает с RouterFunction так же, как с @RestController.
2. **POST с разными ключами** — создаются два платежа.
3. **POST без ключа** — 400 Bad Request.
4. **GET идемпотентен** — стабильный результат при многократных запросах.
5. **Retry (3 запроса с одним ключом)** — создаётся один платёж, несмотря на 3 попытки. Это ключевой production-сценарий: клиент повторяет запрос из-за таймаута, но система обрабатывает его только один раз.

#### Spring Data REST (ProductIdempotencyTest)

1. **POST дубликата SKU** — 409 Conflict. UNIQUE constraint на поле `sku` предотвращает создание товара с тем же артикулом.
2. **PUT с корректной версией** — успешное обновление, `@Version` автоматически инкрементируется. Проверяется совпадение `version + 1` с актуальной версией.
3. **Concurrent PUT (два обновления со старой версией)** — первое обновление проходит успешно (версия 0 → 1), второе получает 412 Precondition Failed, поскольку передан устаревший ETag. Optimistic locking предотвращает lost update.
4. **PATCH идемпотентен** — повторное применение одного и того же PATCH не изменяет данные (имя товара остаётся тем же после второго PATCH).
5. **GET идемпотентен** — стабильный результат.

#### Импорт данных (DataImportTest)

1. **Импорт заказов из JSON** — загрузка данных из файла `orders-input.json`, создание через API, сравнение количества, статусов и сумм с эталонным файлом.
2. **Импорт товаров из CSV** — загрузка данных из файла `products-input.csv` через Jackson CSV, сохранение в БД, затем повторная попытка импорта: все 3 попытки отклоняются UNIQUE constraint, количество записей не увеличивается.

### 3.4. Результаты профиля non-idempotent

Профиль `non-idempotent` отключает механизмы идемпотентности:

- IdempotencyFilter пропускает все POST-запросы без проверки заголовка;
- Unique constraint на `orders(description, amount)` удалён миграцией V3;
- Unique constraint на `products(sku)` удалён миграцией V3.

| Тест                            | Обнаруженная проблема                                              | Статус |
|---------------------------------|--------------------------------------------------------------------|--------|
| POST без Idempotency-Key        | Запрос проходит без проверки — любой POST создаёт ресурс           | BUG    |
| Повторный POST с тем же ключом  | Создаётся дубликат заказа — ключ игнорируется                      | BUG    |
| POST с дубликатом SKU           | Создаётся второй товар с тем же артикулом                          | BUG    |
| Retry платежа (3 раза)          | 3 платежа вместо 1 — тройное списание средств                      | BUG    |

Тесты `NonIdempotentProfileTest` проходят зелёными, подтверждая наличие проблем. Каждый тест использует прямые assertions: например, проверяется, что `orderRepository.count() >= 2` — без фильтра действительно создаются дубликаты. Это демонстрирует критическое значение механизмов идемпотентности для корректного функционирования системы.

---

## 4. Обсуждение

### 4.1. Сравнение подходов к обеспечению идемпотентности

Результаты исследования показывают, что каждый тип контроллера Spring требует различного подхода к обеспечению идемпотентности.

| Критерий              | @RestController                   | Functional                        | Data REST                  |
|-----------------------|-----------------------------------|-----------------------------------|----------------------------|
| Механизм POST        | Idempotency-Key + фильтр         | Idempotency-Key + фильтр         | Unique constraints         |
| Механизм PUT         | Естественная идемпотентность      | Естественная идемпотентность      | @Version + ETag            |
| Concurrent защита    | На уровне фильтра                | На уровне фильтра                | Optimistic locking         |
| Сложность реализации | Средняя                           | Средняя                           | Низкая (автоматически)     |
| Гибкость             | Высокая                           | Высокая                           | Ограниченная               |

### 4.2. Анализ результатов

**Аннотационный @RestController** предоставляет наибольшую гибкость в реализации идемпотентности, но требует ручной реализации фильтра и хранилища ключей. Этот подход наиболее распространён в production-системах благодаря явности и читаемости кода. Разработчик полностью контролирует обработку запроса, может добавить произвольную логику валидации, трансформации и обработки ошибок. Недостаток — необходимость писать и поддерживать больше кода.

**Функциональные эндпоинты** (WebMvc.fn) используют тот же механизм Idempotency-Key через общий фильтр, что демонстрирует важное архитектурное преимущество — фильтры работают на уровне Servlet API, независимо от типа контроллера. Это означает, что один и тот же `IdempotencyFilter` обеспечивает идемпотентность как для `@RestController`, так и для `RouterFunction`. Функциональный стиль обеспечивает лучшую композиционность: маршруты можно комбинировать, вкладывать и переиспользовать программно, в отличие от аннотационного подхода, где маршруты привязаны к методам классов.

**Spring Data REST** автоматически обеспечивает ряд механизмов идемпотентности: `@Version` генерирует ETag, а unique constraints предотвращают дубликаты. Это минимизирует объём ручного кода — разработчику достаточно определить JPA-сущность и репозиторий. Однако этот подход ограничивает возможности кастомизации: Idempotency-Key не поддерживается автоматически, а бизнес-логика добавляется через EventHandler, что менее гибко, чем прямое управление в Service-слое.

### 4.3. Значимость Testcontainers в тестировании идемпотентности

Использование Testcontainers вместо in-memory баз данных (H2) имеет критическое значение для тестирования идемпотентности. Ряд механизмов зависит от особенностей конкретной СУБД:

- **UNIQUE constraints** — поведение при нарушении уникальности различается между H2 и PostgreSQL (разные коды ошибок, разная обработка транзакций).
- **Flyway-миграции** — синтаксис SQL может отличаться (например, `gen_random_uuid()` специфичен для PostgreSQL).
- **Конкурентный доступ** — PostgreSQL поддерживает уровни изоляции и блокировки, отличные от H2.

Singleton-паттерн Testcontainers (один контейнер на все тесты) обеспечивает баланс между точностью и скоростью: тесты работают с реальной СУБД, но контейнер запускается только один раз за сессию.

### 4.4. Рекомендации для production

На основе проведённого исследования сформулированы следующие рекомендации:

1. **Всегда требовать Idempotency-Key для POST-запросов**, создающих ресурсы или выполняющих побочные эффекты. Это предотвращает дублирование при retry и является отраслевым стандартом [14].
2. **Использовать optimistic locking** (`@Version`) для защиты от конкурентных обновлений. Это особенно важно для систем с высокой нагрузкой.
3. **Устанавливать TTL** для ключей идемпотентности. Рекомендуемое значение — 24 часа [3]. Слишком короткий TTL не защитит от поздних retry, слишком длинный — приведёт к росту таблицы ключей.
4. **Хранить ключи** в той же СУБД, что и бизнес-данные, для обеспечения транзакционной консистентности. Альтернатива (Redis) обеспечивает лучшую производительность, но вводит риск рассогласования.
5. **Тестировать с реальной СУБД** через Testcontainers [11], а не с in-memory заменителями, для достоверности результатов.
6. **Комбинировать механизмы**: Idempotency-Key для POST, @Version для PUT, UNIQUE constraints как последний рубеж защиты от дубликатов.

---

## 5. Заключение

В данной работе проведено исследование и демонстрация тестирования идемпотентности REST API на базе Spring Boot. Разработано приложение с тремя кардинально различными типами контроллеров, для каждого из которых реализованы и протестированы механизмы обеспечения идемпотентности.

Основные результаты:

- Формализована математическая модель идемпотентности HTTP-запросов с доказательством корректности паттерна Idempotency-Key.
- Реализован и протестирован паттерн Idempotency-Key с хранением ключей в PostgreSQL, применимый к любому типу контроллера через Servlet-фильтр.
- Продемонстрированы три кардинально различных подхода к организации REST API в Spring: аннотационный (@RestController), функциональный (RouterFunction) и автогенерируемый (Spring Data REST), каждый с собственным механизмом обеспечения идемпотентности.
- Разработано 22 интеграционных теста с использованием Testcontainers и PostgreSQL, покрывающих все аспекты идемпотентности для трёх типов контроллеров.
- Продемонстрировано критическое значение механизмов идемпотентности: при их отключении (профиль non-idempotent) система допускает дублирование данных и тройное списание при retry.

---

## 6. Инструкция по установке и запуску

### 6.1. Требования

- Java 17+ (JDK)
- Maven 3.8+
- Docker (для PostgreSQL и Testcontainers)

### 6.2. Запуск приложения

1. Запустить PostgreSQL через Docker Compose:

```bash
docker-compose up -d
```

2. Собрать и запустить приложение:

```bash
mvn spring-boot:run
```

3. API доступно по адресу `http://localhost:8080`:
   - `/api/orders` — заказы (@RestController)
   - `/api/payments` — платежи (Functional Endpoints)
   - `/api/products` — товары (Spring Data REST)
   - `/api/explorer` — HAL Explorer (Spring Data REST)

### 6.3. Запуск тестов

1. Запуск всех тестов (профиль с идемпотентностью):

```bash
mvn test
```

2. Запуск тестов без идемпотентности (демонстрация багов):

```bash
mvn test -Dtest="NonIdempotentProfileTest"
```

> **Примечание:** для запуска тестов требуется Docker, так как Testcontainers автоматически запускает PostgreSQL в контейнере.

### 6.4. Пример использования API

Создание заказа с Idempotency-Key:

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-key-123" \
  -d '{"description": "Ноутбук", "amount": 89999.99}'
```

Повторный запрос с тем же ключом вернёт кэшированный ответ (заказ не дублируется).

---

## 7. Список литературы

1. R. Fielding, J. Reschke. *Hypertext Transfer Protocol (HTTP/1.1): Semantics and Content*. RFC 7231, 2014.
2. R. Fielding, M. Nottingham, J. Reschke. *HTTP Semantics*. RFC 9110, 2022.
3. S. Dalal, J. Jena. *The Idempotency-Key HTTP Header Field*. IETF Internet-Draft, 2024.
4. L. Richardson, M. Amundsen, S. Ruby. *RESTful Web APIs*. O'Reilly Media, 2013.
5. Spring Team. *Building REST services with Spring*. Spring Guides, 2024.
6. Spring Framework. *Functional Endpoints — Spring Web MVC*. Spring Framework Reference Documentation, 2024.
7. Spring Team. *Accessing JPA Data with REST*. Spring Guides, 2024.
8. M. Fowler. *Patterns of Enterprise Application Architecture*. Addison-Wesley, 2002.
9. P. Helland. *Idempotence Is Not a Medical Condition*. Communications of the ACM, 55(5), pp. 56–65, 2012.
10. M. Kleppmann. *Designing Data-Intensive Applications*. O'Reilly Media, 2017.
11. AtomicJar. *Testcontainers — Integration testing with real dependencies*. 2024.
12. Spring Team. *Testing in Spring Boot*. Spring Boot Reference Documentation, 2024.
13. K. Beck. *Test Driven Development: By Example*. Addison-Wesley, 2002.
14. Stripe. *Idempotent Requests — Stripe API*. Stripe API Documentation, 2024.

---

## Приложение А. Листинги исходного кода

### А.1. docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: idempotency-postgres
    environment:
      POSTGRES_DB: idempotency_demo
      POSTGRES_USER: demo
      POSTGRES_PASSWORD: demo
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U demo -d idempotency_demo"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  pgdata:
```

### А.2. application.yml

```yaml
spring:
  application:
    name: idempotency-demo
  datasource:
    url: jdbc:postgresql://localhost:5432/idempotency_demo
    username: demo
    password: demo
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    rest:
      base-path: /api

app:
  idempotency:
    enabled: true
    key-ttl-hours: 24

server:
  port: 8080
```

### А.3. application-non-idempotent.yml

```yaml
app:
  idempotency:
    enabled: false

spring:
  flyway:
    locations: classpath:db/migration,classpath:db/migration-non-idempotent
  jpa:
    properties:
      hibernate:
        'jakarta.persistence.lock.timeout': 0
```

### А.4. V1__init_schema.sql

```sql
-- Таблица заказов (домен OrderController)
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    description VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_order_description_amount UNIQUE (description, amount)
);

-- Таблица платежей (домен PaymentRouter)
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    amount DECIMAL(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Таблица товаров (домен ProductRepository)
CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0
);
```

### А.5. V2__idempotency_keys.sql

```sql
CREATE TABLE idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    method VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_key ON idempotency_keys(idempotency_key);
CREATE INDEX idx_idempotency_expires ON idempotency_keys(expires_at);
```

### А.6. V3__drop_constraints.sql

```sql
ALTER TABLE orders DROP CONSTRAINT IF EXISTS uk_order_description_amount;
ALTER TABLE products DROP CONSTRAINT IF EXISTS products_sku_key;
```

### А.7. IdempotencyApplication.java

```java
package com.example.idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdempotencyApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdempotencyApplication.class, args);
    }
}
```

### А.8. IdempotencyProperties.java

```java
package com.example.idempotency.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {
    private boolean enabled = true;
    private int keyTtlHours = 24;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getKeyTtlHours() { return keyTtlHours; }
    public void setKeyTtlHours(int keyTtlHours) { this.keyTtlHours = keyTtlHours; }
}
```

### А.9. WebMvcConfig.java

```java
package com.example.idempotency.config;

import com.example.idempotency.idempotency.IdempotencyFilter;
import com.example.idempotency.idempotency.IdempotencyService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class WebMvcConfig {
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyService idempotencyService, IdempotencyProperties properties) {
        var reg = new FilterRegistrationBean<IdempotencyFilter>();
        reg.setFilter(new IdempotencyFilter(idempotencyService, properties));
        reg.addUrlPatterns("/api/orders/*", "/api/orders",
                           "/api/payments/*", "/api/payments");
        reg.setOrder(1);
        return reg;
    }
}
```

### А.10. IdempotencyKeyEntity.java

```java
package com.example.idempotency.idempotency;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt = LocalDateTime.now().plusHours(24);

    public IdempotencyKeyEntity() {}
    public IdempotencyKeyEntity(String key, String method, String path) {
        this.idempotencyKey = key; this.method = method; this.path = path;
    }

    // Getters и Setters
    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer s) { this.responseStatus = s; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String b) { this.responseBody = b; }
}
```

### А.11. IdempotencyKeyRepository.java

```java
package com.example.idempotency.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKeyEntity, Long> {
    Optional<IdempotencyKeyEntity> findByIdempotencyKey(String key);
}
```

### А.12. IdempotencyService.java

```java
package com.example.idempotency.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
public class IdempotencyService {
    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public Optional<IdempotencyKeyEntity> findByKey(String key) {
        return repository.findByIdempotencyKey(key);
    }

    @Transactional
    public IdempotencyKeyEntity registerKey(String key, String method, String path) {
        return repository.save(new IdempotencyKeyEntity(key, method, path));
    }

    @Transactional
    public void saveResponse(String key, int status, String responseBody) {
        repository.findByIdempotencyKey(key).ifPresent(entity -> {
            entity.setResponseStatus(status);
            entity.setResponseBody(responseBody);
            repository.save(entity);
        });
    }
}
```

### А.13. IdempotencyFilter.java

```java
package com.example.idempotency.idempotency;

import com.example.idempotency.config.IdempotencyProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private final IdempotencyService idempotencyService;
    private final IdempotencyProperties properties;

    public IdempotencyFilter(IdempotencyService svc, IdempotencyProperties props) {
        this.idempotencyService = svc; this.properties = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response); return;
        }
        if (!properties.isEnabled()) {
            chain.doFilter(request, response); return;
        }
        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":\"Idempotency-Key required\"}");
            return;
        }
        var existing = idempotencyService.findByKey(key);
        if (existing.isPresent()) {
            var cached = existing.get();
            if (cached.getResponseStatus() != null) {
                response.setStatus(cached.getResponseStatus());
                response.setContentType("application/json;charset=UTF-8");
                if (cached.getResponseBody() != null)
                    response.getWriter().write(cached.getResponseBody());
                return;
            }
            response.setStatus(HttpStatus.CONFLICT.value());
            return;
        }
        try {
            idempotencyService.registerKey(key,
                request.getMethod(), request.getRequestURI());
        } catch (DataIntegrityViolationException e) {
            response.setStatus(HttpStatus.CONFLICT.value()); return;
        }
        var wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        String body = new String(
            wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        idempotencyService.saveResponse(key, wrapper.getStatus(), body);
        wrapper.copyBodyToResponse();
    }
}
```

### А.14. Order.java

```java
package com.example.idempotency.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private String description;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false) @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.CREATED;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Order() {}
    public Order(String description, BigDecimal amount) {
        this.description = description; this.amount = amount;
    }
    // Getters и Setters
    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal a) { this.amount = a; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus s) { this.status = s; }
}
```

### А.15. OrderStatus.java

```java
package com.example.idempotency.order;

public enum OrderStatus { CREATED, CONFIRMED, CANCELLED }
```

### А.16. OrderDto.java

```java
package com.example.idempotency.order;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record OrderDto(
    @NotBlank(message = "Описание обязательно") String description,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    OrderStatus status
) {}
```

### А.17. OrderRepository.java

```java
package com.example.idempotency.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {}
```

### А.18. OrderService.java

```java
package com.example.idempotency.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OrderService {
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository repo) { this.orderRepository = repo; }

    public List<Order> findAll() { return orderRepository.findAll(); }

    public Order findById(UUID id) {
        return orderRepository.findById(id)
            .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional
    public Order create(OrderDto dto) {
        return orderRepository.save(new Order(dto.description(), dto.amount()));
    }

    @Transactional
    public Order update(UUID id, OrderDto dto) {
        var order = findById(id);
        order.setDescription(dto.description());
        order.setAmount(dto.amount());
        if (dto.status() != null) order.setStatus(dto.status());
        return orderRepository.save(order);
    }

    @Transactional
    public void delete(UUID id) { orderRepository.delete(findById(id)); }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("Order not found: " + id);
        }
    }
}
```

### А.19. OrderController.java

```java
package com.example.idempotency.order;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService svc) { this.orderService = svc; }

    @GetMapping
    public List<Order> list() { return orderService.findAll(); }

    @GetMapping("/{id}")
    public Order get(@PathVariable UUID id) { return orderService.findById(id); }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody OrderDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(orderService.create(dto));
    }

    @PutMapping("/{id}")
    public Order update(@PathVariable UUID id, @Valid @RequestBody OrderDto dto) {
        return orderService.update(id, dto);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { orderService.delete(id); }
}
```

### А.20. Payment.java

```java
package com.example.idempotency.payment;

import com.example.idempotency.order.Order;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false) @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Payment() {}
    public Payment(Order order, BigDecimal amount) {
        this.order = order; this.amount = amount;
    }
    // Getters и Setters
    public UUID getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
}
```

### А.21. PaymentStatus.java

```java
package com.example.idempotency.payment;

public enum PaymentStatus { PENDING, COMPLETED, FAILED }
```

### А.22. PaymentDto.java

```java
package com.example.idempotency.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentDto(UUID orderId, BigDecimal amount) {}
```

### А.23. PaymentRepository.java

```java
package com.example.idempotency.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {}
```

### А.24. PaymentService.java

```java
package com.example.idempotency.payment;

import com.example.idempotency.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public PaymentService(PaymentRepository pr, OrderRepository or) {
        this.paymentRepository = pr; this.orderRepository = or;
    }

    public List<Payment> findAll() { return paymentRepository.findAll(); }

    public Payment findById(UUID id) {
        return paymentRepository.findById(id)
            .orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional
    public Payment create(PaymentDto dto) {
        var order = orderRepository.findById(dto.orderId())
            .orElseThrow(() -> new OrderService.OrderNotFoundException(dto.orderId()));
        return paymentRepository.save(new Payment(order, dto.amount()));
    }

    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID id) {
            super("Payment not found: " + id);
        }
    }
}
```

### А.25. PaymentHandler.java

```java
package com.example.idempotency.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.*;
import java.net.URI;
import java.util.UUID;

@Component
public class PaymentHandler {
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentHandler(PaymentService svc, ObjectMapper om) {
        this.paymentService = svc; this.objectMapper = om;
    }

    public ServerResponse list(ServerRequest request) {
        return ServerResponse.ok().body(paymentService.findAll());
    }

    public ServerResponse getById(ServerRequest request) {
        var id = UUID.fromString(request.pathVariable("id"));
        try {
            return ServerResponse.ok().body(paymentService.findById(id));
        } catch (PaymentService.PaymentNotFoundException e) {
            return ServerResponse.notFound().build();
        }
    }

    public ServerResponse create(ServerRequest request) throws Exception {
        var dto = objectMapper.readValue(
            request.servletRequest().getInputStream(), PaymentDto.class);
        var payment = paymentService.create(dto);
        return ServerResponse
            .created(URI.create("/api/payments/" + payment.getId()))
            .body(payment);
    }
}
```

### А.26. PaymentRouter.java

```java
package com.example.idempotency.payment;

import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.servlet.function.RequestPredicates.*;

@Configuration
public class PaymentRouter {
    @Bean
    public RouterFunction<ServerResponse> paymentRoutes(PaymentHandler h) {
        return RouterFunctions.route()
            .path("/api/payments", b -> b
                .GET("", accept(APPLICATION_JSON), h::list)
                .GET("/{id}", accept(APPLICATION_JSON), h::getById)
                .POST("", contentType(APPLICATION_JSON), h::create)
            ).build();
    }
}
```

### А.27. Product.java

```java
package com.example.idempotency.product;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String sku;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;
    @Column(nullable = false)
    private Integer quantity = 0;
    @Version
    private Integer version;

    public Product() {}
    public Product(String sku, String name, BigDecimal price, Integer qty) {
        this.sku = sku; this.name = name; this.price = price; this.quantity = qty;
    }
    // Getters и Setters
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer q) { this.quantity = q; }
    public Integer getVersion() { return version; }
}
```

### А.28. ProductRepository.java

```java
package com.example.idempotency.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import java.util.Optional;

@RepositoryRestResource(collectionResourceRel = "products", path = "products")
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
}
```

### А.29. ProductEventHandler.java

```java
package com.example.idempotency.product;

import org.slf4j.*;
import org.springframework.data.rest.core.annotation.*;
import org.springframework.stereotype.Component;

@Component @RepositoryEventHandler
public class ProductEventHandler {
    private static final Logger log =
        LoggerFactory.getLogger(ProductEventHandler.class);

    @HandleBeforeCreate
    public void handleBeforeCreate(Product product) {
        log.info("Creating product: SKU={}", product.getSku());
        if (product.getQuantity() == null) product.setQuantity(0);
    }

    @HandleBeforeSave
    public void handleBeforeSave(Product product) {
        log.info("Updating product: id={}, version={}",
            product.getId(), product.getVersion());
    }
}
```

### А.30. GlobalExceptionHandler.java

```java
package com.example.idempotency.exception;

import com.example.idempotency.order.OrderService;
import com.example.idempotency.payment.PaymentService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderService.OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(
            OrderService.OrderNotFoundException e) {
        return buildError(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PaymentService.PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentNotFound(
            PaymentService.PaymentNotFoundException e) {
        return buildError(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException e) {
        return buildError(HttpStatus.CONFLICT, "Duplicate resource");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException e) {
        return buildError(HttpStatus.CONFLICT, "Version conflict");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException e) {
        var msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst().map(err -> err.getField() + ": "
                + err.getDefaultMessage()).orElse("Validation error");
        return buildError(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseEntity<Map<String, Object>> buildError(
            HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "timestamp", LocalDateTime.now().toString(),
            "status", status.value(),
            "error", status.getReasonPhrase(),
            "message", message));
    }
}
```

### А.31. BaseIntegrationTest.java

```java
package com.example.idempotency;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("idempotency_test")
            .withUsername("test").withPassword("test");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```
