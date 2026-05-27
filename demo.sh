#!/bin/bash
# =============================================================================
# Демонстрационный скрипт: Тестирование идемпотентности REST API
# Лабораторная работа №4 - Плахотников В.А., гр. 5130903/30303
# =============================================================================

BASE_URL="http://localhost:8080"

# Цвета
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m' # No Color

pause() {
    echo ""
    echo -e "${YELLOW}--- Нажмите Enter для продолжения ---${NC}"
    read -r
}

header() {
    echo ""
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo -e "${BOLD}${CYAN}  $1${NC}"
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo ""
}

subheader() {
    echo ""
    echo -e "${BOLD}>> $1${NC}"
    echo ""
}

success() {
    echo -e "${GREEN}[OK] $1${NC}"
}

fail() {
    echo -e "${RED}[BUG] $1${NC}"
}

info() {
    echo -e "${CYAN}[INFO] $1${NC}"
}

# Универсальная функция для выполнения и логирования curl-запросов
# Использование: do_curl <описание> <curl-аргументы...>
# Возвращает: выставляет LAST_STATUS и LAST_BODY
do_curl() {
    local label="$1"
    shift
    # Собираем команду для отображения
    local display_cmd="curl"
    for arg in "$@"; do
        if [[ "$arg" == *" "* || "$arg" == *"{"* ]]; then
            display_cmd+=" '$arg'"
        else
            display_cmd+=" $arg"
        fi
    done
    echo -e "${DIM}${label}${NC}"
    echo -e "${YELLOW}\$ ${display_cmd}${NC}"
    local response
    response=$(curl -s -w "\n%{http_code}" "$@")
    LAST_BODY=$(echo "$response" | sed '$d')
    LAST_STATUS=$(echo "$response" | tail -1)
    echo -e "  HTTP Status: ${BOLD}${LAST_STATUS}${NC}"
    if [ -n "$LAST_BODY" ]; then
        echo "  Response:"
        echo "$LAST_BODY" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || echo "    $LAST_BODY"
    fi
    echo ""
}

# Тихий curl (без вывода ответа, только лог)
do_curl_quiet() {
    local label="$1"
    shift
    local display_cmd="curl"
    for arg in "$@"; do
        if [[ "$arg" == *" "* || "$arg" == *"{"* ]]; then
            display_cmd+=" '$arg'"
        else
            display_cmd+=" $arg"
        fi
    done
    echo -e "${DIM}${label}${NC}"
    echo -e "${YELLOW}\$ ${display_cmd}${NC}"
    local response
    response=$(curl -s -w "\n%{http_code}" "$@")
    LAST_BODY=$(echo "$response" | sed '$d')
    LAST_STATUS=$(echo "$response" | tail -1)
    echo -e "  -> HTTP ${BOLD}${LAST_STATUS}${NC}"
}

# =============================================================================
header "ДЕМОНСТРАЦИЯ: Тестирование идемпотентности REST API"
# =============================================================================

# --- Шаг 1: Проверяем Docker ---
info "Проверяем PostgreSQL контейнер..."
if ! docker exec idempotency-postgres pg_isready -U demo -d idempotency_demo >/dev/null 2>&1; then
    info "Запускаем docker-compose..."
    docker-compose up -d 2>/dev/null
    sleep 2
fi
success "PostgreSQL готов"

# --- Шаг 2: Чистим БД ---
info "Очищаем БД (DROP SCHEMA)..."
docker exec idempotency-postgres psql -U demo -d idempotency_demo -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" \
  2>/dev/null
if [ $? -eq 0 ]; then
    success "БД очищена"
else
    fail "Не удалось очистить БД"
    exit 1
fi

# --- Шаг 3: Ждём перезапуска приложения ---
echo ""
info "Теперь перезапустите приложение: mvn spring-boot:run"
info "Нажимайте Enter для проверки готовности..."
while true; do
    echo -e -n "${YELLOW}--- Нажмите Enter для проверки API ---${NC}"
    read -r
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/orders" 2>/dev/null)
    if [ "$STATUS" = "200" ]; then
        success "API доступен на $BASE_URL"
        break
    else
        info "Ещё не готово (HTTP $STATUS)... перезапустите приложение и нажмите Enter"
    fi
done

pause

# =============================================================================
# СЦЕНАРИЙ 1: @RestController - Idempotency-Key
# =============================================================================
header "СЦЕНАРИЙ 1: @RestController (Orders) - Idempotency-Key"

info "Тип контроллера: аннотационный @RestController"
info "Домен: Заказы (/api/orders)"
info "Механизм: заголовок Idempotency-Key + фильтр"
echo ""

# --- 1.1 POST без ключа ---
subheader "1.1. POST без Idempotency-Key -> 400 Bad Request"
info "Проверяем, что POST без ключа отклоняется"

do_curl "POST /api/orders (без Idempotency-Key)" \
  -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" \
  -d '{"description":"Test without key","amount":100.00}'

if [ "$LAST_STATUS" = "400" ]; then
    success "Запрос отклонён - ключ обязателен для POST"
else
    fail "Ожидался 400, получен $LAST_STATUS"
fi

pause

# --- 1.2 POST с ключом (первый запрос) ---
subheader "1.2. POST с Idempotency-Key (первый раз) -> 201 Created"
ORDER_TS=$(date +%s)
IDEM_KEY="demo-order-$ORDER_TS"
info "Генерируем ключ: $IDEM_KEY"

do_curl "POST /api/orders (первый запрос)" \
  -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d "{\"description\":\"ASUS Laptop $ORDER_TS\",\"amount\":89999.99}"

ORDER_ID=$(echo "$LAST_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)

if [ "$LAST_STATUS" = "201" ]; then
    success "Заказ создан (id=$ORDER_ID)"
else
    fail "Ожидался 201, получен $LAST_STATUS"
fi

pause

# --- 1.3 POST с тем же ключом (retry) ---
subheader "1.3. POST с тем же Idempotency-Key (retry) -> 201 из кэша"
info "Повторяем ТОЧНО такой же запрос с ключом: $IDEM_KEY"
info "Ожидание: получим кэшированный ответ, заказ НЕ дублируется"

do_curl "POST /api/orders (retry с тем же ключом)" \
  -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d "{\"description\":\"ASUS Laptop $ORDER_TS\",\"amount\":89999.99}"

ORDER_ID_2=$(echo "$LAST_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)

if [ "$LAST_STATUS" = "201" ] && [ "$ORDER_ID" = "$ORDER_ID_2" ]; then
    success "Кэшированный ответ! Тот же ID=$ORDER_ID_2"
    success "Заказ НЕ дублирован - идемпотентность работает"
else
    fail "Неожиданный результат"
fi

pause

# --- 1.4 Проверяем в БД ---
subheader "1.4. Проверка: в списке заказов только ОДИН экземпляр"

do_curl "GET /api/orders (проверка списка)" \
  -X GET "$BASE_URL/api/orders"

EXPECTED_DESC="ASUS Laptop $ORDER_TS"
COUNT=$(echo "$LAST_BODY" | EXPECTED_DESC="$EXPECTED_DESC" python3 -c "import sys,json,os; desc=os.environ['EXPECTED_DESC']; data=json.load(sys.stdin); print(sum(1 for o in data if o.get('description')==desc))" 2>/dev/null)
info "Заказов с описанием '$EXPECTED_DESC': $COUNT"

if [ "$COUNT" = "1" ]; then
    success "Ровно один заказ - дубликат не создан!"
else
    fail "Найдено $COUNT заказов - дубликат!"
fi

pause

# --- 1.5 PUT идемпотентен ---
subheader "1.5. PUT /api/orders/{id} - естественная идемпотентность"
info "Дважды обновляем заказ одними и теми же данными"

if [ -n "$ORDER_ID" ]; then
    do_curl "PUT /api/orders/$ORDER_ID (#1)" \
      -X PUT "$BASE_URL/api/orders/$ORDER_ID" \
      -H "Content-Type: application/json" \
      -d "{\"description\":\"Updated order $ORDER_TS\",\"amount\":95000.00,\"status\":\"CONFIRMED\"}"
    RESP1="$LAST_BODY"

    do_curl "PUT /api/orders/$ORDER_ID (#2)" \
      -X PUT "$BASE_URL/api/orders/$ORDER_ID" \
      -H "Content-Type: application/json" \
      -d "{\"description\":\"Updated order $ORDER_TS\",\"amount\":95000.00,\"status\":\"CONFIRMED\"}"
    RESP2="$LAST_BODY"

    if [ "$RESP1" = "$RESP2" ]; then
        success "Оба PUT вернули одинаковый результат - PUT идемпотентен"
    else
        fail "Ответы отличаются!"
    fi
fi

pause

# --- 1.6 DELETE идемпотентен ---
subheader "1.6. DELETE /api/orders/{id} - идемпотентность удаления"
info "Удаляем заказ дважды"

if [ -n "$ORDER_ID" ]; then
    do_curl_quiet "DELETE /api/orders/$ORDER_ID (#1)" \
      -X DELETE "$BASE_URL/api/orders/$ORDER_ID"
    STATUS_DEL1="$LAST_STATUS"

    do_curl_quiet "DELETE /api/orders/$ORDER_ID (#2)" \
      -X DELETE "$BASE_URL/api/orders/$ORDER_ID"
    STATUS_DEL2="$LAST_STATUS"

    if [ "$STATUS_DEL1" = "204" ] && [ "$STATUS_DEL2" = "404" ]; then
        success "204 -> 404: ресурс удалён, повторное удаление безопасно"
    fi
fi

pause

# =============================================================================
# СЦЕНАРИЙ 2: Functional Endpoints - Платежи
# =============================================================================
header "СЦЕНАРИЙ 2: Functional Endpoints (Payments) - Retry Safety"

info "Тип контроллера: RouterFunction (WebMvc.fn)"
info "Домен: Платежи (/api/payments)"
info "Механизм: тот же IdempotencyFilter (Servlet-уровень)"
echo ""

# Создаём заказ для платежа
TS=$(date +%s)
ORDER_KEY="demo-pay-order-$TS"

do_curl "POST /api/orders (создаём заказ для платежа)" \
  -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $ORDER_KEY" \
  -d "{\"description\":\"Order for payment $TS\",\"amount\":50000.00}"

PAY_ORDER_ID=$(echo "$LAST_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
info "Создан заказ: $PAY_ORDER_ID"

pause

# --- 2.1 Тройной retry платежа ---
subheader "2.1. Тройной retry платежа с одним ключом"
info "Симуляция: клиент отправляет платёж 3 раза из-за таймаута"
info "Ожидание: создаётся ОДИН платёж, деньги списываются ОДИН раз"

PAY_KEY="demo-payment-$(date +%s)"
info "Idempotency-Key: $PAY_KEY"
echo ""

for i in 1 2 3; do
    do_curl "POST /api/payments (попытка $i/3)" \
      -X POST "$BASE_URL/api/payments" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $PAY_KEY" \
      -d "{\"orderId\":\"$PAY_ORDER_ID\",\"amount\":50000.00}"
done

# Проверяем количество
do_curl_quiet "GET /api/payments (проверка количества)" \
  -X GET "$BASE_URL/api/payments"

PAY_COUNT=$(echo "$LAST_BODY" | python3 -c "import sys,json; data=json.load(sys.stdin); print(sum(1 for p in data if str(p.get('order',{}).get('id',''))=='$PAY_ORDER_ID'))" 2>/dev/null)
info "Платежей к заказу $PAY_ORDER_ID: $PAY_COUNT"

if [ "$PAY_COUNT" = "1" ]; then
    success "Один платёж! Тройное списание предотвращено"
else
    fail "Создано $PAY_COUNT платежей - тройное списание!"
fi

pause

# =============================================================================
# СЦЕНАРИЙ 3: Spring Data REST - Optimistic Locking
# =============================================================================
header "СЦЕНАРИЙ 3: Spring Data REST (Products) - Optimistic Locking"

info "Тип контроллера: автогенерируемый из @RepositoryRestResource"
info "Домен: Товары (/api/products)"
info "Механизмы: @Version + ETag + UNIQUE constraints"
echo ""

# --- 3.1 Создание товара ---
subheader "3.1. Создание товара"
PROD_TS=$(date +%s)

do_curl "POST /api/products (создание товара)" \
  -X POST "$BASE_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"DEMO-$PROD_TS\",\"name\":\"Demo Product\",\"price\":999.99,\"quantity\":42}"

PRODUCT_URL=$(echo "$LAST_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('_links',{}).get('self',{}).get('href',''))" 2>/dev/null)
info "Product URL: $PRODUCT_URL"

pause

# --- 3.2 Дубликат SKU ---
subheader "3.2. POST с дубликатом SKU -> 409 Conflict"
DEMO_SKU="UNIQUE-SKU-$(date +%s)"

do_curl_quiet "POST /api/products (первый товар SKU=$DEMO_SKU)" \
  -X POST "$BASE_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"$DEMO_SKU\",\"name\":\"Product 1\",\"price\":100.00,\"quantity\":1}"

info "Пробуем создать ещё один с тем же SKU..."

do_curl "POST /api/products (дубликат SKU=$DEMO_SKU)" \
  -X POST "$BASE_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"$DEMO_SKU\",\"name\":\"Duplicate\",\"price\":200.00,\"quantity\":5}"

if [ "$LAST_STATUS" = "409" ]; then
    success "409 Conflict - UNIQUE constraint предотвратил дубликат"
else
    fail "Ожидался 409, получен $LAST_STATUS"
fi

pause

# --- 3.3 Optimistic Locking (ETag) ---
subheader "3.3. Optimistic Locking - конфликт версий (ETag)"
info "Создаём товар, получаем ETag, делаем два конкурентных PUT"

OL_SKU="LOCK-$(date +%s)"

do_curl_quiet "POST /api/products (создаём versioned товар)" \
  -X POST "$BASE_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"sku\":\"$OL_SKU\",\"name\":\"Versioned Product\",\"price\":500.00,\"quantity\":10}"

# Получаем товар и ETag
do_curl_quiet "GET /api/products/search/findBySku (поиск товара)" \
  -X GET "$BASE_URL/api/products/search/findBySku?sku=$OL_SKU"

PROD_HREF=$(echo "$LAST_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('_links',{}).get('self',{}).get('href',''))" 2>/dev/null)

if [ -n "$PROD_HREF" ]; then
    ETAG=$(curl -s -I "$PROD_HREF" | grep -i "^etag:" | tr -d '\r' | awk '{print $2}')
    info "ETag текущей версии: $ETAG"
    echo ""

    do_curl_quiet "PUT $PROD_HREF (Client A, ETag=$ETAG)" \
      -X PUT "$PROD_HREF" \
      -H "Content-Type: application/json" \
      -H "If-Match: $ETAG" \
      -d "{\"sku\":\"$OL_SKU\",\"name\":\"Client A updated\",\"price\":600.00,\"quantity\":15}"
    STATUS1="$LAST_STATUS"

    do_curl_quiet "PUT $PROD_HREF (Client B, STALE ETag=$ETAG)" \
      -X PUT "$PROD_HREF" \
      -H "Content-Type: application/json" \
      -H "If-Match: $ETAG" \
      -d "{\"sku\":\"$OL_SKU\",\"name\":\"Client B updated\",\"price\":700.00,\"quantity\":20}"
    STATUS2="$LAST_STATUS"

    echo ""
    if [ "$STATUS1" = "204" ] && [ "$STATUS2" = "412" ]; then
        success "204 -> 412: Optimistic locking предотвратил lost update!"
        info "Client A: успех (204). Client B: Precondition Failed (412)"
    fi
fi

pause

# =============================================================================
# СЦЕНАРИЙ 4: GET - стабильность ответов
# =============================================================================
header "СЦЕНАРИЙ 4: GET - идемпотентность и стабильность"

subheader "4.1. Три последовательных GET /api/orders"
info "Все три ответа должны быть идентичны"

do_curl_quiet "GET /api/orders (#1)" -X GET "$BASE_URL/api/orders"
RESP_A="$LAST_BODY"

do_curl_quiet "GET /api/orders (#2)" -X GET "$BASE_URL/api/orders"
RESP_B="$LAST_BODY"

do_curl_quiet "GET /api/orders (#3)" -X GET "$BASE_URL/api/orders"
RESP_C="$LAST_BODY"

echo ""
if [ "$RESP_A" = "$RESP_B" ] && [ "$RESP_B" = "$RESP_C" ]; then
    success "Все 3 GET-запроса вернули идентичный результат"
    info "GET - safe и идемпотентный метод (RFC 9110)"
else
    fail "Ответы отличаются!"
fi

pause

# =============================================================================
header "ДЕМОНСТРАЦИЯ ЗАВЕРШЕНА"
# =============================================================================

echo ""
info "Итоги демонстрации:"
echo ""
echo "  1. Idempotency-Key предотвращает дублирование при retry (Orders, Payments)"
echo "  2. Один IdempotencyFilter работает для @RestController и RouterFunction"
echo "  3. Spring Data REST: @Version + ETag защищает от lost update"
echo "  4. UNIQUE constraints - последний рубеж защиты от дубликатов"
echo "  5. GET/PUT/DELETE - идемпотентны по определению (RFC 9110)"
echo ""
success "Спасибо за внимание!"
echo ""
