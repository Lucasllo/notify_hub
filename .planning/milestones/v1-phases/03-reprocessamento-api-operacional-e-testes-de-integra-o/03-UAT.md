---
status: complete
phase: 03-reprocessamento-api-operacional-e-testes-de-integra-o
source: [03-VERIFICATION.md]
started: 2026-11-01T01:13:53Z
updated: 2026-11-01T16:47:17Z
---

## Current Test

[testing complete]

## Tests

### 1. Swagger UI e botão Authorize (perfil demo)
Com a infra no ar e a API com `--spring.profiles.active=demo`, abrir http://localhost:8080/swagger-ui/index.html, clicar em Authorize, informar `demo-admin-key` e executar `POST /admin/dlq/{canal}/reprocess` com canal=email e max=1.
expected: Três tags (Notificações, Consultas, Administração) com resumos em pt-BR; cadeado só nas duas operações admin; 200 com {requested, reprocessed, skipped, discarded} com a chave e 401 Problem Details sem ela
result: pass

### 2. Smokes das fases 1 e 2
Rodar `scripts/smoke-fase1.sh` e `scripts/smoke-fase2.sh`, que sobem os `-exec.jar`.
expected: Terminam com `SMOKE FASE 1: OK` e `SMOKE FASE 2: OK`
result: pass

### 3. Backstop: API morta no meio de um reprocess
Matar a JVM da API (`kill -9`) no meio de um `POST /admin/dlq/{canal}/reprocess`.
expected: Nenhuma mensagem perdida; documentos resetados retomados pelo próximo reprocess ou pela varredura
result: pass

### 4. Resolver as 15 proibições judgment-tier (planos 03-01..03-10)
Para cada proibição da tabela "Proibições" do 03-VERIFICATION.md, aceitar o veredito (não autoritativo) do verificador ou registrar a decisão. Atenção especial a três com ressalva: 03-09 #2 e 03-04 #1 (WR-05: cópia extra na fila em replay de PENDING confirmado e ainda não reivindicado) e 03-10 #1 (WR-07: replay responde e loga "status PENDING" com o documento ainda PUBLISH_FAILED).
expected: Cada proibição aceita ou com decisão registrada
result: pass

## Summary

total: 4
passed: 4
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
