#!/bin/bash
# =============================================================================
# Демонстрационный скрипт: Идемпотентность gRPC / SOAP / GraphQL
# Лабораторная работа №4 - Плахотников В.А., гр. 5130903/30303
#
# Дополнение к demo.sh (REST). Идёт по тем же трём транспортам:
#   - gRPC  (NotificationService.Send)     - ServerInterceptor + cached protobuf
#   - SOAP  (Shipments.createShipment)     - общий IdempotencyFilter + cached envelope
#   - GraphQL (reserveInventory mutation)  - общий IdempotencyFilter + cached JSON
#
# Скрипт не чистит БД: каждый прогон генерирует уникальные tracking/item/recipient
# с суффиксом по timestamp, поэтому его можно запускать многократно.
# =============================================================================

BASE_URL="http://localhost:8080"
GRPC_HOST="localhost:9090"
PROTO_FILE="src/main/proto/notification.proto"
PROTO_IMPORT_PATH="src/main/proto"
GRPC_SERVICE="com.example.idempotency.grpc.notification.NotificationService"

# Цвета (совпадают с demo.sh)
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

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

success() { echo -e "${GREEN}[OK] $1${NC}"; }
fail()    { echo -e "${RED}[BUG] $1${NC}"; }
info()    { echo -e "${CYAN}[INFO] $1${NC}"; }

# -----------------------------------------------------------------------------
# Универсальный curl с логированием (как в demo.sh)
# -----------------------------------------------------------------------------
do_curl() {
    local label="$1"
    shift
    local display_cmd="curl"
    for arg in "$@"; do
        if [[ "$arg" == *" "* || "$arg" == *"{"* || "$arg" == *"<"* ]]; then
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
        # Пытаемся красиво распечатать: сначала JSON, потом XML, иначе как есть
        if echo "$LAST_BODY" | python3 -m json.tool >/dev/null 2>&1; then
            echo "$LAST_BODY" | python3 -m json.tool | sed 's/^/    /'
        elif echo "$LAST_BODY" | xmllint --format - >/dev/null 2>&1; then
            echo "$LAST_BODY" | xmllint --format - 2>/dev/null | sed 's/^/    /'
        else
            echo "    $LAST_BODY"
        fi
    fi
    echo ""
}

# Тихий curl - только статус, без печати тела
do_curl_quiet() {
    local label="$1"
    shift
    local display_cmd="curl"
    for arg in "$@"; do
        if [[ "$arg" == *" "* || "$arg" == *"{"* || "$arg" == *"<"* ]]; then
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

# -----------------------------------------------------------------------------
# Универсальный grpcurl с логированием
# Возвращает: LAST_GRPC_BODY, LAST_GRPC_RC (exit code), LAST_GRPC_ERR (stderr)
# -----------------------------------------------------------------------------
do_grpcurl() {
    local label="$1"
    shift
    local display_cmd="grpcurl"
    for arg in "$@"; do
        if [[ "$arg" == *" "* || "$arg" == *"{"* || "$arg" == *":"* ]]; then
            display_cmd+=" '$arg'"
        else
            display_cmd+=" $arg"
        fi
    done
    echo -e "${DIM}${label}${NC}"
    echo -e "${YELLOW}\$ ${display_cmd}${NC}"
    local tmp_err
    tmp_err=$(mktemp)
    LAST_GRPC_BODY=$(grpcurl "$@" 2>"$tmp_err")
    LAST_GRPC_RC=$?
    LAST_GRPC_ERR=$(cat "$tmp_err")
    rm -f "$tmp_err"
    if [ "$LAST_GRPC_RC" -eq 0 ]; then
        echo "  Response:"
        echo "$LAST_GRPC_BODY" | sed 's/^/    /'
    else
        echo -e "  ${RED}grpcurl exit=$LAST_GRPC_RC${NC}"
        echo "$LAST_GRPC_ERR" | sed 's/^/    /'
    fi
    echo ""
}

# -----------------------------------------------------------------------------
# Проверка зависимостей и готовности приложения
# -----------------------------------------------------------------------------
header "ДЕМОНСТРАЦИЯ: gRPC / SOAP / GraphQL - идемпотентность"

info "Проверяем зависимости..."
NEED_TOOLS=()
command -v curl    >/dev/null 2>&1 || NEED_TOOLS+=("curl")
command -v jq      >/dev/null 2>&1 || NEED_TOOLS+=("jq")
command -v xmllint >/dev/null 2>&1 || NEED_TOOLS+=("xmllint (libxml2)")
command -v python3 >/dev/null 2>&1 || NEED_TOOLS+=("python3")
if [ ${#NEED_TOOLS[@]} -gt 0 ]; then
    fail "Не найдены: ${NEED_TOOLS[*]}"
    info "Установите недостающие утилиты и повторите запуск."
    exit 1
fi

GRPCURL_OK=1
if ! command -v grpcurl >/dev/null 2>&1; then
    GRPCURL_OK=0
    fail "grpcurl не установлен - сценарий 1 (gRPC) будет пропущен."
    info "Установка на macOS:  brew install grpcurl"
    info "Установка на Linux:  https://github.com/fullstorydev/grpcurl/releases"
fi

if [ ! -f "$PROTO_FILE" ]; then
    fail "Не найден $PROTO_FILE - запускайте скрипт из корня репозитория."
    exit 1
fi

info "Проверяем доступность приложения на $BASE_URL ..."
while true; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/orders" 2>/dev/null)
    if [ "$STATUS" = "200" ]; then
        success "HTTP API доступен ($BASE_URL)"
        break
    fi
    echo -e -n "${YELLOW}--- API не отвечает (HTTP $STATUS). Запустите 'mvn spring-boot:run' и нажмите Enter ---${NC}"
    read -r
done

if [ "$GRPCURL_OK" -eq 1 ]; then
    info "Проверяем gRPC-порт ($GRPC_HOST) ..."
    # Шлём заведомо невалидный вызов, чтобы получить любой ответ от сервера
    if grpcurl -plaintext -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
        "$GRPC_HOST" "$GRPC_SERVICE/List" >/dev/null 2>&1; then
        success "gRPC-сервер слушает $GRPC_HOST"
    else
        # Без ключа сервер вернёт INVALID_ARGUMENT - это уже значит, что порт жив
        ERR=$(grpcurl -plaintext -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
            "$GRPC_HOST" "$GRPC_SERVICE/List" 2>&1)
        if echo "$ERR" | grep -qi "InvalidArgument\|idempotency-key"; then
            success "gRPC-сервер слушает $GRPC_HOST (вернул InvalidArgument, как и ожидалось)"
        else
            fail "gRPC-сервер на $GRPC_HOST не отвечает: $ERR"
            GRPCURL_OK=0
        fi
    fi
fi

pause

# =============================================================================
# СЦЕНАРИЙ 1: gRPC - NotificationService.Send
# =============================================================================
if [ "$GRPCURL_OK" -eq 1 ]; then
header "СЦЕНАРИЙ 1: gRPC (Notifications) - Idempotency-Key через Metadata"

info "Транспорт: HTTP/2 + protobuf"
info "Механизм: GrpcIdempotencyInterceptor сохраняет байты ответа,"
info "          реплей десериализует их через MethodDescriptor.responseMarshaller"
echo ""

# --- 1.1 Send без ключа ---
subheader "1.1. Send без idempotency-key -> INVALID_ARGUMENT"

do_grpcurl "Send (без metadata)" \
  -plaintext \
  -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
  -d '{"recipient":"no-key","message":"should be rejected"}' \
  "$GRPC_HOST" "$GRPC_SERVICE/Send"

if echo "$LAST_GRPC_ERR" | grep -qi "InvalidArgument"; then
    success "Запрос отклонён - ключ обязателен (как и для REST POST)"
else
    fail "Ожидался InvalidArgument, получено: $LAST_GRPC_ERR"
fi

pause

# --- 1.2 Send с ключом (первый раз) ---
GRPC_TS=$(date +%s)
GRPC_KEY="demo-grpc-$GRPC_TS"
GRPC_RECIPIENT="user-$GRPC_TS"
subheader "1.2. Send с idempotency-key (первый раз) -> OK"
info "Idempotency-Key: $GRPC_KEY"
info "Recipient:        $GRPC_RECIPIENT"

do_grpcurl "Send #1" \
  -plaintext \
  -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
  -H "idempotency-key: $GRPC_KEY" \
  -d "{\"recipient\":\"$GRPC_RECIPIENT\",\"message\":\"Hello from demo $GRPC_TS\"}" \
  "$GRPC_HOST" "$GRPC_SERVICE/Send"

GRPC_ID_1=$(echo "$LAST_GRPC_BODY" | jq -r '.id // empty')
if [ -n "$GRPC_ID_1" ]; then
    success "Уведомление создано (id=$GRPC_ID_1)"
else
    fail "Не удалось получить id из ответа"
fi

pause

# --- 1.3 Retry с тем же ключом ---
subheader "1.3. Send с тем же ключом (retry) -> реплей из кэша"
info "Ожидание: тот же id, тот же status; в БД ничего нового"

do_grpcurl "Send #2 (retry с ключом $GRPC_KEY)" \
  -plaintext \
  -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
  -H "idempotency-key: $GRPC_KEY" \
  -d "{\"recipient\":\"$GRPC_RECIPIENT\",\"message\":\"Hello from demo $GRPC_TS\"}" \
  "$GRPC_HOST" "$GRPC_SERVICE/Send"

GRPC_ID_2=$(echo "$LAST_GRPC_BODY" | jq -r '.id // empty')

if [ -n "$GRPC_ID_1" ] && [ "$GRPC_ID_1" = "$GRPC_ID_2" ]; then
    success "Тот же id=$GRPC_ID_2 - protobuf-ответ восстановлен из кэша"
else
    fail "id отличаются: '$GRPC_ID_1' != '$GRPC_ID_2'"
fi

pause

# --- 1.4 Проверка через List ---
subheader "1.4. NotificationService/List - уведомлений с recipient='$GRPC_RECIPIENT' ровно 1"

do_grpcurl "List (получаем весь список)" \
  -plaintext \
  -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
  -H "idempotency-key: list-$GRPC_KEY" \
  -d '{}' \
  "$GRPC_HOST" "$GRPC_SERVICE/List"

GRPC_COUNT=$(echo "$LAST_GRPC_BODY" | jq --arg r "$GRPC_RECIPIENT" '[.items[]? | select(.recipient==$r)] | length')
info "Уведомлений с recipient='$GRPC_RECIPIENT': $GRPC_COUNT"
if [ "$GRPC_COUNT" = "1" ]; then
    success "Ровно одно - дубликат не создан"
else
    fail "Найдено $GRPC_COUNT - дубликат!"
fi

pause

# --- 1.5 Новый ключ -> новая запись ---
subheader "1.5. Send с НОВЫМ ключом -> новый id (ключ - основа разделения вызовов)"
GRPC_KEY2="demo-grpc-${GRPC_TS}-v2"

do_grpcurl "Send (новый ключ)" \
  -plaintext \
  -import-path "$PROTO_IMPORT_PATH" -proto "$PROTO_FILE" \
  -H "idempotency-key: $GRPC_KEY2" \
  -d "{\"recipient\":\"$GRPC_RECIPIENT\",\"message\":\"second\"}" \
  "$GRPC_HOST" "$GRPC_SERVICE/Send"

GRPC_ID_3=$(echo "$LAST_GRPC_BODY" | jq -r '.id // empty')
if [ -n "$GRPC_ID_3" ] && [ "$GRPC_ID_3" != "$GRPC_ID_1" ]; then
    success "Создано новое уведомление id=$GRPC_ID_3"
else
    fail "Новый ключ должен дать новый id, а получили '$GRPC_ID_3'"
fi

pause
else
    info "Сценарий 1 (gRPC) пропущен - нет grpcurl."
fi

# =============================================================================
# СЦЕНАРИЙ 2: SOAP - Shipments.createShipment
# =============================================================================
header "СЦЕНАРИЙ 2: SOAP (Shipments) - Idempotency-Key + cached envelope"

info "Транспорт: HTTP + text/xml (SOAP 1.1, contract-first)"
info "Механизм: тот же IdempotencyFilter (Servlet) - кэширует SOAP-envelope целиком"
echo ""

SOAP_URL="$BASE_URL/api/soap/"
SOAP_TS=$(date +%s)
SOAP_TN="TR-DEMO-$SOAP_TS"
SOAP_KEY="demo-soap-$SOAP_TS"

build_envelope() {
    local tn="$1"
    local recipient="$2"
    cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns:createShipmentRequest xmlns:ns="http://example.com/idempotency/shipments">
      <ns:trackingNumber>$tn</ns:trackingNumber>
      <ns:recipient>$recipient</ns:recipient>
    </ns:createShipmentRequest>
  </soap:Body>
</soap:Envelope>
EOF
}

# --- 2.1 POST без Idempotency-Key ---
subheader "2.1. POST без Idempotency-Key -> 400 Bad Request"
SOAP_ENV=$(build_envelope "TR-NO-KEY-$SOAP_TS" "Х")

do_curl "POST /api/soap/ (без ключа)" \
  -X POST "$SOAP_URL" \
  -H "Content-Type: text/xml" \
  -H "SOAPAction: " \
  --data-binary "$SOAP_ENV"

if [ "$LAST_STATUS" = "400" ]; then
    success "400 - фильтр требует ключ независимо от транспорта"
else
    fail "Ожидался 400, получен $LAST_STATUS"
fi

pause

# --- 2.2 Первый POST с ключом ---
subheader "2.2. POST с Idempotency-Key (первый раз) -> 200 OK"
info "trackingNumber: $SOAP_TN"
info "Idempotency-Key: $SOAP_KEY"

SOAP_ENV=$(build_envelope "$SOAP_TN" "Иванов И.И.")

do_curl "POST /api/soap/ (первый)" \
  -X POST "$SOAP_URL" \
  -H "Content-Type: text/xml" \
  -H "SOAPAction: " \
  -H "Idempotency-Key: $SOAP_KEY" \
  --data-binary "$SOAP_ENV"

SOAP_BODY_1="$LAST_BODY"
SOAP_STATUS_1="$LAST_STATUS"
SOAP_ID_1=$(echo "$SOAP_BODY_1" \
    | xmllint --xpath "string(//*[local-name()='createShipmentResponse']/*[local-name()='id'])" - 2>/dev/null)

if [ "$SOAP_STATUS_1" = "200" ] && [ -n "$SOAP_ID_1" ]; then
    success "Shipment создан (id=$SOAP_ID_1)"
else
    fail "Ожидался 200 + id, получено $SOAP_STATUS_1"
fi

pause

# --- 2.3 Retry - байт-в-байт тот же envelope ---
subheader "2.3. POST с тем же ключом (retry) -> кэшированный envelope"
info "Ожидание: тот же id, и весь XML-ответ идентичен первому байт-в-байт"

do_curl "POST /api/soap/ (retry с ключом $SOAP_KEY)" \
  -X POST "$SOAP_URL" \
  -H "Content-Type: text/xml" \
  -H "SOAPAction: " \
  -H "Idempotency-Key: $SOAP_KEY" \
  --data-binary "$SOAP_ENV"

SOAP_BODY_2="$LAST_BODY"
SOAP_ID_2=$(echo "$SOAP_BODY_2" \
    | xmllint --xpath "string(//*[local-name()='createShipmentResponse']/*[local-name()='id'])" - 2>/dev/null)

if [ "$SOAP_ID_1" = "$SOAP_ID_2" ] && [ "$SOAP_BODY_1" = "$SOAP_BODY_2" ]; then
    success "Идентичный envelope, тот же id=$SOAP_ID_2"
else
    fail "Различия! id1='$SOAP_ID_1' id2='$SOAP_ID_2'"
fi

pause

# --- 2.4 Проверка getShipment (запрос идемпотентен по природе) ---
subheader "2.4. getShipmentRequest по trackingNumber - стабильный ответ"

GET_ENV=$(cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns:getShipmentRequest xmlns:ns="http://example.com/idempotency/shipments">
      <ns:trackingNumber>$SOAP_TN</ns:trackingNumber>
    </ns:getShipmentRequest>
  </soap:Body>
</soap:Envelope>
EOF
)

# getShipment - это тоже POST (SOAP всегда POST), но фильтр требует ключ.
# Используем разные ключи для двух get'ов - содержимое ответа должно совпасть.
do_curl_quiet "GET shipment #1" \
  -X POST "$SOAP_URL" \
  -H "Content-Type: text/xml" \
  -H "SOAPAction: " \
  -H "Idempotency-Key: get-$SOAP_KEY-a" \
  --data-binary "$GET_ENV"
GET_RESP_A="$LAST_BODY"

do_curl_quiet "GET shipment #2" \
  -X POST "$SOAP_URL" \
  -H "Content-Type: text/xml" \
  -H "SOAPAction: " \
  -H "Idempotency-Key: get-$SOAP_KEY-b" \
  --data-binary "$GET_ENV"
GET_RESP_B="$LAST_BODY"

GET_ID_A=$(echo "$GET_RESP_A" | xmllint --xpath "string(//*[local-name()='getShipmentResponse']/*[local-name()='id'])" - 2>/dev/null)
GET_ID_B=$(echo "$GET_RESP_B" | xmllint --xpath "string(//*[local-name()='getShipmentResponse']/*[local-name()='id'])" - 2>/dev/null)
echo ""
info "id из get #1: $GET_ID_A"
info "id из get #2: $GET_ID_B"
if [ -n "$GET_ID_A" ] && [ "$GET_ID_A" = "$GET_ID_B" ] && [ "$GET_ID_A" = "$SOAP_ID_1" ]; then
    success "Оба запроса вернули тот же id, что и при createShipment"
else
    fail "Несоответствие id"
fi

pause

# =============================================================================
# СЦЕНАРИЙ 3: GraphQL - reserveInventory mutation
# =============================================================================
header "СЦЕНАРИЙ 3: GraphQL (Inventory) - Idempotency-Key для мутаций"

info "Транспорт: HTTP + application/json (single endpoint POST /graphql)"
info "Механизм: тот же IdempotencyFilter - путь /graphql добавлен в URL-паттерны"
echo ""

GQL_URL="$BASE_URL/graphql"
GQL_TS=$(date +%s)
GQL_ITEM="widget-demo-$GQL_TS"
GQL_KEY="demo-gql-$GQL_TS"

mutation_body() {
    local item="$1"
    local qty="$2"
    cat <<EOF
{"query":"mutation { reserveInventory(input:{itemName:\"$item\",quantity:$qty}) { id itemName quantity } }"}
EOF
}

# --- 3.1 Mutation без Idempotency-Key ---
subheader "3.1. POST /graphql (mutation) без Idempotency-Key -> 400"

BODY=$(mutation_body "no-key-$GQL_TS" 1)
do_curl "POST /graphql (без ключа)" \
  -X POST "$GQL_URL" \
  -H "Content-Type: application/json" \
  -d "$BODY"

if [ "$LAST_STATUS" = "400" ]; then
    success "400 - фильтр блокирует любую POST-мутацию без ключа"
else
    fail "Ожидался 400, получен $LAST_STATUS"
fi

pause

# --- 3.2 Mutation первый раз ---
subheader "3.2. Mutation reserveInventory (первый раз) -> 200 OK"
info "itemName:        $GQL_ITEM"
info "Idempotency-Key: $GQL_KEY"

BODY=$(mutation_body "$GQL_ITEM" 10)
do_curl "POST /graphql (mutation #1)" \
  -X POST "$GQL_URL" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $GQL_KEY" \
  -d "$BODY"

GQL_BODY_1="$LAST_BODY"
GQL_STATUS_1="$LAST_STATUS"
GQL_ID_1=$(echo "$GQL_BODY_1" | jq -r '.data.reserveInventory.id // empty')

if [ "$GQL_STATUS_1" = "200" ] && [ -n "$GQL_ID_1" ]; then
    success "Inventory создан (id=$GQL_ID_1)"
else
    fail "Ожидался 200 + id, получено $GQL_STATUS_1"
fi

pause

# --- 3.3 Retry с тем же ключом ---
subheader "3.3. Mutation с тем же ключом (retry) -> идентичный JSON"

do_curl "POST /graphql (mutation #2, retry)" \
  -X POST "$GQL_URL" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $GQL_KEY" \
  -d "$BODY"

GQL_BODY_2="$LAST_BODY"
GQL_ID_2=$(echo "$GQL_BODY_2" | jq -r '.data.reserveInventory.id // empty')

if [ "$GQL_ID_1" = "$GQL_ID_2" ] && [ "$GQL_BODY_1" = "$GQL_BODY_2" ]; then
    success "Тот же id=$GQL_ID_2, JSON-ответ байт-в-байт"
else
    fail "Различия! id1='$GQL_ID_1' id2='$GQL_ID_2'"
fi

pause

# --- 3.4 Проверка через query ---
subheader "3.4. Query inventories - записи с itemName='$GQL_ITEM' ровно 1"

QUERY='{"query":"{ inventories { id itemName quantity } }"}'

# Query через POST тоже идёт через фильтр - нужен ключ
do_curl "POST /graphql (query)" \
  -X POST "$GQL_URL" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: query-$GQL_KEY" \
  -d "$QUERY"

GQL_COUNT=$(echo "$LAST_BODY" | jq --arg n "$GQL_ITEM" '[.data.inventories[]? | select(.itemName==$n)] | length')
info "Записей с itemName='$GQL_ITEM': $GQL_COUNT"
if [ "$GQL_COUNT" = "1" ]; then
    success "Дубликат не создан"
else
    fail "Найдено $GQL_COUNT - дубликат!"
fi

pause

# --- 3.5 Mutation с новым ключом -> новая запись ---
subheader "3.5. Mutation с НОВЫМ ключом и НОВЫМ itemName -> новая запись"
GQL_ITEM2="widget-demo-${GQL_TS}-v2"
GQL_KEY2="demo-gql-${GQL_TS}-v2"

BODY2=$(mutation_body "$GQL_ITEM2" 5)
do_curl_quiet "POST /graphql (новая мутация)" \
  -X POST "$GQL_URL" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $GQL_KEY2" \
  -d "$BODY2"

GQL_ID_3=$(echo "$LAST_BODY" | jq -r '.data.reserveInventory.id // empty')
if [ -n "$GQL_ID_3" ] && [ "$GQL_ID_3" != "$GQL_ID_1" ]; then
    success "Создан новый Inventory id=$GQL_ID_3"
else
    fail "Новый ключ должен дать новый id, получили '$GQL_ID_3'"
fi

pause

# =============================================================================
header "ДЕМОНСТРАЦИЯ ЗАВЕРШЕНА"
# =============================================================================

echo ""
info "Итоги:"
echo ""
echo "  1. gRPC      - GrpcIdempotencyInterceptor + cached protobuf (HTTP/2)"
echo "  2. SOAP      - общий IdempotencyFilter + cached XML envelope (text/xml)"
echo "  3. GraphQL   - общий IdempotencyFilter + cached JSON (application/json)"
echo ""
echo "  Один IdempotencyService (БД-таблица idempotency_keys) обслуживает все"
echo "  три транспорта + REST/Spring Data REST из demo.sh - ключ универсален."
echo ""
success "Спасибо за внимание!"
echo ""
