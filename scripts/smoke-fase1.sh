#!/usr/bin/env bash
# Smoke ponta a ponta da Fase 1 (plano 01-06).
#
# Sobe a infra (RabbitMQ + MongoDB) com o docker compose, inicia o worker e a API a partir dos jars
# e confere o esqueleto inteiro contra o broker e o banco de verdade:
#   - topologia no RabbitMQ (exchanges, filas principais, filas de delay por nível e DLQs);
#   - índices nomeados e seed de templates no MongoDB;
#   - POST → 202 + Location → SENT, renderizado em pt-BR e processado em virtual thread;
#   - caminhos de falha: 400 sem persistir, 503 PUBLISH_FAILED, duplicata sem reenvio, DLQ sem loop.
#
# Uso (a partir de qualquer diretório; o script faz cd para a raiz do repositório):
#   bash scripts/smoke-fase1.sh [--sem-build] [--perfil-demo]
#
#   --sem-build    não roda o ./mvnw package (usa os jars que já estão em */target)
#   --perfil-demo  sobe API e worker com --spring.profiles.active=demo e confere só a topologia
#                  com os níveis 1s/5s/25s, índices/seed e o caminho feliz EMAIL
#
# Pré-requisitos: Docker Desktop ligado, JDK 21 no PATH, curl. Nada pode estar ouvindo em 8080 nem 8081.
# Logs das apps e respostas HTTP ficam em target/smoke/.
#
# O script NÃO roda "docker compose down": a infra fica no ar para inspeção no painel
# (http://localhost:15672, notify/notify). Os volumes também não são apagados, então o teste não
# depende de banco vazio: ele compara contagens antes/depois e segue ids próprios.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
RAIZ=$(pwd)
SMOKE_DIR="$RAIZ/target/smoke"
VERSAO="0.1.0-SNAPSHOT"
API_URL="http://localhost:8080"
WORKER_URL="http://localhost:8081"
RABBIT_API_URL="http://localhost:15672/api"
RABBIT_CRED="notify:notify"

uso() {
  # Imprime o bloco de comentários do topo do arquivo.
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

SEM_BUILD=false
PERFIL_DEMO=false
for arg in "$@"; do
  case "$arg" in
    --sem-build) SEM_BUILD=true ;;
    --perfil-demo) PERFIL_DEMO=true ;;
    -h|--help) uso; exit 0 ;;
    *) echo "Opção desconhecida: $arg" >&2; uso >&2; exit 2 ;;
  esac
done

if $PERFIL_DEMO; then
  NIVEIS=(1s 5s 25s)
  PERFIL_ARGS=(--spring.profiles.active=demo)
else
  NIVEIS=(5s 25s 125s)
  PERFIL_ARGS=()
fi
CANAIS=(email sms webhook)

WORKER_PID=""
API_PID=""

# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

ok() { echo "OK: $*"; }

# Sai com 1. Nunca chame dentro de $(...): só encerraria o subshell.
falhou() { echo "FALHOU: $*"; exit 1; }

info() { echo "-- $*"; }

# GET na API de gerenciamento do RabbitMQ (falha se o status HTTP não for 2xx).
rabbit_api() { curl -sf -u "$RABBIT_CRED" "$RABBIT_API_URL/$1"; }

# Executa JavaScript no banco "notify" pelo mongosh do container.
mongo_eval() { MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval "$1" | tr -d '\r'; }

# POST /notifications com o JSON dado. Grava headers (sem \r) e corpo em target/smoke/ e imprime
# o status HTTP ("000" se a conexão falhar).
post_notificacao() {
  local status
  status=$(curl -s -D "$SMOKE_DIR/resposta.headers" -o "$SMOKE_DIR/resposta.json" -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' --data "$1" "$API_URL/notifications") || status="000"
  if [ -f "$SMOKE_DIR/resposta.headers" ]; then
    tr -d '\r' <"$SMOKE_DIR/resposta.headers" >"$SMOKE_DIR/resposta.headers.tmp"
    mv "$SMOKE_DIR/resposta.headers.tmp" "$SMOKE_DIR/resposta.headers"
  fi
  echo "$status"
}

# Valor de um header da última resposta de post_notificacao (nome sem diferenciar maiúsculas).
header_resposta() {
  grep -i "^$1:" "$SMOKE_DIR/resposta.headers" | tail -n 1 | sed 's/^[^:]*:[[:space:]]*//' || true
}

corpo_resposta() { cat "$SMOKE_DIR/resposta.json" 2>/dev/null || true; }

# GET /notifications/<id> (corpo vazio se falhar).
get_notificacao() { curl -s "$API_URL/notifications/$1" || true; }

# Faz polling no GET até o status pedido (timeout 15s). Deixa o último corpo em NOTIFICACAO.
esperar_status() {
  local id=$1 esperado=$2
  local limite=$((SECONDS + 15))
  NOTIFICACAO=""
  while ((SECONDS < limite)); do
    NOTIFICACAO=$(get_notificacao "$id")
    if grep -qF "\"status\":\"$esperado\"" <<<"$NOTIFICACAO"; then
      return 0
    fi
    sleep 0.5
  done
  falhou "notificação $id não chegou a $esperado em 15s. Último GET: $NOTIFICACAO"
}

# Faz polling em <url>/actuator/health até "status":"UP" (timeout 120s).
# Se o processo morrer antes, mostra o fim do log e falha na hora.
esperar_saude() {
  local url=$1 nome=$2 pid=${3:-} log=${4:-}
  local limite=$((SECONDS + 120))
  while ((SECONDS < limite)); do
    if curl -s --max-time 5 "$url/actuator/health" 2>/dev/null | grep -qF '"status":"UP"'; then
      ok "$nome no ar ($url/actuator/health UP)"
      return 0
    fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      [ -n "$log" ] && tail -n 40 "$log"
      falhou "$nome encerrou durante a subida (log: $log)"
    fi
    sleep 1
  done
  [ -n "$log" ] && tail -n 40 "$log"
  falhou "$nome não ficou UP em 120s (log: $log)"
}

# true se nada responde na porta (curl sai com 7 = conexão recusada). No Windows a recusa leva ~2s
# (o TCP repete o SYN), por isso o timeout folgado.
porta_livre() {
  local codigo=0
  curl -s -o /dev/null --max-time 10 "http://127.0.0.1:$1/" || codigo=$?
  [ "$codigo" -eq 7 ]
}

# Encerra um processo iniciado em segundo plano. No Git Bash o "kill" age sobre o processo MSYS;
# se o java (processo Windows) continuar vivo, usa taskkill com o pid de /proc/<pid>/winpid.
parar_processo() {
  local pid=$1 nome=$2
  [ -n "$pid" ] || return 0
  local winpid=""
  [ -r "/proc/$pid/winpid" ] && winpid=$(cat "/proc/$pid/winpid" 2>/dev/null || true)
  kill "$pid" 2>/dev/null || true
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  if [ -n "$winpid" ] && command -v taskkill >/dev/null 2>&1; then
    if tasklist //FI "PID eq $winpid" //NH 2>/dev/null | grep -qi java; then
      taskkill //F //T //PID "$winpid" >/dev/null 2>&1 || true
    fi
  elif kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  echo "-- $nome encerrado (pid $pid${winpid:+, winpid $winpid})"
}

# Recria o binding notify.exchange → notify.email (idempotente). Usado no passo 11 e no trap de saída,
# para que o ambiente nunca fique sem o binding se o script falhar no meio do cenário 503.
BINDING_REMOVIDO=false
restaurar_binding() {
  curl -sf -u "$RABBIT_CRED" -X POST -H 'Content-Type: application/json' \
    --data '{"routing_key":"notification.email","arguments":{}}' \
    "$RABBIT_API_URL/bindings/%2F/e/notify.exchange/q/notify.email" >/dev/null
}

# Quantidade de mensagens de uma fila segundo a API de gerenciamento (estatística atualizada a cada ~5s).
mensagens_na_fila() {
  rabbit_api "queues/%2F/$1?columns=messages" | grep -o '"messages":[0-9]*' | head -n 1 | cut -d: -f2
}

encerrar() {
  local codigo=$?
  set +e
  if $BINDING_REMOVIDO; then
    if restaurar_binding; then
      echo "-- binding notify.exchange → notify.email restaurado no trap de saída"
    else
      echo "AVISO: não consegui restaurar o binding notify.exchange → notify.email; reinicie a notify-api para redeclarar" >&2
    fi
  fi
  parar_processo "$API_PID" "notify-api"
  parar_processo "$WORKER_PID" "notify-worker"
  exit "$codigo"
}
trap encerrar EXIT

# ---------------------------------------------------------------------------------------------
# 1. Pré-condições
# ---------------------------------------------------------------------------------------------
mkdir -p "$SMOKE_DIR"
info "Smoke da Fase 1 ($($PERFIL_DEMO && echo "perfil demo, níveis ${NIVEIS[*]}" || echo "perfil padrão, níveis ${NIVEIS[*]}"))"

docker info >/dev/null 2>&1 || falhou "Docker não está rodando: inicie o Docker Desktop (ou rode 'docker desktop start')"
ok "Docker respondendo"
porta_livre 8080 || falhou "a porta 8080 está ocupada: encerre a notify-api (ou outro processo) antes do smoke"
porta_livre 8081 || falhou "a porta 8081 está ocupada: encerre o notify-worker (ou outro processo) antes do smoke"
ok "portas 8080 e 8081 livres"

# ---------------------------------------------------------------------------------------------
# 2. Infra
# ---------------------------------------------------------------------------------------------
info "docker compose up -d --wait"
docker compose up -d --wait || falhou "docker compose up -d --wait não deixou rabbitmq e mongo healthy"
ok "rabbitmq e mongo healthy"

# ---------------------------------------------------------------------------------------------
# 3. Build
# ---------------------------------------------------------------------------------------------
if $SEM_BUILD; then
  info "--sem-build: usando os jars já empacotados"
else
  info "./mvnw -q -DskipTests package"
  ./mvnw -q -DskipTests package || falhou "o empacotamento com ./mvnw falhou"
  ok "jars empacotados"
fi
WORKER_JAR="notify-worker/target/notify-worker-$VERSAO.jar"
API_JAR="notify-api/target/notify-api-$VERSAO.jar"
[ -f "$WORKER_JAR" ] || falhou "jar ausente: $WORKER_JAR (rode sem --sem-build)"
[ -f "$API_JAR" ] || falhou "jar ausente: $API_JAR (rode sem --sem-build)"

# ---------------------------------------------------------------------------------------------
# 4. Worker e API
# ---------------------------------------------------------------------------------------------
info "subindo notify-worker (log em target/smoke/worker.log)"
java -jar "$WORKER_JAR" ${PERFIL_ARGS[@]+"${PERFIL_ARGS[@]}"} >"$SMOKE_DIR/worker.log" 2>&1 &
WORKER_PID=$!
esperar_saude "$WORKER_URL" "notify-worker" "$WORKER_PID" "$SMOKE_DIR/worker.log"

info "subindo notify-api (log em target/smoke/api.log)"
java -jar "$API_JAR" ${PERFIL_ARGS[@]+"${PERFIL_ARGS[@]}"} >"$SMOKE_DIR/api.log" 2>&1 &
API_PID=$!
esperar_saude "$API_URL" "notify-api" "$API_PID" "$SMOKE_DIR/api.log"

# ---------------------------------------------------------------------------------------------
# 5. Topologia (MSG-01..04)
# ---------------------------------------------------------------------------------------------
exchanges=$(rabbit_api "exchanges/%2F?columns=name") || falhou "a API de gerenciamento do RabbitMQ não respondeu"
for ex in notify.exchange notify.retry notify.dlx; do
  grep -qF "\"name\":\"$ex\"" <<<"$exchanges" || falhou "exchange $ex ausente no broker"
done
ok "exchanges notify.exchange, notify.retry e notify.dlx declaradas"

filas=$(rabbit_api "queues/%2F?columns=name") || falhou "a API de gerenciamento do RabbitMQ não respondeu"
for canal in "${CANAIS[@]}"; do
  esperadas=("notify.$canal" "notify.$canal.dlq")
  for nivel in "${NIVEIS[@]}"; do
    esperadas+=("notify.$canal.retry.$nivel")
  done
  for fila in "${esperadas[@]}"; do
    grep -qF "\"name\":\"$fila\"" <<<"$filas" || falhou "fila $fila ausente no broker"
  done
done
ok "por canal (email, sms, webhook): fila principal, DLQ e retry.${NIVEIS[0]}/${NIVEIS[1]}/${NIVEIS[2]}"

# As estatísticas da API de gerenciamento atualizam a cada ~5s: espera até 30s pelos 10 consumidores.
limite=$((SECONDS + 30))
fila_email=""
while ((SECONDS < limite)); do
  fila_email=$(rabbit_api "queues/%2F/notify.email" || true)
  grep -qF '"consumers":10' <<<"$fila_email" && break
  sleep 1
done
grep -qF '"consumers":10' <<<"$fila_email" \
  || falhou "notify.email não mostrou 10 consumidores em 30s: $(grep -o '"consumers":[0-9]*' <<<"$fila_email" || echo sem dado)"
ok "notify.email com 10 consumidores"

# ---------------------------------------------------------------------------------------------
# 6. Índices e seed (FND-04, TPL-01)
# ---------------------------------------------------------------------------------------------
indices=$(mongo_eval "db.notifications.getIndexes().map(i => i.name).join(',')") \
  || falhou "mongosh não respondeu"
for indice in status_channel_createdAt channel_createdAt createdAt; do
  grep -qE "(^|,)$indice(,|$)" <<<"$indices" || falhou "índice $indice ausente em notifications (encontrados: $indices)"
done
ok "índices de notifications: $indices"

templates=$(mongo_eval "db.templates.countDocuments()") || falhou "mongosh não respondeu"
[ "$templates" = "3" ] || falhou "templates tem $templates documentos (esperado 3)"
ok "3 templates no seed"

# ---------------------------------------------------------------------------------------------
# 7. Caminho feliz (API-01, API-04, WRK-02, WRK-04, DLV-01)
# ---------------------------------------------------------------------------------------------

# POST válido → 202 + Location + PENDING → SENT; o GET precisa conter cada texto esperado.
# Deixa o id em ULTIMO_ID. Chame direto, nunca dentro de $(...).
caminho_feliz() {
  local canal=$1 json=$2
  shift 2
  local status location id esperado
  status=$(post_notificacao "$json")
  [ "$status" = "202" ] || falhou "POST $canal respondeu $status (esperado 202): $(corpo_resposta)"
  location=$(header_resposta Location)
  [[ "$location" == /notifications/* ]] || falhou "POST $canal sem Location /notifications/<id> (veio '$location')"
  grep -qF '"status":"PENDING"' "$SMOKE_DIR/resposta.json" \
    || falhou "corpo do 202 de $canal sem \"status\":\"PENDING\": $(corpo_resposta)"
  id=${location#/notifications/}
  ok "POST $canal → 202, Location $location, PENDING"

  esperar_status "$id" SENT
  for esperado in "$@" '"number":1' '"durationMs"' '"thread":"VirtualThread'; do
    grep -qF -- "$esperado" <<<"$NOTIFICACAO" || falhou "GET de $canal ($id) não contém $esperado: $NOTIFICACAO"
  done
  ok "$canal $id chegou a SENT, renderizado, tentativa 1 em VirtualThread"
  ULTIMO_ID=$id
}

caminho_feliz EMAIL \
  '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}' \
  'Boas-vindas ao Notify Hub, Ana!'
EMAIL_ID=$ULTIMO_ID

if ! $PERFIL_DEMO; then
  caminho_feliz SMS \
    '{"channel":"SMS","recipient":"+5511999998888","templateCode":"order-shipped","variables":{"name":"Ana","orderId":"PED-123"}}' \
    'PED-123'
  SMS_ID=$ULTIMO_ID

  caminho_feliz WEBHOOK \
    '{"channel":"WEBHOOK","recipient":"https://example.test/hooks/notify","templateCode":"password-reset","variables":{"name":"Ana","resetCode":"789012"}}' \
    'password.reset' '789012'
  WEBHOOK_ID=$ULTIMO_ID
  # Envelope de evento (D-09): o worker injeta o próprio id em notificationId.
  grep -qF "\\\"notificationId\\\":\\\"$WEBHOOK_ID\\\"" <<<"$NOTIFICACAO" \
    || falhou "envelope WEBHOOK sem notificationId = $WEBHOOK_ID: $NOTIFICACAO"
  grep -qF '\"event\":\"password.reset\"' <<<"$NOTIFICACAO" || falhou "envelope WEBHOOK sem event: $NOTIFICACAO"
  grep -qF '\"data\":{' <<<"$NOTIFICACAO" || falhou "envelope WEBHOOK sem data: $NOTIFICACAO"
  ok "WEBHOOK com envelope event/notificationId/data"
fi

# ---------------------------------------------------------------------------------------------
# 8. Logs (WRK-05, MSG-01)
# ---------------------------------------------------------------------------------------------
grep -qF 'VirtualThread' "$SMOKE_DIR/worker.log" || falhou "worker.log não registra processamento em VirtualThread"
if grep -qF 'Consumer failed to start' "$SMOKE_DIR/worker.log"; then
  falhou "worker.log contém 'Consumer failed to start'"
fi
for log in worker api; do
  if grep -qF 'PRECONDITION_FAILED' "$SMOKE_DIR/$log.log"; then
    falhou "$log.log contém PRECONDITION_FAILED (argumentos de fila divergentes)"
  fi
done
ok "worker em VirtualThread, sem 'Consumer failed to start' e sem PRECONDITION_FAILED nos logs"

if $PERFIL_DEMO; then
  # 14. Profile demo (MSG-05): topologia 1s/5s/25s (passo 5) e caminho feliz EMAIL (passo 7) já conferidos.
  ok "profile demo: filas retry.1s/5s/25s nos três canais e EMAIL até SENT, sem mudança de código"
  info "notificação criada: EMAIL $EMAIL_ID"
else

# ---------------------------------------------------------------------------------------------
# 10. 400 sem persistir (API-06)
# ---------------------------------------------------------------------------------------------
contagem_antes=$(mongo_eval "db.notifications.countDocuments()") || falhou "mongosh não respondeu"

# POST inválido → 400 application/problem+json com o campo esperado em errors[].
espera_400() {
  local caso=$1 json=$2 campo=$3
  local status tipo
  status=$(post_notificacao "$json")
  [ "$status" = "400" ] || falhou "$caso respondeu $status (esperado 400): $(corpo_resposta)"
  tipo=$(header_resposta Content-Type)
  [[ "$tipo" == *application/problem+json* ]] || falhou "$caso com Content-Type '$tipo' (esperado application/problem+json)"
  grep -qF "\"field\":\"$campo\"" "$SMOKE_DIR/resposta.json" \
    || falhou "$caso sem \"field\":\"$campo\" em errors[]: $(corpo_resposta)"
  ok "$caso → 400 problem+json, errors[].field = $campo"
}

espera_400 "canal desconhecido (PUSH)" \
  '{"channel":"PUSH","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}' channel
espera_400 "destinatário ausente" \
  '{"channel":"EMAIL","templateCode":"welcome","variables":{"name":"Ana"}}' recipient
espera_400 "SMS fora do E.164" \
  '{"channel":"SMS","recipient":"123","templateCode":"order-shipped","variables":{"name":"Ana","orderId":"PED-123"}}' recipient
espera_400 "chave de variável com ponto" \
  '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"a.b":"x"}}' variables

contagem_depois=$(mongo_eval "db.notifications.countDocuments()") || falhou "mongosh não respondeu"
[ "$contagem_antes" = "$contagem_depois" ] \
  || falhou "os 400 gravaram documentos: notifications tinha $contagem_antes e agora tem $contagem_depois"
ok "nenhum 400 gravou no Mongo (notifications continua com $contagem_depois documentos)"

# ---------------------------------------------------------------------------------------------
# 11. 503 PUBLISH_FAILED (API-02)
# ---------------------------------------------------------------------------------------------
binding=$(rabbit_api "bindings/%2F/e/notify.exchange/q/notify.email") || falhou "não consegui ler o binding notify.exchange → notify.email"
chave=$(grep -o '"properties_key":"[^"]*"' <<<"$binding" | head -n 1 | cut -d'"' -f4 || true)
[ -n "$chave" ] || falhou "binding notify.exchange → notify.email não existe: $binding"
BINDING_REMOVIDO=true
curl -sf -u "$RABBIT_CRED" -X DELETE "$RABBIT_API_URL/bindings/%2F/e/notify.exchange/q/notify.email/$chave" >/dev/null \
  || falhou "não consegui remover o binding notify.exchange → notify.email ($chave)"
ok "binding notify.exchange → notify.email ($chave) removido"

status=$(post_notificacao '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}')
[ "$status" = "503" ] || falhou "POST EMAIL sem binding respondeu $status (esperado 503): $(corpo_resposta)"
retry_after=$(header_resposta Retry-After)
[ "$retry_after" = "5" ] || falhou "503 com Retry-After '$retry_after' (esperado 5)"
grep -qF 'publish-failed' "$SMOKE_DIR/resposta.json" || falhou "503 sem Problem Detail publish-failed: $(corpo_resposta)"
falho_id=$(grep -o '"notificationId":"[^"]*"' "$SMOKE_DIR/resposta.json" | head -n 1 | cut -d'"' -f4 || true)
[ -n "$falho_id" ] || falhou "503 sem notificationId no corpo: $(corpo_resposta)"
ok "POST EMAIL sem rota → 503, Retry-After: 5, publish-failed ($falho_id)"
esperar_status "$falho_id" PUBLISH_FAILED
ok "GET $falho_id mostra PUBLISH_FAILED"

restaurar_binding || falhou "não consegui restaurar o binding notify.exchange → notify.email"
BINDING_REMOVIDO=false
rabbit_api "bindings/%2F/e/notify.exchange/q/notify.email" | grep -qF '"routing_key":"notification.email"' \
  || falhou "binding notify.exchange → notify.email não voltou"
ok "binding notify.exchange → notify.email restaurado"

caminho_feliz EMAIL \
  '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"welcome","variables":{"name":"Ana"}}' \
  'Boas-vindas ao Notify Hub, Ana!'
ok "com o binding de volta, o POST EMAIL chega a SENT de novo"

# ---------------------------------------------------------------------------------------------
# 12. Duplicata (WRK-02)
# ---------------------------------------------------------------------------------------------
agora=$(date -u +%Y-%m-%dT%H:%M:%SZ)
publicacao=$(curl -sf -u "$RABBIT_CRED" -X POST -H 'Content-Type: application/json' \
  --data "{\"properties\":{\"content_type\":\"application/json\",\"headers\":{\"notify-retry-count\":0}},\"routing_key\":\"notification.email\",\"payload\":\"{\\\"notificationId\\\":\\\"$EMAIL_ID\\\",\\\"channel\\\":\\\"EMAIL\\\",\\\"templateCode\\\":\\\"welcome\\\",\\\"createdAt\\\":\\\"$agora\\\"}\",\"payload_encoding\":\"string\"}" \
  "$RABBIT_API_URL/exchanges/%2F/notify.exchange/publish") || falhou "a publicação pela API de gerenciamento falhou"
grep -qF '"routed":true' <<<"$publicacao" || falhou "mensagem duplicada não foi roteada: $publicacao"
ok "mensagem duplicada de $EMAIL_ID publicada em notify.exchange (routed)"

limite=$((SECONDS + 10))
until grep -qF "duplicada/obsoleta descartada id=$EMAIL_ID" "$SMOKE_DIR/worker.log"; do
  ((SECONDS < limite)) || falhou "worker.log não registrou 'duplicada/obsoleta' para $EMAIL_ID em 10s"
  sleep 0.5
done
view=$(get_notificacao "$EMAIL_ID")
tentativas=$(grep -o '"number":' <<<"$view" | wc -l | tr -d ' ' || true)
[ "$tentativas" = "1" ] || falhou "$EMAIL_ID tem $tentativas tentativas depois da duplicata (esperado 1): $view"
grep -qF '"status":"SENT"' <<<"$view" || falhou "$EMAIL_ID saiu de SENT depois da duplicata: $view"
ok "duplicata descartada (log 'duplicada/obsoleta'), $EMAIL_ID continua SENT com 1 tentativa"

# ---------------------------------------------------------------------------------------------
# 13. DLQ sem loop (MSG-04, critério 4)
# ---------------------------------------------------------------------------------------------
dlq_antes=$(mensagens_na_fila notify.email.dlq) || falhou "não consegui ler notify.email.dlq"
status=$(post_notificacao '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"nao-existe","variables":{"name":"Ana"}}')
[ "$status" = "202" ] || falhou "POST com template inexistente respondeu $status (esperado 202): $(corpo_resposta)"
dlq_location=$(header_resposta Location)
dlq_id=${dlq_location#/notifications/}
ok "POST EMAIL com templateCode nao-existe → 202 ($dlq_id)"

dlq_esperado=$((dlq_antes + 1))
dlq_agora=$dlq_antes
limite=$((SECONDS + 15))
while ((SECONDS < limite)); do
  dlq_agora=$(mensagens_na_fila notify.email.dlq || echo "$dlq_antes")
  [ "$dlq_agora" = "$dlq_esperado" ] && break
  sleep 1
done
[ "$dlq_agora" = "$dlq_esperado" ] \
  || falhou "notify.email.dlq tem $dlq_agora mensagens depois de 15s (esperado $dlq_esperado)"
sleep 3
dlq_final=$(mensagens_na_fila notify.email.dlq) || falhou "não consegui ler notify.email.dlq"
principal=$(mensagens_na_fila notify.email) || falhou "não consegui ler notify.email"
[ "$dlq_final" = "$dlq_esperado" ] || falhou "notify.email.dlq mudou de $dlq_esperado para $dlq_final: rejeição em loop?"
[ "$principal" = "0" ] || falhou "notify.email tem $principal mensagens (esperado 0): rejeição em loop?"
ok "rejeição sem loop: notify.email.dlq com exatamente uma mensagem a mais ($dlq_final) e notify.email vazia"
status_dlq=$(get_notificacao "$dlq_id" | grep -o '"status":"[A-Z_]*"' | head -n 1 | cut -d'"' -f4 || true)
info "status atual de $dlq_id: ${status_dlq:-desconhecido} (PROCESSING é a lacuna conhecida, fechada na Fase 2)"

info "notificações criadas: EMAIL $EMAIL_ID, SMS $SMS_ID, WEBHOOK $WEBHOOK_ID, PUBLISH_FAILED $falho_id, DLQ $dlq_id"
fi

# Encerra as apps antes da linha final, para que "SMOKE FASE 1: OK" seja a última linha da saída.
# A infra continua no ar para inspeção.
parar_processo "$API_PID" "notify-api"
API_PID=""
parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
echo "SMOKE FASE 1: OK"
