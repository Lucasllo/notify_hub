---
status: testing
phase: 01-entrega-ponta-a-ponta-no-caminho-feliz
source: [01-VERIFICATION.md]
started: 2026-10-13T00:52:50Z
updated: 2026-10-13T00:52:50Z
---

## Current Test

number: 1
name: Painel do RabbitMQ mostra a topologia completa
expected: |
  Com API e worker no ar, em http://localhost:15672 (notify/notify): notify.exchange (topic), notify.retry e notify.dlx (direct); por canal (email, sms, webhook) a fila principal com DLX, as filas retry.5s/25s/125s com TTL e a DLQ; notify.email.dlq com as mensagens rejeitadas pelo smoke
awaiting: user response

## Tests

### 1. Painel do RabbitMQ mostra a topologia completa
expected: Em http://localhost:15672 (notify/notify), abas Exchanges e Queues: notify.exchange (topic), notify.retry e notify.dlx (direct); por canal a fila principal com DLX, retry.5s/25s/125s com TTL e a DLQ; notify.email.dlq com mensagens rejeitadas pelo smoke
result: [pending]

### 2. GET de uma notificação EMAIL entregue no navegador
expected: Com a API no ar, GET http://localhost:8080/notifications/{id} (ex.: e37a6216-7353-4c1c-9c70-e04f2078d380) retorna JSON com status SENT, rendered.subject/body em pt-BR e attempts[0] com startedAt, durationMs e thread VirtualThread
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps
