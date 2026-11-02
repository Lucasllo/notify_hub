---
status: complete
phase: 04-observabilidade-demo-e-documenta-o
source: [04-VERIFICATION.md]
started: 2026-11-02T00:40:00Z
updated: 2026-11-02T13:45:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Capturar e versionar as screenshots docs/img/dashboard-k6.png e docs/img/rabbitmq-filas.png
expected: Dashboard Notify Hub durante `docker compose --profile bench run --rm k6` (CB, recusas, retries, DLQ visíveis) e aba Queues and Streams do RabbitMQ (filas de delay e DLQs); as duas imagens existem em docs/img/ e renderizam no README e no docs/demo.md
result: pass
previous_result: issue (G-04-1); resolvido pelas screenshots versionadas em 68cbb38

### 2. Dashboard no Grafana durante uma rodada do k6
expected: http://localhost:3000 abre sem login; as 5 rows renderizam com dados; state timeline do CB em verde/vermelho/amarelo; recusas circuit_open, retries por nível e DLQs crescendo aparecem; nenhum painel "No data". Conferir também "Taxa de sucesso" num stack recém-subido (pode mostrar NaN antes do primeiro desfecho, IN-03)
result: pass

### 3. Diagramas Mermaid no GitHub
expected: Depois do push, os 3 diagramas (arquitetura, topologia, stateDiagram-v2 com 16 transições) renderizam sem erro em README.md e docs/arquitetura.md
result: pass

### 4. Leitura de um minuto do topo do README como recrutador
expected: Pitch, o que o projeto demonstra e como rodar ficam claros sem rolar demais
result: pass

### 5. Afirmações do benchmark batem com o medido
expected: Toda afirmação de docs/benchmark.md e do README bate com target/bench/resumo.txt e resultados.csv (medianas 744.4/786.2 msg/s, RSS 718/844 MiB, empate coerente com a dispersão, 7 eventos JFR em basicAck); nenhuma conclusão além do medido
result: pass

### 6. Proibições de nível judgment (privacidade, transparência, garantias da documentação)
expected: Só example.com, fail.test e telefones fictícios (+5511..., +5500...) em scripts e docs; nenhuma afirmação de exactly-once / "nunca duplica" / "entrega garantida" sem a ressalva at-least-once; profile demo declarado como não pronto para produção (docs/trade-offs.md)
result: pass

### 7. (Recomendado) Clone limpo numa máquina sem cache do BuildKit
expected: `docker compose up --build` e `docker compose --profile bench run --rm k6` sobem tudo sem JDK nem passo manual, e o k6 imprime o resumo com novas=1000
result: pass

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- gap_id: G-04-1
  truth: "docs/img/dashboard-k6.png e docs/img/rabbitmq-filas.png existem, versionadas, e renderizam no README (linha 81) e no docs/demo.md (linhas 72 e 86)"
  status: resolved
  reason: "User reported: screenshots ainda não capturadas (opção 2); o teste 1 tinha sido marcado pass por engano"
  severity: major
  test: 1
  resolved_by: "68cbb38 docs(04): screenshots da demo"
  resolved_at: 2026-11-02
  root_cause: "Captura deixada de propósito para o checkpoint humano (D-10, 04-05); docs/img/ só contém .gitkeep (git ls-files) e as três referências de imagem apontam para arquivos inexistentes"
  artifacts:
    - path: "docs/img/"
      issue: "Só .gitkeep; faltam dashboard-k6.png e rabbitmq-filas.png"
    - path: "README.md:81"
      issue: "Imagem quebrada (docs/img/dashboard-k6.png)"
    - path: "docs/demo.md:72,86"
      issue: "Imagens quebradas (img/dashboard-k6.png, img/rabbitmq-filas.png)"
  missing:
    - "Com o stack no ar (docker compose up --build), rodar docker compose --profile bench run --rm k6 e capturar o dashboard Notify Hub no Grafana (CB, recusas, retries, DLQ visíveis) como docs/img/dashboard-k6.png"
    - "Capturar a aba Queues and Streams do RabbitMQ (http://localhost:15672, filas de delay e DLQs) como docs/img/rabbitmq-filas.png"
    - "Versionar as duas imagens e reexecutar /gsd-verify-work 04"
  debug_session: ""
