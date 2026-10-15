## Deferred Items

- Javadoc de `RecipientRules` cita o SMS mágico `+5500000000001` (14 caracteres), que não é mais um destinatário mágico
  status: open
  **What:** `notify-common/src/main/java/dev/notifyhub/common/domain/RecipientRules.java` (javadoc da classe) usa um exemplo antigo; o contrato fechado no plano 02-04 é `+550000000001` (13 caracteres, ver `MagicRecipients`). Só documentação; `isValid` aceita os dois. Fora do escopo do 02-04 (que só altera `notify-worker`).
  **Found during:** 02-04 Task 2
