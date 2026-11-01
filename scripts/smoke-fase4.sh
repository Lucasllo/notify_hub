#!/usr/bin/env bash
# Smoke da Fase 4 (planos 04-01 e 04-03): o stack inteiro sobe pelo docker compose, sem JDK no host,
# o Grafana abre no dashboard e a carga do k6 aparece no Prometheus.
#
# Etapas e o que cada uma prova:
#   1. docker info: o Docker Desktop está respondendo;
#   2. docker compose up -d --build --wait: API e worker são construídos dentro do estágio Maven do
#      Dockerfile e rabbitmq, mongo, api, worker e prometheus ficam healthy (DEM-01);
#   3. o Prometheus tem os quatro alvos no ar: api, worker, rabbitmq e rabbitmq-queues (OBS-02, D-05);
#   4. a profundidade por fila existe: rabbitmq_detailed_queue_messages com queue="notify.webhook.dlq";
#   5. uma notificação criada pela API em container chega a SENT pelo worker em container
#      (API → RabbitMQ → worker → Mongo, profile demo);
#   6. o scrape direto da API traz o histograma http_server_requests_seconds_bucket do
#      POST /notifications (base do p95 do D-02);
#   7. o Prometheus já coletou esse POST (http_server_requests_seconds_count da API >= 1);
#   8. API e worker rodam como usuário não root (id -u diferente de 0);
#   9. o Grafana abre sem login direto no dashboard Notify Hub, só para leitura (OBS-03, D-06);
#  10. a API expõe as 7 séries de notify_publish_outcomes_total no Prometheus (04-02);
#  11. o k6 dispara 1000 notificações e sai verde, com novas=1000 e nenhum não terminal no resumo
#      (DEM-02; log em target/smoke/k6.log);
#  12. o Prometheus mostra o que o dashboard desenha durante a carga: recusas circuit_open e retries
#      nos últimos 5 minutos, mensagens nas DLQs e >= 1000 publishes confirmados.
#
# Uso (a partir de qualquer diretório; o script faz cd para a raiz do repositório):
#   bash scripts/smoke-fase4.sh [--sem-build] [--sem-k6]
#
#   --sem-build    sobe o compose sem --build (usa as imagens já construídas)
#   --sem-k6       pula as etapas 11 e 12 (a rodada do k6 leva cerca de 1 minuto)
#
# Pré-requisitos: Docker Desktop ligado e curl. Não precisa de JDK nem de Maven no host, nem de jq.
# Nada além dos containers do compose pode ouvir em 8080/8081 (os smokes das Fases 1 e 2 usam essas
# portas com os jars locais e param os containers api/worker antes de subir).
#
# O script NÃO roda "docker compose down": o stack fica no ar para inspeção
# (Grafana em http://localhost:3000, Prometheus em http://localhost:9090, painel do RabbitMQ em
# http://localhost:15672, notify/notify).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/smoke-lib.sh
source scripts/smoke-lib.sh

uso() {
  # Imprime o bloco de comentários do topo do arquivo.
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

SEM_BUILD=false
SEM_K6=false
for arg in "$@"; do
  case "$arg" in
    --sem-build) SEM_BUILD=true ;;
    --sem-k6) SEM_K6=true ;;
    -h|--help) uso; exit 0 ;;
    *) echo "Opção desconhecida: $arg" >&2; uso >&2; exit 2 ;;
  esac
done

PROM_URL="http://127.0.0.1:9090"
GRAFANA_URL="http://127.0.0.1:3000"

# Valor (string) da primeira amostra de uma query instantânea do Prometheus; vazio sem resultado.
# MSYS_NO_PATHCONV=1: no Git Bash, um argumento como query=...uri="/notifications" teria o caminho
# convertido para C:/Program Files/Git/notifications e o seletor não casaria com nada.
prom_valor() {
  { MSYS_NO_PATHCONV=1 curl -s --data-urlencode "query=$1" "$PROM_URL/api/v1/query" || true; } | tr -d '\r\n' \
    | { grep -o '"value":\[[^]]*\]' || true; } | head -n 1 \
    | sed 's/^"value":\[[^,]*,"\([^"]*\)"\]$/\1/'
}

# Polling a cada 2s até a query devolver exatamente <esperado>. Chame direto, nunca dentro de $(...).
esperar_prom() {
  local query=$1 esperado=$2 timeout=$3
  local limite=$((SECONDS + timeout)) valor=""
  while ((SECONDS < limite)); do
    valor=$(prom_valor "$query")
    if [ "$valor" = "$esperado" ]; then
      ok "Prometheus: $query = $esperado"
      return 0
    fi
    sleep 2
  done
  falhou "Prometheus: $query não chegou a $esperado em ${timeout}s (último valor: '${valor}')"
}

# Polling a cada 2s até a query devolver um número >= <minimo>. Chame direto, nunca dentro de $(...).
prom_maior_igual() {
  local query=$1 minimo=$2 timeout=$3
  local limite=$((SECONDS + timeout)) valor=""
  while ((SECONDS < limite)); do
    valor=$(prom_valor "$query")
    if [ -n "$valor" ] && awk -v v="$valor" -v m="$minimo" 'BEGIN { exit !(v + 0 >= m + 0) }'; then
      ok "Prometheus: $query = $valor (>= $minimo)"
      return 0
    fi
    sleep 2
  done
  falhou "Prometheus: $query não chegou a >= $minimo em ${timeout}s (último valor: '${valor}')"
}

mkdir -p "$SMOKE_DIR"
info "Smoke da Fase 4 (stack inteiro no docker compose, perfil demo)"

# 1. Docker
docker info >/dev/null 2>&1 || falhou "Docker não está rodando: inicie o Docker Desktop"
ok "Docker respondendo"

# 2. Stack inteiro
if $SEM_BUILD; then
  info "docker compose up -d --wait (--sem-build: imagens já construídas)"
  docker compose up -d --wait \
    || falhou "docker compose up -d --wait falhou. Uma app local (smoke 1/2) pode estar ocupando 8080/8081"
else
  info "docker compose up -d --build --wait (o primeiro build baixa as dependências Maven: alguns minutos)"
  docker compose up -d --build --wait \
    || falhou "docker compose up -d --build --wait falhou. Uma app local (smoke 1/2) pode estar ocupando 8080/8081"
fi
ok "rabbitmq, mongo, api, worker, prometheus e grafana no ar"

# 3. Quatro alvos de scrape no ar
esperar_prom 'sum(up{job=~"api|worker|rabbitmq|rabbitmq-queues"})' 4 90

# 4. Profundidade por fila (rótulo queue do endpoint detalhado)
esperar_prom 'count(rabbitmq_detailed_queue_messages{queue="notify.webhook.dlq"})' 1 30

# 5. POST → SENT entre containers (destinatário de domínio reservado, nunca dado real)
status=$(post_notificacao '{"channel":"EMAIL","recipient":"smoke-fase4@example.com","templateCode":"welcome","variables":{"name":"Smoke"}}')
[ "$status" = "202" ] || falhou "POST /notifications respondeu $status (esperado 202): $(corpo_resposta)"
id=$(id_da_resposta)
[ -n "$id" ] || falhou "POST /notifications sem id na resposta: $(corpo_resposta)"
ok "POST /notifications aceito (202, id $id)"
esperar_status "$id" SENT 30
ok "notificação $id chegou a SENT pelo worker em container"

# 6. Histograma HTTP no scrape direto da API
scrape=$({ curl -s "$API_URL/actuator/prometheus" || true; } | tr -d '\r')
grep -qE '^http_server_requests_seconds_bucket.*uri="/notifications"' <<<"$scrape" \
  || falhou "/actuator/prometheus da API sem http_server_requests_seconds_bucket com uri=\"/notifications\""
ok "API expõe http_server_requests_seconds_bucket do POST /notifications"

# 7. O Prometheus coletou o POST
prom_maior_igual 'sum(http_server_requests_seconds_count{job="api",method="POST",uri="/notifications"})' 1 30

# 8. Containers sem root
for servico in api worker; do
  uid=$(docker compose exec -T "$servico" id -u | tr -d '\r')
  [ -n "$uid" ] && [ "$uid" != "0" ] || falhou "o container $servico roda como root (id -u = '$uid')"
  ok "$servico roda como usuário não root (uid $uid)"
done

# 9. Grafana: a home anônima é o dashboard Notify Hub, sem permissão de edição
limite=$((SECONDS + 60))
home=""
while ((SECONDS < limite)); do
  home=$({ curl -s "$GRAFANA_URL/api/dashboards/home" || true; } | tr -d '\r\n')
  case "$home" in *'"title":"Notify Hub"'*) break ;; esac
  sleep 2
done
case "$home" in
  *'"title":"Notify Hub"'*) ;;
  *) falhou "a home anônima do Grafana não virou o dashboard Notify Hub em 60s" ;;
esac
case "$home" in
  *'"canEdit":false'*) ;;
  *) falhou "a home anônima do Grafana permite edição (esperado \"canEdit\":false)" ;;
esac
ok "Grafana abre sem login no dashboard Notify Hub, somente leitura"

# 10. Contador do resultado do publish (04-02): 7 séries pré-registradas
esperar_prom 'count(notify_publish_outcomes_total)' 7 30

if $SEM_K6; then
  info "--sem-k6: rodada do k6 e reflexo da carga no Prometheus puladas"
else
  # 11. Rodada do k6: 1000 notificações com destinatários mágicos e resumo pelo /notifications/stats.
  # O caminho do script está no command do serviço k6, então nada aqui passa pela conversão do Git Bash
  info "docker compose --profile bench run --rm k6 (1000 notificações; cerca de 1 minuto)"
  if ! docker compose --profile bench run --rm k6 >"$SMOKE_DIR/k6.log" 2>&1; then
    tail -n 40 "$SMOKE_DIR/k6.log"
    falhou "o k6 saiu com erro (log completo em target/smoke/k6.log)"
  fi
  grep -q "novas=1000" "$SMOKE_DIR/k6.log" || falhou "o resumo do k6 não tem novas=1000 (target/smoke/k6.log)"
  grep -q "PENDING=0 PROCESSING=0 RETRYING=0" "$SMOKE_DIR/k6.log" \
    || falhou "o resumo do k6 ainda tem não terminais (target/smoke/k6.log)"
  terminais=$({ grep -o 'terminais da rodada: [^"]*' "$SMOKE_DIR/k6.log" || true; } | head -n 1)
  ok "k6 verde: 1000 notificações novas, todas em status terminal (${terminais})"

  # 12. O dashboard reflete a carga: as séries que os painéis leem se mexeram
  prom_maior_igual 'sum(increase(notify_delivery_rejections_total{reason="circuit_open"}[5m]))' 1 40
  prom_maior_igual 'sum(increase(notify_delivery_retries_total[5m]))' 1 40
  prom_maior_igual 'sum(rabbitmq_detailed_queue_messages{queue=~"notify[.].+[.]dlq"})' 1 40
  prom_maior_igual 'sum(notify_publish_outcomes_total{outcome="confirmed"})' 1000 40
fi

echo "SMOKE FASE 4: OK"
