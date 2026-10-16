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
#   - critério 5: webhook com aspas e barra invertida nas variáveis chega a SENT (JSON válido).
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
# Fim
# ---------------------------------------------------------------------------------------------
info "notificações criadas: always-transient $TRANSIENT_ID, webhook com aspas $ASPAS_ID"

# Encerra as apps antes da linha final, para que "SMOKE FASE 2: OK" seja a última linha da saída.
# A infra continua no ar para inspeção.
parar_processo "$API_PID" "notify-api"
API_PID=""
parar_processo "$WORKER_PID" "notify-worker"
WORKER_PID=""
echo "SMOKE FASE 2: OK"
