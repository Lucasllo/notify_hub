## Deferred Items

- Javadoc de `RecipientRules` cita o SMS mágico `+5500000000001` (14 caracteres), que não é mais um destinatário mágico
  status: open
  **What:** `notify-common/src/main/java/dev/notifyhub/common/domain/RecipientRules.java` (javadoc da classe) usa um exemplo antigo; o contrato fechado no plano 02-04 é `+550000000001` (13 caracteres, ver `MagicRecipients`). Só documentação; `isValid` aceita os dois. Fora do escopo do 02-04 (que só altera `notify-worker`).
  **Found during:** 02-04 Task 2

- CR-01: falha do Mongo no claim ou no update de resultado (e rejeições antes do claim) deixa o documento não terminal com a mensagem na DLQ
  status: open
  target: Phase 3
  **What:** Nenhum mecanismo da Fase 2 recupera esses documentos:
  - claim que falha (`DeliveryProcessor`, `NotificationStateService.claim` lançando `DataAccessException`) deixa o documento em PENDING/RETRYING e a mensagem na DLQ sem `x-failure-class`;
  - falha em `markSent`/`markRetrying`/`markDeadLettered`/`markFailed` deixa o documento em PROCESSING com lease de 5m e a mensagem na DLQ (no `markSent`, o envio já aconteceu);
  - rejeições antes do claim (header `notify-retry-count` inválido, canal divergente) deixam o documento em PENDING com a mensagem na DLQ;
  - a tabela `NotificationStatus` só permite o reset FAILED/DEAD_LETTERED → PENDING, e o ADM-01 "para na primeira falha": uma dessas mensagens na cabeça da DLQ trava o reprocessamento do canal inteiro.

  **Entrada obrigatória do planejamento da Fase 3:** o reprocessamento (ADM-01/ADM-02) precisa aceitar e tratar documentos PENDING, RETRYING e PROCESSING (com lease vencido) cuja mensagem está na DLQ, ou é preciso criar um sweeper de lease vencido (WR-08), sem travar na cabeça da DLQ. Referências: `DeliveryProcessor` (claim e updates de resultado), `NotificationStateService.claim` e `NotificationStatus` (tabela de transições). O Javadoc do `DeliveryProcessor` já não promete recuperação automática (02-08).
  **Found during:** 02-REVIEW (CR-01) e 02-VERIFICATION (escalation gate); adiado pelo usuário na gap closure 02-08.
