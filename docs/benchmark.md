# Benchmark: virtual threads x threads de plataforma no worker

O worker do Notify Hub roda os consumidores do RabbitMQ em virtual threads (`spring.threads.virtual.enabled=true`). A pergunta deste benchmark é simples: o que muda na drenagem de um backlog de notificações quando **só** o tipo de thread do worker muda? A resposta vem de um protocolo automatizado (`scripts/bench/run-bench.sh`). Os números abaixo são os que ele mediu, sem rodadas escolhidas a dedo.

## Método

### O que fica fixo

Os dois modos usam o mesmo override (`docker-compose.bench.yml`) e os valores da linha `parametros` do resumo:

| Item | Valor nos dois modos |
|------|----------------------|
| Consumidores por canal (C) | 200 (`NOTIFY_WORKER_CHANNELS_{EMAIL,SMS,WEBHOOK}_CONCURRENCY`), 600 no total |
| Prefetch | 10 (`spring.rabbitmq.listener.simple.prefetch`) |
| Latência do adapter simulado | fixa em 100ms (`latency-min` = `latency-max`), um `sleep` bloqueante |
| Failure-rate | 0 nos três canais: nenhuma mensagem passa pelas filas de delay |
| Destinatários mágicos | desligados (`NOTIFY_SIMULATION_MAGIC_RECIPIENTS=false`); o backlog só usa `example.com` e telefones fictícios `+5511…` |
| Bulkhead | `max-concurrent-calls` = C, então nunca recusa |
| Circuit breaker | neutralizado (janela e mínimo de 1000 chamadas, limiar de 100%) |
| Pool do MongoDB | `maxPoolSize=200` no worker |
| Níveis de retry | padrão (5s/25s/125s) na API e no worker |
| Logs por mensagem | em WARN (`dev.notifyhub.worker.channel` e `dev.notifyhub.worker.processing`) |
| Profile | nenhum profile de demo |
| Backlog | N = 30000 medidas + W = 6000 de aquecimento, um terço por canal, template `welcome` |

### O que varia

Só `VIRTUAL_THREADS` do worker: `true` (virtual) ou `false` (plataforma).

### Procedimento de cada rodada

1. O worker para, a coleção `notifications` é esvaziada e as filas `notify.*` com mensagens são purgadas.
2. O k6 (`k6/bench-backlog.js`) cria o backlog de W + N notificações pela API com o worker parado. O script confere que todas estão `PENDING` e nenhuma virou `PUBLISH_FAILED`.
3. O worker sobe num container novo, ou seja, numa JVM nova, com o modo da rodada.
4. O script espera todas as W + N chegarem a `SENT`. Enquanto isso, registra o pico de memória do container (`docker stats`) e o pico de `mongodb_driver_pool_waitqueuesize`.
5. No fim lê `jvm_threads_peak_threads` do worker. Essa métrica vem do `ThreadMXBean` e conta só threads de plataforma.
6. A vazão sai do MongoDB (`scripts/bench/drain-stats.js`): as SENT são ordenadas por `sentAt`, as W primeiras conclusões são descartadas como aquecimento (JIT, pools e conexões esquentando) e a vazão é N dividido pela janela entre a W-ésima e a última conclusão. Aquecimento e medição acontecem na mesma JVM.

Há 5 rodadas oficiais por modo, **intercaladas** (vt1, pt1, vt2, pt2…) para diluir deriva térmica, de cache e de carga da máquina. O resumo traz a mediana, o mínimo e o máximo por modo. Duas coisas ficam **fora da mediana** e estão rotuladas assim abaixo: uma rodada com JFR, porque o Flight Recorder tem custo próprio, e a varredura de concorrência (C = 50 e C = 500), com uma rodada por modo.

O script também tem uma sanidade do modo: se a mediana do pico de threads de plataforma no modo plataforma não passar de 3 × C, ou não for maior que a do modo virtual, ele falha, porque isso indicaria que o modo não foi aplicado.

### Por que a latência da API ficou de fora

O Tomcat com threads de plataforma tem teto de 200 threads (`server.tomcat.threads.max`). Com virtual threads esse teto não existe. Uma comparação de latência da API sob carga aberta compararia concorrências diferentes, não tipos de thread. Por isso a métrica principal é a drenagem do worker, em que a concorrência (C consumidores por canal) é a mesma nos dois modos.

## Ambiente

```
hardware=cpu="Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz" ncpu=8 memoria_gib=7.7 docker=29.8.0 so="Docker Desktop"
jvm=openjdk version "21.0.13" 2026-10-20 LTS
```

Os números são da máquina do autor (Windows 11, Docker Desktop). Tudo roda dentro da VM do Docker Desktop: RabbitMQ 4.3.6, MongoDB 8.0.32, API, worker, Prometheus e Grafana dividem as mesmas 8 CPUs e 7.7 GiB. Durante as rodadas também estava no ar um container de outro projeto, sem relação com este (um nó de Kubernetes local). No início da medição ele usava cerca de 40% de uma CPU e 3.1 GiB da VM (registrado no log do script), e o consumo dele oscilou ao longo da sessão. Como as rodadas são intercaladas, essa carga cai sobre os dois modos, mas varia com o tempo e ajuda a explicar a dispersão entre rodadas do mesmo modo.

## Resultados

### Rodadas oficiais (C = 200, N = 30000, W = 6000)

| Modo | Mediana (msg/s) | Mín–máx (msg/s) | Mediana do pico de threads de plataforma | Mediana da memória do container (MiB) |
|------|-----------------|-----------------|------------------------------------------|----------------------------------------|
| virtual | 744.4 | 508.3–871.6 | 40 | 718 |
| plataforma | 786.2 | 603.7–929.1 | 636 | 844 |

Todas as rodadas, na ordem em que rodaram (todas entram na mediana):

| Rodada | Modo | Janela medida (ms) | msg/s | Pico de threads de plataforma | Memória máx. (MiB) |
|--------|------|--------------------|-------|-------------------------------|--------------------|
| vt1 | virtual | 45228 | 663.3 | 39 | 676 |
| pt1 | plataforma | 42573 | 704.7 | 636 | 791 |
| vt2 | virtual | 40299 | 744.4 | 40 | 718 |
| pt2 | plataforma | 49692 | 603.7 | 636 | 793 |
| vt3 | virtual | 34418 | 871.6 | 40 | 774 |
| pt3 | plataforma | 32290 | 929.1 | 636 | 844 |
| vt4 | virtual | 35122 | 854.2 | 43 | 731 |
| pt4 | plataforma | 33088 | 906.7 | 636 | 858 |
| vt5 | virtual | 59026 | 508.3 | 39 | 706 |
| pt5 | plataforma | 38157 | 786.2 | 636 | 870 |

A espera no pool do MongoDB ficou em 0 em nove das dez rodadas oficiais (pico de 10 na pt2).

### Varredura de concorrência (uma rodada por modo, fora da mediana; C = 200 é a mediana oficial)

| C por canal | Modo | msg/s | Pico de threads de plataforma | Memória (MiB) |
|-------------|------|-------|-------------------------------|---------------|
| 50 | virtual | 786.4 | 43 | 643 |
| 50 | plataforma | 776 | 186 | 643 |
| 200 (oficial) | virtual | 744.4 | 40 | 718 |
| 200 (oficial) | plataforma | 786.2 | 636 | 844 |
| 500 | virtual | 807.5 | 41 | 750 |
| 500 | plataforma | 807.6 | 1536 | 1097 |

Com C = 500 são 1500 consumidores para 200 conexões no pool do MongoDB, e a espera no pool apareceu nos dois modos (pico de 261 no virtual e 211 no plataforma).

### Pinning de virtual threads (JFR)

A rodada `vt-jfr` (modo virtual, C = 200, fora da mediana, 867.5 msg/s) gravou o evento `jdk.VirtualThreadPinned` com limiar de 1ms (o padrão do JDK é 20ms):

```
jfr virtual_thread_pinned=7
```

Foram 7 eventos em 36000 mensagens, com duração entre 1.31ms e 23.5ms. Os 7 têm a mesma pilha: o `basicAck` do `amqp-client` (`ChannelN.basicAck` → `AMQConnection.writeFrame`) escreve o frame no socket segurando um monitor (`synchronized`). Enquanto espera o lock do stream, a virtual thread fica presa à thread carregadora. É raro e curto nesse fluxo: não aparece nenhum evento no driver do MongoDB nem no `sleep` do adapter.

## Leitura dos resultados

**Vazão: empate.** A mediana do modo plataforma (786.2 msg/s) ficou 42 msg/s acima da do modo virtual (744.4 msg/s), cerca de 5%. A dispersão dentro de cada modo é muito maior que essa diferença: 508.3–871.6 no virtual e 603.7–929.1 no plataforma. Os intervalos quase se sobrepõem por inteiro, e a vencedora troca de rodada para rodada (vt3 e vt4 ficaram acima da pt1, da pt2 e da pt5). Os dados não mostram ganho de vazão com virtual threads. Também não mostram uma perda que se distinga do ruído desta máquina.

Esse empate é o esperado. O `SimpleMessageListenerContainer` cria um consumidor por unidade de `concurrency`, e cada consumidor já tem a sua própria thread, virtual ou de plataforma. Com a mesma concorrência, os dois modos têm o mesmo número de entregas em voo. O teto teórico seria 3 × 200 / 100ms = 6000 msg/s, e o medido ficou em torno de 750 a 800 msg/s, ou seja, a vazão é limitada por outra coisa: I/O e CPU compartilhada. Cada entrega faz o claim e a gravação do resultado no MongoDB e o ack no RabbitMQ, e tudo divide as mesmas 8 CPUs da VM. A varredura confirma isso. De C = 50 para C = 500 a vazão quase não muda (de 776–786 para cerca de 807 msg/s), enquanto a concorrência teórica decuplica.

**Onde está a diferença: threads de plataforma e memória.** No modo virtual, o pico de threads de plataforma fica estável perto de 40 com qualquer C (39 a 43): são as carregadoras do `ForkJoinPool` e as threads fixas da JVM, do Tomcat e dos clientes. No modo plataforma ele cresce com 3 × C: 186 com C = 50, 636 com C = 200 e 1536 com C = 500. A memória do container acompanha: com C = 50 os dois modos ficaram iguais (643 MiB), com C = 200 a mediana foi 718 MiB contra 844 MiB (+126 MiB) e com C = 500 foram 750 MiB contra 1097 MiB (+347 MiB). Em resumo, virtual threads não deixam este worker mais rápido. Elas deixam subir a concorrência sem pagar uma thread do sistema operacional e a pilha nativa de cada consumidor.

## Limitações

- Uma máquina só, um i7-4790 de 8 threads, e uma execução do protocolo. Os números valem para esse hardware.
- Tudo roda na VM do Docker Desktop, junto com o broker, o banco e a API, e havia outro container de outro projeto consumindo CPU e memória da mesma VM. Isso aumenta a dispersão. Rodar num host dedicado deve estreitar os intervalos.
- A latência do adapter é simulada com `sleep`. Um provedor real teria I/O de rede, TLS e variância de latência, e isso não foi medido.
- Java 21. A documentação do Spring Boot recomenda Java 24 ou mais novo para virtual threads por causa de pinning em blocos `synchronized`, resolvido no JDK 24 (JEP 491). O pinning medido aqui, no `amqp-client`, é desse tipo.
- RabbitMQ e MongoDB num nó só, com filas clássicas.
- A memória é a do `docker stats` (uso do cgroup do container), amostrada a cada volta do polling, e não um RSS exato do processo.
- A latência da API não entra, pelo motivo explicado no método.

## Como reproduzir

Pré-requisitos: Docker Desktop (ou Docker Engine com Compose v2), Git Bash ou outro bash, e `curl`. Não precisa de JDK nem de Maven no host: as imagens são construídas dentro do Docker.

```bash
# 1. Calibração: uma rodada por modo, com sugestão de N e W para a janela medida passar de 20s
bash scripts/bench/run-bench.sh --calibrar

# 2. Protocolo oficial com a varredura de concorrência (de 40 a 60 minutos)
mkdir -p target/bench
bash scripts/bench/run-bench.sh --varredura > target/bench/execucao.log 2>&1
```

ENVs aceitas (todas opcionais): `BENCH_C` (consumidores por canal, padrão 200, no máximo 650 por causa do `channel_max` 2047 do broker), `BENCH_N` (padrão 30000), `BENCH_W` (padrão 6000), `BENCH_N_CAL` (padrão 6000), `BENCH_LATENCIA` (padrão `100ms`), `BENCH_POOL` (padrão 200) e `BENCH_REPETICOES` (padrão 5). Exemplo: `BENCH_C=100 BENCH_REPETICOES=7 bash scripts/bench/run-bench.sh`. Flags: `--sem-build` reaproveita as imagens já construídas e `--manter` deixa o projeto do benchmark no ar no fim.

Os resultados ficam em `target/bench/`, que não é versionado:

- `resumo.txt`: ambiente, parâmetros, mediana/mín/máx por modo, contagem de pinning e varredura;
- `resultados.csv`: uma linha por rodada, de todas as execuções (a coluna `execucao` separa uma da outra);
- `worker-vt.jfr` e `jfr-pinned.txt`: a gravação da rodada `vt-jfr` e os eventos de pinning;
- `k6-<rodada>.log`: a saída do k6 de cada backlog.

Durante a execução, acompanhe a drenagem no mesmo dashboard da demo, em http://localhost:3000 (row "API e JVM" para threads e memória). Para ler o JFR com um JDK 21 no host:

```bash
jfr summary target/bench/worker-vt.jfr
jfr print --events jdk.VirtualThreadPinned target/bench/worker-vt.jfr
```

O script roda num projeto compose separado (`docker compose -p notify-bench -f docker-compose.yml -f docker-compose.bench.yml`). Como as portas do host são as mesmas da demo, ele **para** a demo antes (`docker compose stop`, sem apagar volumes). No fim, `down -v` apaga só os volumes do projeto `notify-bench`. Para voltar à demo: `docker compose up -d`.

## Saída bruta

Conteúdo de `target/bench/resumo.txt` desta execução, sem edição:

```
data=2026-11-01T23:49:26Z
hardware=cpu="Intel(R) Core(TM) i7-4790 CPU @ 3.60GHz" ncpu=8 memoria_gib=7.7 docker=29.8.0 so="Docker Desktop"
jvm=openjdk version "21.0.13" 2026-10-20 LTS
parametros c=200 n=30000 w=6000 latencia=100ms pool=200 prefetch=10 repeticoes=5
virtual mediana_msgs=744.4 min_msgs=508.3 max_msgs=871.6 mediana_pico_threads=40 mediana_rss_mib=718
plataforma mediana_msgs=786.2 min_msgs=603.7 max_msgs=929.1 mediana_pico_threads=636 mediana_rss_mib=844
jfr virtual_thread_pinned=7
varredura c=50 virtual msgs=786.4 pico_threads=43 rss_mib=643
varredura c=50 plataforma msgs=776 pico_threads=186 rss_mib=643
varredura c=500 virtual msgs=807.5 pico_threads=41 rss_mib=750
varredura c=500 plataforma msgs=807.6 pico_threads=1536 rss_mib=1097
```
