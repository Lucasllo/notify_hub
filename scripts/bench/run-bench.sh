#!/usr/bin/env bash
# Benchmark virtual threads x threads de plataforma no worker do Notify Hub.
#
# Objetivo: medir a vazão de drenagem de um backlog quando só o tipo de thread do worker muda
# (VIRTUAL_THREADS=true|false). Concorrência, prefetch, latência do adapter, failure-rate, bulkhead,
# circuit breaker, pool do MongoDB, níveis de retry e logs ficam iguais nos dois modos
# (docker-compose.bench.yml).
#
# Uso (a partir de qualquer pasta; o script vai para a raiz do repositório):
#   bash scripts/bench/run-bench.sh [--calibrar] [--varredura] [--sem-build] [--manter]
#     (sem flag)   protocolo oficial: BENCH_REPETICOES rodadas por modo, intercaladas (vt1, pt1, vt2,
#                  pt2...), mais uma rodada com JFR fora da mediana, e o resumo com mediana, mínimo e máximo
#     --calibrar   só uma rodada por modo com N_CAL notificações e sugere N e W para as rodadas oficiais
#     --varredura  depois das rodadas oficiais, uma rodada por modo com C=50 e C=500
#     --sem-build  não reconstrói as imagens da api e do worker
#     --manter     não derruba o projeto notify-bench no fim (para olhar o Grafana ou o RabbitMQ)
#
# ENVs (todas opcionais):
#   BENCH_C           consumidores por canal (padrão 200; no máximo 650 por causa do channel_max 2047)
#   BENCH_N           notificações medidas por rodada (padrão calibrado, ver abaixo)
#   BENCH_W           notificações de aquecimento, descartadas da medida (padrão N/5)
#   BENCH_N_CAL       N da calibração (padrão 6000)
#   BENCH_LATENCIA    latência fixa do adapter simulado (padrão 100ms)
#   BENCH_POOL        maxPoolSize do MongoDB no worker (padrão 200)
#   BENCH_REPETICOES  rodadas oficiais por modo, intercaladas (padrão 5)
#
# Cada rodada: para o worker, limpa a coleção notifications e as filas, cria o backlog de W+N com o
# k6 (worker parado), sobe o worker num container novo com o modo da rodada, espera todas virarem
# SENT e mede a vazão no MongoDB (scripts/bench/drain-stats.js), descartando as W primeiras conclusões.
#
# Duração esperada: a calibração leva uns 10 minutos; as rodadas oficiais com --varredura, de 40 a
# 60 minutos, dependendo da máquina.
#
# Resultados em target/bench/ (ignorado pelo git):
#   resumo.txt       ambiente, parâmetros, mediana/mín/máx por modo, contagem de pinning e varredura
#   resultados.csv   uma linha por rodada, de todas as execuções (a coluna execucao separa uma da outra)
#   worker-vt.jfr    gravação JFR da rodada vt-jfr; jfr-pinned.txt traz os eventos jdk.VirtualThreadPinned
#   k6-<tag>.log     saída do k6 de cada rodada; build.log, saída do build das imagens
# Para guardar a saída inteira: bash scripts/bench/run-bench.sh --varredura > target/bench/execucao.log 2>&1
#
# Atenção: o benchmark roda num projeto compose separado (notify-bench), com volumes próprios, mas usa
# as mesmas portas do host que a demo. Por isso o script PARA a demo (docker compose stop, sem apagar
# volumes) se ela estiver no ar. No fim, o "down -v" apaga só os volumes do projeto notify-bench.
# Para voltar à demo depois: docker compose up -d
#
# Pré-requisitos: Docker Desktop ligado e curl. Não depende de jq, nem de JDK ou Maven no host.

set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../smoke-lib.sh
source scripts/smoke-lib.sh

CALIBRAR=false
VARREDURA=false
BUILD=true
MANTER=false
for arg in "$@"; do
  case "$arg" in
    --calibrar) CALIBRAR=true ;;
    --varredura) VARREDURA=true ;;
    --sem-build) BUILD=false ;;
    --manter) MANTER=true ;;
    *) falhou "opção desconhecida: $arg (use --calibrar, --varredura, --sem-build ou --manter)" ;;
  esac
done

BENCH_C=${BENCH_C:-200}
# N e W padrão saíram do --calibrar na máquina do autor: ~480 msg/s no melhor modo, então o mínimo de
# 30000 já dá uma janela medida de mais de 60s. Em outra máquina, rode --calibrar e ajuste por ENV.
BENCH_N=${BENCH_N:-30000}
BENCH_W=${BENCH_W:-6000}
BENCH_N_CAL=${BENCH_N_CAL:-6000}
BENCH_LATENCIA=${BENCH_LATENCIA:-100ms}
BENCH_POOL=${BENCH_POOL:-200}
BENCH_REPETICOES=${BENCH_REPETICOES:-5}
# Lidas pelo docker-compose.bench.yml; iguais em todas as rodadas
export BENCH_LATENCIA BENCH_POOL

BENCH_DIR="target/bench"
CSV="$BENCH_DIR/resultados.csv"
# Identifica as linhas desta execução no CSV, que acumula todas as execuções
EXECUCAO="$(date -u +%Y%m%dT%H%M%SZ)"
WORKER_METRICAS="http://127.0.0.1:8081/actuator/prometheus"
TETO_DRENAGEM_S=900

COMPOSE=(docker compose -p notify-bench -f docker-compose.yml -f docker-compose.bench.yml)

mkdir -p "$BENCH_DIR"
[ -f "$CSV" ] || echo "execucao,tag,modo,c,n,w,janela_ms,msgs_por_s,pico_threads,rss_max_mib,espera_pool_max" >"$CSV"

# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

# Executa JavaScript no banco "notify" pelo mongosh do container mongo do projeto notify-bench.
mongo_bench() { MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T mongo mongosh notify --quiet --eval "$1" | tr -d '\r'; }

contar_status() { mongo_bench "print(db.notifications.countDocuments({status: '$1'}))"; }

# Soma de todas as séries de uma métrica do worker (0 se o endpoint não responder ou a métrica faltar).
metrica_worker() {
  { curl -s --max-time 5 "$WORKER_METRICAS" || true; } | tr -d '\r' |
    awk -v m="$1" '$1 == m || index($1, m "{") == 1 { s += $2; f = 1 } END { if (f) printf "%.0f", s; else print 0 }'
}

# Memória do container do worker segundo o docker stats, em MiB (0 se não der para ler).
rss_worker_mib() {
  local cid uso
  cid=$("${COMPOSE[@]}" ps -q worker 2>/dev/null || true)
  [ -n "$cid" ] || { echo 0; return; }
  uso=$(docker stats --no-stream --format '{{.MemUsage}}' "$cid" 2>/dev/null | awk '{print $1}' || true)
  awk -v u="$uso" 'BEGIN {
    v = u + 0
    if (u ~ /GiB$/) v *= 1024; else if (u ~ /KiB$/) v /= 1024; else if (u ~ /[0-9]B$/) v /= 1048576
    printf "%.0f", v
  }'
}

maior() { awk -v a="$1" -v b="$2" 'BEGIN { print (a + 0 >= b + 0) ? a : b }'; }

# Valores de uma coluna do CSV para as rodadas desta execução cujo tag casa com a regex.
# Colunas: 8 = msgs_por_s, 9 = pico_threads, 10 = rss_max_mib.
coluna() { awk -F, -v e="$EXECUCAO" -v r="$1" -v c="$2" '$1 == e && $2 ~ r { print $c }' "$CSV"; }

# Mediana da entrada (um número por linha): elemento central; com quantidade par, média dos dois centrais.
mediana() {
  sort -g | awk '{ a[NR] = $1 } END {
    if (NR == 0) { print "n/a"; exit }
    if (NR % 2) print a[(NR + 1) / 2]; else print (a[NR / 2] + a[NR / 2 + 1]) / 2
  }'
}
minimo() { sort -g | head -n 1; }
maximo() { sort -g | tail -n 1; }

# Linha "<modo> mediana_msgs=... min_msgs=... max_msgs=... mediana_pico_threads=... mediana_rss_mib=..."
# das rodadas oficiais (tags vtN ou ptN; JFR e varredura ficam de fora).
linha_modo() {
  local nome=$1 regex=$2
  echo "$nome mediana_msgs=$(coluna "$regex" 8 | mediana) min_msgs=$(coluna "$regex" 8 | minimo)" \
    "max_msgs=$(coluna "$regex" 8 | maximo) mediana_pico_threads=$(coluna "$regex" 9 | mediana)" \
    "mediana_rss_mib=$(coluna "$regex" 10 | mediana)"
}

# Filas do Notify Hub com mensagens prontas (nome por linha).
filas_com_mensagens() {
  MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q list_queues name messages | tr -d '\r' |
    awk '$1 ~ /^notify\./ && $2 + 0 > 0 { print $1 }'
}

# ---------------------------------------------------------------------------------------------
# Uma rodada: rodada <tag> <true|false> <C> <N> <W>
# ---------------------------------------------------------------------------------------------

rodada() {
  local tag=$1 vt=$2 c=$3 n=$4 w=$5
  local total=$((w + n)) modo fila pendentes falhas enviados limite rss rss_max=0 espera espera_max=0
  local pico stats janela msgs
  [ "$vt" = "true" ] && modo=virtual || modo=plataforma
  info "rodada $tag: modo=$modo c=$c n=$n w=$w"

  # 1. worker parado, coleção e filas vazias
  "${COMPOSE[@]}" stop worker >/dev/null 2>&1
  mongo_bench "db.notifications.deleteMany({})" >/dev/null
  for fila in $(filas_com_mensagens); do
    info "purgando a fila $fila"
    MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q purge_queue "$fila" >/dev/null
  done

  # 2. backlog com o worker parado (--no-deps: o k6 depende do worker no compose base)
  if ! MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" --profile bench run --rm --no-deps -e ITERATIONS="$total" \
    k6 run --quiet /scripts/bench-backlog.js >"$BENCH_DIR/k6-$tag.log" 2>&1; then
    tail -n 30 "$BENCH_DIR/k6-$tag.log"
    falhou "o k6 não criou o backlog da rodada $tag (saída em $BENCH_DIR/k6-$tag.log)"
  fi

  # 3. backlog íntegro: todas PENDING, nenhuma PUBLISH_FAILED
  pendentes=$(contar_status PENDING)
  falhas=$(contar_status PUBLISH_FAILED)
  [ "$pendentes" = "$total" ] && [ "$falhas" = "0" ] ||
    falhou "backlog da rodada $tag inválido: PENDING=$pendentes (esperado $total) PUBLISH_FAILED=$falhas"

  # 4. worker num container novo (JVM nova) com o modo da rodada. MSYS_NO_PATHCONV também vale para as
  #    ENVs: sem ele o Git Bash trocaria o /tmp/worker.jfr do BENCH_JAVA_TOOL_OPTIONS por um caminho do Windows
  MSYS_NO_PATHCONV=1 BENCH_VIRTUAL_THREADS=$vt BENCH_C=$c \
    "${COMPOSE[@]}" up -d --wait --no-deps --force-recreate worker >/dev/null 2>&1 ||
    falhou "o worker não ficou saudável na rodada $tag (veja: ${COMPOSE[*]} logs worker)"

  # 5. drenagem, registrando o pico de memória e de espera no pool do MongoDB
  limite=$((SECONDS + TETO_DRENAGEM_S))
  while :; do
    rss=$(rss_worker_mib)
    rss_max=$(maior "$rss_max" "$rss")
    espera=$(metrica_worker mongodb_driver_pool_waitqueuesize)
    espera_max=$(maior "$espera_max" "$espera")
    enviados=$(contar_status SENT)
    [ "$enviados" = "$total" ] && break
    ((SECONDS < limite)) ||
      falhou "a rodada $tag não drenou em ${TETO_DRENAGEM_S}s: SENT=$enviados de $total ($(mongo_bench "print(JSON.stringify(db.notifications.aggregate([{\$group: {_id: '\$status', n: {\$sum: 1}}}]).toArray()))"))"
    sleep 2
  done

  # 6. pico de threads de plataforma da JVM (ThreadMXBean não conta virtual threads)
  pico=$(metrica_worker jvm_threads_peak_threads)

  # 7. vazão no MongoDB, com as W primeiras conclusões descartadas
  stats=$(mongo_bench "var warmup=$w; $(cat scripts/bench/drain-stats.js)")
  grep -q '"msgPorSeg"' <<<"$stats" || falhou "drain-stats da rodada $tag sem resultado: $stats"
  janela=$(grep -o '"janelaMs":[0-9]*' <<<"$stats" | cut -d: -f2)
  msgs=$(grep -o '"msgPorSeg":[0-9.]*' <<<"$stats" | cut -d: -f2)

  # 8. resultado
  echo "RESULTADO tag=$tag modo=$modo c=$c n=$n w=$w janela_ms=$janela msgs_por_s=$msgs pico_threads=$pico rss_max_mib=$rss_max espera_pool_max=$espera_max"
  echo "$EXECUCAO,$tag,$modo,$c,$n,$w,$janela,$msgs,$pico,$rss_max,$espera_max" >>"$CSV"
}

# ---------------------------------------------------------------------------------------------
# Subida do projeto notify-bench
# ---------------------------------------------------------------------------------------------

((BENCH_C <= 650)) ||
  falhou "BENCH_C=$BENCH_C acima de 650: consumidores e publishers dividem uma conexão e o channel_max do broker é 2047"

if [ -n "$(docker compose -p notify-hub ps -q --status running 2>/dev/null || true)" ]; then
  info "parando a demo (notify-hub) para liberar as portas; os volumes ficam (para voltar: docker compose up -d)"
  docker compose -p notify-hub stop >/dev/null 2>&1
fi

if $BUILD; then
  info "construindo as imagens da api e do worker (projeto notify-bench)"
  "${COMPOSE[@]}" build api worker >"$BENCH_DIR/build.log" 2>&1 || {
    tail -n 30 "$BENCH_DIR/build.log"
    falhou "build das imagens falhou (saída em $BENCH_DIR/build.log)"
  }
fi

info "subindo rabbitmq, mongo, api, prometheus e grafana (projeto notify-bench)"
"${COMPOSE[@]}" up -d --wait rabbitmq mongo api prometheus grafana >/dev/null 2>&1 ||
  falhou "o projeto notify-bench não subiu (veja: ${COMPOSE[*]} ps)"
"${COMPOSE[@]}" stop worker >/dev/null 2>&1 || true
ok "projeto notify-bench no ar"

# ---------------------------------------------------------------------------------------------
# Calibração
# ---------------------------------------------------------------------------------------------

if $CALIBRAR; then
  W_CAL=$((BENCH_N_CAL / 5))
  rodada cal-vt true "$BENCH_C" "$BENCH_N_CAL" "$W_CAL"
  rodada cal-pt false "$BENCH_C" "$BENCH_N_CAL" "$W_CAL"
  MAIOR_MSGS=$(awk -F, -v e="$EXECUCAO" '$1 == e && $2 ~ /^cal-/ { if ($8 + 0 > m) m = $8 + 0 } END { print m + 0 }' "$CSV")
  # Janela medida de ~30s na maior vazão (e nunca menos de 30000 notificações)
  SUGESTAO_N=$(awk -v x="$MAIOR_MSGS" 'BEGIN { n = x * 30 / 1000; c = int(n); if (c < n) c++; c *= 1000; if (c < 30000) c = 30000; print c }')
  echo "CALIBRACAO maior_msgs=$MAIOR_MSGS sugestao_n=$SUGESTAO_N sugestao_w=$((SUGESTAO_N / 5))"
fi

# ---------------------------------------------------------------------------------------------
# Protocolo oficial (modo padrão): ambiente, rodadas intercaladas, JFR, varredura e resumo
# ---------------------------------------------------------------------------------------------

if ! $CALIBRAR; then
  # Ambiente declarado: CPU vista de dentro de um container, recursos da VM do Docker e a JVM das imagens
  CPU=$(MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T api grep -m1 "model name" /proc/cpuinfo | tr -d '\r' | cut -d: -f2- | sed 's/^ *//')
  NCPU=$(docker info --format '{{.NCPU}}' | tr -d '\r')
  MEM_GIB=$(awk -v b="$(docker info --format '{{.MemTotal}}' | tr -d '\r')" 'BEGIN { printf "%.1f", b / 1073741824 }')
  DOCKER_VERSAO=$(docker info --format '{{.ServerVersion}}' | tr -d '\r')
  SO=$(docker info --format '{{.OperatingSystem}}' | tr -d '\r')
  JVM=$(MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T api java -version 2>&1 | tr -d '\r' | head -n 1)
  info "ambiente: cpu=\"$CPU\" ncpu=$NCPU memoria_gib=$MEM_GIB docker=$DOCKER_VERSAO so=\"$SO\" jvm=$JVM"
  # Outros containers na mesma VM do Docker competem por CPU e memória: ficam registrados no log
  OUTROS=$(docker ps --format '{{.Names}}' | tr -d '\r' | grep -v '^notify-bench-' || true)
  if [ -n "$OUTROS" ]; then
    # shellcheck disable=SC2086
    info "outros containers no ar (carga concorrente): $(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' $OUTROS | tr -d '\r' | paste -sd ';' -)"
  else
    info "outros containers no ar: nenhum"
  fi

  # Rodadas oficiais intercaladas (vt1, pt1, vt2, pt2...): dilui deriva térmica e de cache entre os modos
  for i in $(seq 1 "$BENCH_REPETICOES"); do
    rodada "vt$i" true "$BENCH_C" "$BENCH_N" "$BENCH_W"
    rodada "pt$i" false "$BENCH_C" "$BENCH_N" "$BENCH_W"
  done

  # Rodada com JFR (fora da mediana: o Flight Recorder tem custo próprio). Limiar de 1ms para
  # jdk.VirtualThreadPinned (o padrão do JDK é 20ms). O dump acontece na saída da JVM, por isso o
  # worker para com SIGTERM (docker compose stop) antes da cópia
  export BENCH_JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=filename=/tmp/worker.jfr,settings=profile,jdk.VirtualThreadPinned#threshold=1ms"
  rodada vt-jfr true "$BENCH_C" "$BENCH_N" "$BENCH_W"
  unset BENCH_JAVA_TOOL_OPTIONS
  "${COMPOSE[@]}" stop worker >/dev/null 2>&1
  rm -f "$BENCH_DIR/worker-vt.jfr"
  if ! MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" cp worker:/tmp/worker.jfr "$BENCH_DIR/worker-vt.jfr" >/dev/null 2>&1; then
    # Fallback: docker cp direto no container parado do worker
    WORKER_CID=$("${COMPOSE[@]}" ps -aq worker | tr -d '\r')
    MSYS_NO_PATHCONV=1 docker cp "$WORKER_CID:/tmp/worker.jfr" "$BENCH_DIR/worker-vt.jfr" >/dev/null 2>&1 || true
  fi
  [ -s "$BENCH_DIR/worker-vt.jfr" ] ||
    falhou "gravação JFR ausente ou vazia ($BENCH_DIR/worker-vt.jfr): a JVM do worker não gravou o arquivo na saída"
  MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" cp "$BENCH_DIR/worker-vt.jfr" api:/tmp/worker-vt.jfr >/dev/null 2>&1 ||
    falhou "não deu para copiar o JFR para o container api"
  # Lido com o jfr da imagem (eclipse-temurin:21-jre tem jfr, não tem jcmd); root se a cópia ficou sem leitura
  JFR_SUMMARY=$(MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T api jfr summary /tmp/worker-vt.jfr 2>&1 | tr -d '\r' ||
    MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T -u 0 api jfr summary /tmp/worker-vt.jfr 2>&1 | tr -d '\r' || true)
  PINNED=$(awk '$1 == "jdk.VirtualThreadPinned" { print $2; exit }' <<<"$JFR_SUMMARY")
  [ -n "$PINNED" ] || {
    echo "$JFR_SUMMARY" | tail -n 20
    falhou "jfr summary sem a linha jdk.VirtualThreadPinned: a gravação está vazia ou o evento não foi habilitado"
  }
  MSYS_NO_PATHCONV=1 "${COMPOSE[@]}" exec -T -u 0 api jfr print --stack-depth 32 --events jdk.VirtualThreadPinned /tmp/worker-vt.jfr |
    tr -d '\r' >"$BENCH_DIR/jfr-pinned.txt" || true
  ok "JFR gravado em $BENCH_DIR/worker-vt.jfr: jdk.VirtualThreadPinned=$PINNED (detalhes em $BENCH_DIR/jfr-pinned.txt)"

  # Varredura de concorrência: uma rodada por modo, só para mostrar como as threads crescem com C
  if $VARREDURA; then
    for c in 50 500; do
      rodada "vt-c$c" true "$c" "$BENCH_N" "$BENCH_W"
      rodada "pt-c$c" false "$c" "$BENCH_N" "$BENCH_W"
    done
  fi

  # Resumo, reescrito a cada execução
  RESUMO="$BENCH_DIR/resumo.txt"
  {
    echo "data=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "hardware=cpu=\"$CPU\" ncpu=$NCPU memoria_gib=$MEM_GIB docker=$DOCKER_VERSAO so=\"$SO\""
    echo "jvm=$JVM"
    echo "parametros c=$BENCH_C n=$BENCH_N w=$BENCH_W latencia=$BENCH_LATENCIA pool=$BENCH_POOL prefetch=10 repeticoes=$BENCH_REPETICOES"
    linha_modo virtual '^vt[0-9]+$'
    linha_modo plataforma '^pt[0-9]+$'
    echo "jfr virtual_thread_pinned=$PINNED"
    if $VARREDURA; then
      for c in 50 500; do
        echo "varredura c=$c virtual msgs=$(coluna "^vt-c$c\$" 8) pico_threads=$(coluna "^vt-c$c\$" 9) rss_mib=$(coluna "^vt-c$c\$" 10)"
        echo "varredura c=$c plataforma msgs=$(coluna "^pt-c$c\$" 8) pico_threads=$(coluna "^pt-c$c\$" 9) rss_mib=$(coluna "^pt-c$c\$" 10)"
      done
    fi
  } >"$RESUMO"

  # Sanidade do modo: com threads de plataforma, cada um dos 3 x C consumidores tem a sua thread do SO.
  # Se o pico não passar de 3 x C, ou não for maior que o do modo virtual, o modo não foi aplicado
  PICO_PT=$(coluna '^pt[0-9]+$' 9 | mediana)
  PICO_VT=$(coluna '^vt[0-9]+$' 9 | mediana)
  awk -v p="$PICO_PT" -v v="$PICO_VT" -v c="$BENCH_C" 'BEGIN { exit !(p + 0 >= 3 * c && p + 0 > v + 0) }' ||
    falhou "sanidade do modo: mediana do pico de threads de plataforma no modo plataforma ($PICO_PT) precisa ser >= 3 x C ($((3 * BENCH_C))) e maior que a do modo virtual ($PICO_VT)"
  ok "sanidade do modo: pico de threads plataforma=$PICO_PT >= $((3 * BENCH_C)) e > virtual=$PICO_VT"

  echo "==== $RESUMO ===="
  cat "$RESUMO"
fi

# ---------------------------------------------------------------------------------------------
# Fim
# ---------------------------------------------------------------------------------------------

if $MANTER; then
  info "projeto notify-bench mantido no ar (Grafana em http://localhost:3000); para derrubar: ${COMPOSE[*]} down -v"
else
  "${COMPOSE[@]}" down -v >/dev/null 2>&1
  info "projeto notify-bench removido (só os volumes dele); para voltar à demo: docker compose up -d"
fi
echo "BENCH: OK"
