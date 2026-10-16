#!/usr/bin/env bash
# Smoke ponta a ponta da Fase 2 (plano 02-07).
#
# Sobe a infra (RabbitMQ 4.3 + MongoDB 8.0) com o docker compose, inicia o worker e a API no profile
# demo (escada de retry 1s/5s/25s) e confere os critérios de sucesso da Fase 2 contra o broker e o
# banco de verdade:
#   - critério 1: always-transient desce a escada visível (RETRYING, fila retry.25s) até DEAD_LETTERED
#     com 4 tentativas e cópia na DLQ com x-failure-class: TRANSIENT;
#   - critério 2: falhas permanentes (always-permanent, template inexistente, variável obrigatória
#     faltando) vão direto para FAILED com 1 tentativa e cópia na DLQ com x-failure-class: PERMANENT;
#   - critério 5: webhook com aspas e barra invertida nas variáveis chega a SENT (JSON válido);
#   - critério 3: fail-2-then-ok chega a SENT na 3ª tentativa (nível 2), e um worker derrubado à força
#     antes do ack retoma a reentrega sem envio duplicado (WRK-03, D-12);
#   - critério 4: rajada de always-transient abre o circuit breaker do webhook e o bulkhead do SMS
#     recusa o excesso, sem nenhuma notificação FAILED; /actuator/prometheus expõe as séries do D-15
#     e do Resilience4j; os logs do worker não têm erro de topologia nem aviso do Mustache do MVC.
#
# Uso (a partir de qualquer diretório; o script faz cd para a raiz do repositório):
#   bash scripts/smoke-fase2.sh [--sem-build]
#
#   --sem-build    não roda o ./mvnw package (usa os jars que já estão em */target)
#
# Pré-requisitos: Docker Desktop ligado, JDK 21 no PATH, curl. Nada pode estar ouvindo em 8080 nem 8081.
# Logs das apps e respostas HTTP ficam em target/smoke/ (fase2-*.log).
#
# O script NÃO roda "docker compose down": a infra fica no ar para inspeção no painel
# (http://localhost:15672, notify/notify). Os volumes também não são apagados: filas e mensagens de
# rodadas anteriores ficam no broker, e o script segue só os ids que ele mesmo criou.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=scripts/smoke-lib.sh
source scripts/smoke-lib.sh

uso() {
  # Imprime o bloco de comentários do topo do arquivo.
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

SEM_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --sem-build) SEM_BUILD=true ;;
    -h|--help) uso; exit 0 ;;
    *) echo "Opção desconhecida: $arg" >&2; uso >&2; exit 2 ;;
  esac
done

encerrar() {
  local codigo=$?
  set +e
  parar_processo "$API_PID" "notify-api"
  parar_processo "$WORKER_PID" "notify-worker"
  exit "$codigo"
}
trap encerrar EXIT

# POST que precisa responder 202. Deixa o id criado em ULTIMO_ID. Chame direto, nunca dentro de $(...).
postar_202() {
  local descricao=$1 json=$2
  local status
  status=$(post_notificacao "$json")
  [ "$status" = "202" ] || falhou "POST $descricao respondeu $status (esperado 202): $(corpo_resposta)"
  ULTIMO_ID=$(id_da_resposta)
  [ -n "$ULTIMO_ID" ] || falhou "POST $descricao sem id na resposta: $(corpo_resposta)"
}

# Confere que o último GET (NOTIFICACAO) contém <texto> exatamente <n> vezes.
exige_contagem() {
  local id=$1 texto=$2 esperado=$3
  local n
  n=$(contar "$texto" "$NOTIFICACAO")
  [ "$n" = "$esperado" ] || falhou "$id tem $n ocorrência(s) de $texto (esperado $esperado): $NOTIFICACAO"
}

# Confere que o último GET (NOTIFICACAO) contém <texto>.
exige_texto() {
  local id=$1 texto=$2
  grep -qF -- "$texto" <<<"$NOTIFICACAO" || falhou "GET de $id não contém $texto: $NOTIFICACAO"
}

# Faz polling até todas as notificações dadas chegarem a <status> (timeout em segundos). Falha na hora
# se alguma virar FAILED. Deixa em ULTIMOS_GETS os corpos finais, um por linha.
esperar_todas() {
  local esperado=$1 timeout=$2
  shift 2
  local limite=$((SECONDS + timeout))
  local pendentes=("$@") restantes id corpo
  ULTIMOS_GETS=""
  while ((${#pendentes[@]} > 0)); do
    restantes=()
    for id in "${pendentes[@]}"; do
      corpo=$(get_notificacao "$id")
      if grep -qF "\"status\":\"$esperado\"" <<<"$corpo"; then
        ULTIMOS_GETS+="$corpo"$'\n'
      elif grep -qF '"status":"FAILED"' <<<"$corpo"; then
        falhou "$id terminou FAILED (esperado $esperado): $corpo"
      else
        restantes+=("$id")
      fi
    done
    pendentes=("${restantes[@]}")
    ((${#pendentes[@]} == 0)) && break
    ((SECONDS < limite)) \
      || falhou "${#pendentes[@]} notificação(ões) não chegaram a $esperado em ${timeout}s: ${pendentes[*]}"
    sleep 1
  done
}

# Faz polling até a métrica (linha exata do scrape do worker) ficar maior que zero (timeout em s).
esperar_metrica_positiva() {
  local linha=$1 timeout=$2
  local limite=$((SECONDS + timeout)) valor=0
  while ((SECONDS < limite)); do
    valor=$(valor_metrica "$linha")
    maior_que_zero "$valor" && { METRICA_VALOR=$valor; return 0; }
    sleep 1
  done
  falhou "$linha continua em $valor depois de ${timeout}s"
}

# ---------------------------------------------------------------------------------------------
# 1. Pré-condições, infra e build
# ---------------------------------------------------------------------------------------------
mkdir -p "$SMOKE_DIR"
info "Smoke da Fase 2 (perfil demo, níveis 1s 5s 25s)"

docker info >/dev/null 2>&1 || falhou "Docker não está rodando: inicie o Docker Desktop"
ok "Docker respondendo"
porta_livre 8080 || falhou "a porta 8080 está ocupada: encerre a notify-api (ou outro processo) antes do smoke"
porta_livre 8081 || falhou "a porta 8081 está ocupada: encerre o notify-worker (ou outro processo) antes do smoke"
ok "portas 8080 e 8081 livres"

info "docker compose up -d --wait"
docker compose up -d --wait || falhou "docker compose up -d --wait não deixou rabbitmq e mongo healthy"
ok "rabbitmq e mongo healthy"

if $SEM_BUILD; then
  info "--sem-build: usando os jars já empacotados"
else
  info "./mvnw -q -DskipTests package"
  ./mvnw -q -DskipTests package || falhou "o empacotamento com ./mvnw falhou"
  ok "jars empacotados"
fi

# ---------------------------------------------------------------------------------------------
# 2. Worker e API no profile demo
# ---------------------------------------------------------------------------------------------
info "subindo notify-worker (log em target/smoke/fase2-worker-1.log)"
iniciar_worker "$SMOKE_DIR/fase2-worker-1.log"
info "subindo notify-api (log em target/smoke/fase2-api.log)"
iniciar_api "$SMOKE_DIR/fase2-api.log"

# ---------------------------------------------------------------------------------------------
# 3. Critério 1: always-transient desce a escada visível até DEAD_LETTERED (D-11, D-14)
# ---------------------------------------------------------------------------------------------
postar_202 "EMAIL always-transient" \
  '{"channel":"EMAIL","recipient":"always-transient@fail.test","templateCode":"welcome","variables":{"name":"Ana"}}'
TRANSIENT_ID=$ULTIMO_ID
ok "POST EMAIL always-transient@fail.test → 202 ($TRANSIENT_ID)"

viu_retrying=false
viu_fila_25s=false
NOTIFICACAO=""
limite=$((SECONDS + 60))
while ((SECONDS < limite)); do
  NOTIFICACAO=$(get_notificacao "$TRANSIENT_ID")
  grep -qF '"status":"RETRYING"' <<<"$NOTIFICACAO" && viu_retrying=true
  grep -qF '"status":"DEAD_LETTERED"' <<<"$NOTIFICACAO" && break
  if ! $viu_fila_25s; then
    na_fila=$(mensagens_na_fila notify.email.retry.25s || echo 0)
    [ "${na_fila:-0}" -gt 0 ] && viu_fila_25s=true
  fi
  sleep 1
done
grep -qF '"status":"DEAD_LETTERED"' <<<"$NOTIFICACAO" \
  || falhou "$TRANSIENT_ID não chegou a DEAD_LETTERED em 60s. Último GET: $NOTIFICACAO"
$viu_retrying || falhou "nenhuma leitura do GET de $TRANSIENT_ID mostrou RETRYING"
$viu_fila_25s || falhou "notify.email.retry.25s nunca mostrou mensagem durante a escada de $TRANSIENT_ID"
exige_contagem "$TRANSIENT_ID" '"number":' 4
for nivel in 0 1 2 3; do
  exige_texto "$TRANSIENT_ID" "\"retryLevel\":$nivel"
done
# "errorCode" é o campo das tentativas; o lastError repete o código em "code"
exige_contagem "$TRANSIENT_ID" '"errorCode":"SIMULATED_TRANSIENT"' 4
exige_texto "$TRANSIENT_ID" '"failureClass":"TRANSIENT"'
ok "$TRANSIENT_ID: RETRYING visto, retry.25s com mensagem, DEAD_LETTERED com 4 tentativas (níveis 0..3, SIMULATED_TRANSIENT)"

dlq_tem_header notify.email.dlq "$TRANSIENT_ID" TRANSIENT \
  || falhou "notify.email.dlq não tem a mensagem de $TRANSIENT_ID com x-failure-class TRANSIENT"
ok "notify.email.dlq tem $TRANSIENT_ID com x-failure-class: TRANSIENT"

# ---------------------------------------------------------------------------------------------
# 4. Critério 2: permanentes direto para FAILED, sem fila de delay (D-10, D-16)
# ---------------------------------------------------------------------------------------------

# POST → FAILED em 15s com exatamente 1 tentativa no nível 0, o código esperado e a cópia na DLQ.
permanente() {
  local descricao=$1 json=$2 codigo=$3 dlq=$4
  local id
  postar_202 "$descricao" "$json"
  id=$ULTIMO_ID
  esperar_status "$id" FAILED 15
  exige_contagem "$id" '"number":' 1
  exige_texto "$id" '"retryLevel":0'
  exige_texto "$id" "\"errorCode\":\"$codigo\""
  exige_texto "$id" '"failureClass":"PERMANENT"'
  dlq_tem_header "$dlq" "$id" PERMANENT \
    || falhou "$dlq não tem a mensagem de $id ($descricao) com x-failure-class PERMANENT"
  ok "$descricao ($id): FAILED com 1 tentativa $codigo e cópia em $dlq com x-failure-class: PERMANENT"
}

permanente "EMAIL always-permanent" \
  '{"channel":"EMAIL","recipient":"always-permanent@fail.test","templateCode":"welcome","variables":{"name":"Ana"}}' \
  SIMULATED_PERMANENT notify.email.dlq
permanente "EMAIL template inexistente" \
  '{"channel":"EMAIL","recipient":"ana@exemplo.com","templateCode":"nao-existe","variables":{"name":"Ana"}}' \
  TEMPLATE_NOT_FOUND notify.email.dlq
permanente "SMS order-shipped sem orderId" \
  '{"channel":"SMS","recipient":"+5511999998888","templateCode":"order-shipped","variables":{"name":"Ana"}}' \
  MISSING_VARIABLE notify.sms.dlq

# ---------------------------------------------------------------------------------------------
# 5. Critério 5: webhook com aspas e barra invertida continua JSON válido (D-16)
# ---------------------------------------------------------------------------------------------
postar_202 "WEBHOOK password-reset com aspas" \
  '{"channel":"WEBHOOK","recipient":"https://example.test/hooks/notify","templateCode":"password-reset","variables":{"name":"Ana \"Bia\" \\ x","resetCode":"789012"}}'
ASPAS_ID=$ULTIMO_ID
esperar_status "$ASPAS_ID" SENT 15
ok "WEBHOOK com aspas e barra invertida ($ASPAS_ID) chegou a SENT (corpo JSON válido)"

# ---------------------------------------------------------------------------------------------
# 7. Critério 3a: fail-2-then-ok se recupera na 3ª tentativa, no nível 2 (D-02)
# ---------------------------------------------------------------------------------------------
postar_202 "EMAIL fail-2-then-ok" \
  '{"channel":"EMAIL","recipient":"fail-2-then-ok@fail.test","templateCode":"welcome","variables":{"name":"Ana"}}'
RECUPERA_ID=$ULTIMO_ID
esperar_status "$RECUPERA_ID" SENT 30
exige_contagem "$RECUPERA_ID" '"number":' 3
exige_contagem "$RECUPERA_ID" '"errorCode":"SIMULATED_TRANSIENT"' 2
# Campos na ordem do AttemptView: number, retryLevel, timestamp, durationMs, outcome
grep -qE '"number":3,"retryLevel":2,"timestamp":"[^"]*","durationMs":[0-9]+,"outcome":"SUCCESS"' <<<"$NOTIFICACAO" \
  || falhou "a 3ª tentativa de $RECUPERA_ID não é SUCCESS no retryLevel 2: $NOTIFICACAO"
ok "fail-2-then-ok ($RECUPERA_ID): SENT com 3 tentativas, 2 SIMULATED_TRANSIENT e a última SUCCESS no nível 2"

# ---------------------------------------------------------------------------------------------
# 8. Critério 3b: worker derrubado antes do ack retoma sem envio duplicado (WRK-03, D-09, D-12)
# ---------------------------------------------------------------------------------------------
parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
info "subindo notify-worker com latência de webhook 8s (log em target/smoke/fase2-worker-2.log)"
iniciar_worker "$SMOKE_DIR/fase2-worker-2.log" \
  --notify.simulation.webhook.latency-min=8s --notify.simulation.webhook.latency-max=8s

postar_202 "WEBHOOK com latência de 8s" \
  '{"channel":"WEBHOOK","recipient":"https://example.test/hooks/notify","templateCode":"password-reset","variables":{"name":"Ana","resetCode":"123456"}}'
KILL_ID=$ULTIMO_ID
esperar_status "$KILL_ID" PROCESSING 10
ok "WEBHOOK $KILL_ID em PROCESSING (adapter dentro dos 8s de latência)"
matar_abruptamente "$WORKER_PID" "notify-worker"
WORKER_PID=""
status_depois_kill=$(get_notificacao "$KILL_ID" | grep -o '"status":"[A-Z_]*"' | head -n 1 | cut -d'"' -f4 || true)
[ "$status_depois_kill" = "PROCESSING" ] \
  || falhou "$KILL_ID deveria continuar PROCESSING depois do kill (veio ${status_depois_kill:-nada}): o ack saiu antes?"
ok "worker derrubado com $KILL_ID ainda PROCESSING (mensagem sem ack volta para a fila)"

info "subindo notify-worker com a latência padrão (log em target/smoke/fase2-worker-3.log)"
iniciar_worker "$SMOKE_DIR/fase2-worker-3.log"
esperar_status "$KILL_ID" SENT 30
exige_contagem "$KILL_ID" '"number":' 1
exige_texto "$KILL_ID" '"outcome":"SUCCESS"'
entregas_2=$(grep -cF "Notificação entregue id=$KILL_ID" "$SMOKE_DIR/fase2-worker-2.log" || true)
entregas_3=$(grep -cF "Notificação entregue id=$KILL_ID" "$SMOKE_DIR/fase2-worker-3.log" || true)
[ $(( ${entregas_2:-0} + ${entregas_3:-0} )) -eq 1 ] \
  || falhou "logs dos workers 2 e 3 somam $(( ${entregas_2:-0} + ${entregas_3:-0} )) linhas 'Notificação entregue id=$KILL_ID' (esperado 1)"
grep -qF "reentrega retomada id=$KILL_ID" "$SMOKE_DIR/fase2-worker-3.log" \
  || falhou "fase2-worker-3.log não registrou 'reentrega retomada id=$KILL_ID'"
ok "$KILL_ID retomado (redelivered) e SENT com 1 tentativa SUCCESS; uma única linha 'Notificação entregue' nos dois logs"

# ---------------------------------------------------------------------------------------------
# 9. Critério 4a: rajada de always-transient abre o circuit breaker do webhook (D-04, D-05, D-06)
# ---------------------------------------------------------------------------------------------
CB_IDS=()
for i in $(seq 1 20); do
  postar_202 "WEBHOOK always-transient #$i" \
    '{"channel":"WEBHOOK","recipient":"https://fail.test/always-transient","templateCode":"password-reset","variables":{"name":"Ana","resetCode":"123456"}}'
  CB_IDS+=("$ULTIMO_ID")
done
ok "20 WEBHOOK para https://fail.test/always-transient aceitos (202)"

esperar_metrica_positiva 'notify_delivery_rejections_total{channel="webhook",reason="circuit_open"}' 20
ok "notify_delivery_rejections_total{channel=\"webhook\",reason=\"circuit_open\"} = $METRICA_VALOR"

esperar_todas DEAD_LETTERED 90 "${CB_IDS[@]}"
grep -qF '"errorCode":"CIRCUIT_OPEN"' <<<"$ULTIMOS_GETS" \
  || falhou "nenhuma das 20 notificações da rajada tem tentativa CIRCUIT_OPEN"
cb_recusas=$(contar '"errorCode":"CIRCUIT_OPEN"' "$ULTIMOS_GETS")
ok "as 20 da rajada terminaram DEAD_LETTERED, nenhuma FAILED; $cb_recusas tentativa(s) CIRCUIT_OPEN"

NORMAIS_IDS=()
for i in 1 2 3 4 5; do
  postar_202 "WEBHOOK comum #$i" \
    '{"channel":"WEBHOOK","recipient":"https://example.test/hooks/notify","templateCode":"password-reset","variables":{"name":"Ana","resetCode":"123456"}}'
  NORMAIS_IDS+=("$ULTIMO_ID")
done
esperar_todas SENT 45 "${NORMAIS_IDS[@]}"
ok "5 WEBHOOK comuns enviados depois da rajada chegaram a SENT, nenhum FAILED"

# ---------------------------------------------------------------------------------------------
# 10. Critério 4b: bulkhead do SMS recusa o excesso sem nenhum FAILED (D-07, D-08)
# ---------------------------------------------------------------------------------------------
parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
info "subindo notify-worker com bulkhead 2 e latência de SMS 1s (log em target/smoke/fase2-worker-4.log)"
export NOTIFY_BULKHEAD_MAX_CONCURRENT=2
iniciar_worker "$SMOKE_DIR/fase2-worker-4.log" \
  --notify.simulation.sms.latency-min=1s --notify.simulation.sms.latency-max=1s
unset NOTIFY_BULKHEAD_MAX_CONCURRENT

BH_IDS=()
for i in 1 2 3 4 5 6; do
  postar_202 "SMS order-shipped #$i" \
    '{"channel":"SMS","recipient":"+5511999998888","templateCode":"order-shipped","variables":{"name":"Ana","orderId":"PED-1"}}'
  BH_IDS+=("$ULTIMO_ID")
done
esperar_todas SENT 45 "${BH_IDS[@]}"
grep -qF '"errorCode":"BULKHEAD_FULL"' <<<"$ULTIMOS_GETS" \
  || falhou "nenhum dos 6 SMS tem tentativa BULKHEAD_FULL"
bh_recusas=$(contar '"errorCode":"BULKHEAD_FULL"' "$ULTIMOS_GETS")
bh_metrica=$(valor_metrica 'notify_delivery_rejections_total{channel="sms",reason="bulkhead_full"}')
maior_que_zero "$bh_metrica" \
  || falhou "notify_delivery_rejections_total{channel=\"sms\",reason=\"bulkhead_full\"} = $bh_metrica (esperado > 0)"
ok "6 SMS SENT, nenhum FAILED; $bh_recusas tentativa(s) BULKHEAD_FULL e rejections{sms,bulkhead_full} = $bh_metrica"

parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
info "subindo notify-worker com os padrões do profile demo (log em target/smoke/fase2-worker-5.log)"
iniciar_worker "$SMOKE_DIR/fase2-worker-5.log"

# ---------------------------------------------------------------------------------------------
# 11. Métricas no /actuator/prometheus do worker (D-15, OBS-01)
# ---------------------------------------------------------------------------------------------
outcomes=$(metrica '^notify_delivery_outcomes_total\{' | sort -u | wc -l | tr -d ' ')
[ "$outcomes" = "15" ] || falhou "o scrape do worker tem $outcomes séries notify_delivery_outcomes_total (esperado 15)"
for nivel in 1 2 3; do
  [ -n "$(metrica "^notify_delivery_retries_total\\{.*level=\"$nivel\"")" ] \
    || falhou "o scrape do worker não tem notify_delivery_retries_total com level=\"$nivel\""
done
for serie in notify_delivery_duration_seconds_bucket notify_delivery_rejections_total \
  resilience4j_circuitbreaker_state resilience4j_bulkhead_available_concurrent_calls; do
  [ -n "$(metrica "^$serie\\{")" ] || falhou "o scrape do worker não tem $serie"
done
ok "$WORKER_URL/actuator/prometheus: 15 séries de outcomes, retries nos níveis 1/2/3, histograma de duração, rejections, estado do CB e bulkhead"

# ---------------------------------------------------------------------------------------------
# 12. Logs dos workers
# ---------------------------------------------------------------------------------------------
for log in "$SMOKE_DIR"/fase2-worker-*.log; do
  for proibido in 'Consumer failed to start' 'PRECONDITION_FAILED' 'Cannot find template location'; do
    if grep -qF "$proibido" "$log"; then
      falhou "$(basename "$log") contém '$proibido'"
    fi
  done
done
ok "logs fase2-worker-*.log sem 'Consumer failed to start', PRECONDITION_FAILED nem aviso de template location"

# ---------------------------------------------------------------------------------------------
# Fim
# ---------------------------------------------------------------------------------------------
info "notificações criadas: always-transient $TRANSIENT_ID, webhook com aspas $ASPAS_ID, fail-2-then-ok $RECUPERA_ID, retomada $KILL_ID"

# Encerra as apps antes da linha final, para que "SMOKE FASE 2: OK" seja a última linha da saída.
# A infra continua no ar para inspeção.
parar_processo "$API_PID" "notify-api"
API_PID=""
parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
echo "SMOKE FASE 2: OK"
