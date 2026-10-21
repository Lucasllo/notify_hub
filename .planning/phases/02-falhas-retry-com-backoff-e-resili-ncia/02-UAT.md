---
status: complete
phase: 02-falhas-retry-com-backoff-e-resili-ncia
source: [02-VERIFICATION.md]
started: 2026-10-20T23:50:43Z
updated: 2026-10-21T00:14:15Z
---

## Current Test

[testing complete]

## Tests

### 1. Smoke da Fase 2 com Docker e painel do RabbitMQ
expected: Com a infra no ar, `bash scripts/smoke-fase2.sh --sem-build`: durante o critério 1, em http://localhost:15672 (notify/notify) → Queues, notify.email.retry.1s, .5s e .25s recebem a mensagem em sequência e, ao final, notify.email.dlq tem a mensagem com x-failure-class (aba Get messages); o smoke termina com SMOKE FASE 2: OK
result: pass

### 2. Legibilidade do GET e do scrape do Prometheus
expected: http://localhost:8080/notifications/<id> de uma always-transient mostra 4 tentativas legíveis (nível, código, classe); http://localhost:8081/actuator/prometheus mostra notify_delivery_* por canal e resilience4j_circuitbreaker_state
result: pass

### 3. Queda do worker nas janelas da escada
expected: Derrubando o worker entre o confirm do token h+1 e o ack, a original reentregue é descartada sem nova tentativa; derrubando antes do publish, a mensagem ainda é republicada; nunca fica RETRYING sem token (ver behavior_unverified_items em 02-VERIFICATION.md e .planning/WINDOWS.md)
result: pass

## Summary

total: 3
passed: 3
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
