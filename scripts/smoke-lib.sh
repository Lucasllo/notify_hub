#!/usr/bin/env bash
# Helpers compartilhados pelos smokes (plano 02-07).
#
# Uso: "source scripts/smoke-lib.sh" a partir de um script que já fez "set -euo pipefail".
# Este arquivo só define variáveis e funções: não liga "set -e" por conta própria, não faz cd e não
# instala trap. Os helpers de smoke-fase1.sh foram copiados para cá sem mudar o comportamento
# (o smoke da Fase 1 continua autocontido e não usa este arquivo).
#
# Pré-requisitos: Docker Desktop ligado, JDK 21 no PATH, curl. Não depende de jq: os campos JSON são
# extraídos com grep/sed/tr.
#
# Jars: desde a Fase 3 (plano 03-01), o executável de cada app sai com classifier exec
# (notify-<app>-<versão>-exec.jar). O jar sem classifier é o principal, com as classes na raiz,
# consumido pelo módulo notify-it como dependência; ele não sobe com "java -jar".

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE_DIR="$RAIZ/target/smoke"
VERSAO="0.1.0-SNAPSHOT"
API_URL="http://localhost:8080"
WORKER_URL="http://localhost:8081"
RABBIT_API_URL="http://localhost:15672/api"
# Credenciais da demo, só locais (as mesmas do docker-compose.yml)
RABBIT_CRED="notify:notify"
WORKER_JAR="$RAIZ/notify-worker/target/notify-worker-$VERSAO-exec.jar"
API_JAR="$RAIZ/notify-api/target/notify-api-$VERSAO-exec.jar"

WORKER_PID=""
API_PID=""
NOTIFICACAO=""

# ---------------------------------------------------------------------------------------------
# Saída
# ---------------------------------------------------------------------------------------------

ok() { echo "OK: $*"; }

# Sai com 1. Nunca chame dentro de $(...): só encerraria o subshell.
falhou() { echo "FALHOU: $*"; exit 1; }

info() { echo "-- $*"; }

# Quantas vezes o texto literal <padrão> aparece em <texto> (0 se nenhuma).
contar() {
  { grep -oF -- "$1" <<<"$2" || true; } | wc -l | tr -d ' '
}

# ---------------------------------------------------------------------------------------------
# RabbitMQ e MongoDB
# ---------------------------------------------------------------------------------------------

# GET na API de gerenciamento do RabbitMQ (falha se o status HTTP não for 2xx).
rabbit_api() { curl -sf -u "$RABBIT_CRED" "$RABBIT_API_URL/$1"; }

# Executa JavaScript no banco "notify" pelo mongosh do container.
mongo_eval() { MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval "$1" | tr -d '\r'; }

# Quantidade de mensagens de uma fila segundo a API de gerenciamento (estatística atualizada a cada ~5s).
mensagens_na_fila() {
  rabbit_api "queues/%2F/$1?columns=messages" | grep -o '"messages":[0-9]*' | head -n 1 | cut -d: -f2
}

# true se a DLQ <fila> tem a mensagem do id <id> com o header x-failure-class = <classe>.
# Lê com ackmode ack_requeue_true: as mensagens voltam para a DLQ (o reprocessamento da Fase 3 usa).
# Como o script não apaga volumes, a DLQ acumula rodadas anteriores: pede todas as mensagens da fila
# (no mínimo 200) para o id novo não ficar de fora.
dlq_tem_header() {
  local fila=$1 id=$2 classe=$3
  local total quantidade resposta
  total=$(mensagens_na_fila "$fila" 2>/dev/null || echo 0)
  quantidade=$(( ${total:-0} + 100 ))
  [ "$quantidade" -ge 200 ] || quantidade=200
  resposta=$(curl -sf -u "$RABBIT_CRED" -X POST -H 'Content-Type: application/json' \
    --data "{\"count\":$quantidade,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\"}" \
    "$RABBIT_API_URL/queues/%2F/$fila/get") || return 1
  # Uma mensagem por linha: cada objeto da resposta começa em "payload_bytes".
  tr -d '\r\n' <<<"$resposta" | sed 's/"payload_bytes"/\n&/g' | grep -F -- "$id" \
    | grep -qF -- "\"x-failure-class\":\"$classe\""
}

# ---------------------------------------------------------------------------------------------
# HTTP da API
# ---------------------------------------------------------------------------------------------

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

# Id da notificação criada no último POST. O corpo do 202 é {"id":...,"status":...,"createdAt":...}
# (CreateNotificationResponse); o 503 traz "notificationId". Cai para o header Location se preciso.
id_da_resposta() {
  local id
  id=$({ grep -o '"id":"[^"]*"' "$SMOKE_DIR/resposta.json" 2>/dev/null || true; } | head -n 1 | cut -d'"' -f4)
  if [ -z "$id" ]; then
    id=$({ grep -o '"notificationId":"[^"]*"' "$SMOKE_DIR/resposta.json" 2>/dev/null || true; } \
      | head -n 1 | cut -d'"' -f4)
  fi
  if [ -z "$id" ]; then
    local location
    location=$(header_resposta Location)
    [[ "$location" == /notifications/* ]] && id=${location#/notifications/}
  fi
  echo "$id"
}

# GET /notifications/<id> (corpo vazio se falhar).
get_notificacao() { curl -s "$API_URL/notifications/$1" || true; }

# Faz polling no GET até o status pedido (timeout em segundos, padrão 15). Deixa o último corpo em
# NOTIFICACAO. Chame direto, nunca dentro de $(...).
esperar_status() {
  local id=$1 esperado=$2 timeout=${3:-15}
  local limite=$((SECONDS + timeout))
  NOTIFICACAO=""
  while ((SECONDS < limite)); do
    NOTIFICACAO=$(get_notificacao "$id")
    if grep -qF "\"status\":\"$esperado\"" <<<"$NOTIFICACAO"; then
      return 0
    fi
    sleep 0.5
  done
  falhou "notificação $id não chegou a $esperado em ${timeout}s. Último GET: $NOTIFICACAO"
}

# ---------------------------------------------------------------------------------------------
# Métricas do worker
# ---------------------------------------------------------------------------------------------

# Linhas do scrape do worker que casam com a regex estendida (vazio se nenhuma).
metrica() {
  { curl -s "$WORKER_URL/actuator/prometheus" || true; } | tr -d '\r' | { grep -E -- "$1" || true; }
}

# Valor (último campo) da linha do scrape que começa exatamente com <linha-prefixo>, ex.:
#   valor_metrica 'notify_delivery_rejections_total{channel="sms",reason="bulkhead_full"}'
# Imprime 0 se a linha não existir.
valor_metrica() {
  local valor
  valor=$({ curl -s "$WORKER_URL/actuator/prometheus" || true; } | tr -d '\r' \
    | awk -v p="$1" 'index($0, p " ") == 1 { v = $NF } END { print v }')
  echo "${valor:-0}"
}

# true se o número (inteiro ou decimal, ex.: 3.0) é maior que zero.
maior_que_zero() { awk -v v="$1" 'BEGIN { exit !(v + 0 > 0) }'; }

# ---------------------------------------------------------------------------------------------
# Processos
# ---------------------------------------------------------------------------------------------

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

# Espera a porta liberar (até 30s), por exemplo depois de encerrar o processo anterior.
esperar_porta_livre() {
  local porta=$1 nome=$2
  local limite=$((SECONDS + 30))
  until porta_livre "$porta"; do
    ((SECONDS < limite)) || falhou "a porta $porta continua ocupada: encerre o $nome (ou outro processo) antes do smoke"
    sleep 1
  done
}

# pid Windows de um processo iniciado em segundo plano pelo Git Bash (vazio fora do Windows).
winpid_de() {
  local pid=$1
  [ -r "/proc/$pid/winpid" ] && cat "/proc/$pid/winpid" 2>/dev/null || true
}

# Encerra um processo iniciado em segundo plano. No Git Bash o "kill" age sobre o processo MSYS;
# se o java (processo Windows) continuar vivo, usa taskkill com o pid de /proc/<pid>/winpid.
parar_processo() {
  local pid=$1 nome=$2
  [ -n "$pid" ] || return 0
  local winpid
  winpid=$(winpid_de "$pid")
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

# Encerra o processo à força, sem shutdown gracioso: no Windows, taskkill //F no pid Windows do java;
# fora dele, kill -9. O shutdown gracioso do Spring AMQP terminaria a mensagem em voo e confirmaria,
# o que invalidaria o teste de reentrega (WRK-03). Só age sobre um pid que o próprio script iniciou.
matar_abruptamente() {
  local pid=$1 nome=$2
  [ -n "$pid" ] || falhou "matar_abruptamente sem pid para $nome"
  local winpid
  winpid=$(winpid_de "$pid")
  if [ -n "$winpid" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //F //T //PID "$winpid" >/dev/null 2>&1 || falhou "taskkill //F não encerrou $nome (winpid $winpid)"
  else
    kill -9 "$pid" 2>/dev/null || falhou "kill -9 não encerrou $nome (pid $pid)"
  fi
  local i
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  echo "-- $nome derrubado à força (pid $pid${winpid:+, winpid $winpid})"
}

# Sobe o worker no profile demo em segundo plano (args extras vão para o Spring), guarda WORKER_PID e
# espera o health UP e a linha "Listeners iniciados" (os listeners sobem depois do runner de índices e
# seed). O processo herda as variáveis de ambiente exportadas pelo chamador.
iniciar_worker() {
  local log=$1
  shift
  [ -f "$WORKER_JAR" ] || falhou "jar ausente: $WORKER_JAR (rode sem --sem-build)"
  esperar_porta_livre 8081 notify-worker
  java -jar "$WORKER_JAR" --spring.profiles.active=demo "$@" >"$log" 2>&1 &
  WORKER_PID=$!
  esperar_saude "$WORKER_URL" "notify-worker" "$WORKER_PID" "$log"
  local limite=$((SECONDS + 60))
  until grep -qF 'Listeners iniciados' "$log"; do
    if ((SECONDS >= limite)); then
      tail -n 40 "$log"
      falhou "notify-worker não registrou 'Listeners iniciados' em 60s (log: $log)"
    fi
    sleep 0.5
  done
}

# Sobe a API no profile demo em segundo plano, guarda API_PID e espera o health UP.
iniciar_api() {
  local log=$1
  shift
  [ -f "$API_JAR" ] || falhou "jar ausente: $API_JAR (rode sem --sem-build)"
  esperar_porta_livre 8080 notify-api
  java -jar "$API_JAR" --spring.profiles.active=demo "$@" >"$log" 2>&1 &
  API_PID=$!
  esperar_saude "$API_URL" "notify-api" "$API_PID" "$log"
}
