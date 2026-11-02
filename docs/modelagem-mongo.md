# Modelagem no MongoDB

O MongoDB é a fonte da verdade do Notify Hub. A mensagem que passa pelo RabbitMQ carrega só o id da notificação, e todo o resto (status, histórico de tentativas, conteúdo renderizado) mora num documento. Este texto explica o formato dos documentos, por que cada índice existe e mostra os planos de execução reais das consultas que a API e o worker fazem.

## Coleções

### `notifications`

Um documento por notificação. O `_id` é um UUID gerado pela API.

| Campo | Tipo | Para que serve |
|-------|------|----------------|
| `_id` | string (UUID) | Identificador devolvido no 202 e usado na mensagem AMQP |
| `channel` | string (`EMAIL`, `SMS`, `WEBHOOK`) | Canal de entrega |
| `recipient` | string | Destinatário (e-mail, telefone ou URL) |
| `templateCode` | string | Código do template na coleção `templates` |
| `variables` | objeto | Variáveis do template enviadas pelo cliente |
| `status` | string | Um dos 7 status do ciclo de vida (ver [`arquitetura.md`](arquitetura.md)) |
| `retryCount` | inteiro | Nível atual da escada de retry. É o "fence" do claim e volta a zero no reprocessamento |
| `attemptCount` | inteiro | Total de tentativas, só cresce (nunca é zerado) |
| `attempts` | array de objetos | Histórico embutido: número, nível, início, duração, resultado, código e mensagem do erro, classe da falha e thread |
| `rendered` | objeto (`subject`, `body`) | Conteúdo renderizado, gravado junto com o resultado da tentativa |
| `lastError` | objeto (`code`, `message`, `failureClass`, `at`) | Último erro, para leitura rápida sem percorrer o histórico |
| `createdAt` | data | Criação. Chave de ordenação das listagens |
| `updatedAt` | data | Última mudança. Usada pela varredura de publicações não confirmadas |
| `claimedAt` | data | Momento em que o worker reivindicou a notificação. Nulo enquanto nenhum worker pegou |
| `leaseUntil` | data | Fim do lease do claim. Depois dele, outro consumidor pode retomar uma entrega presa |
| `sentAt` | data | Momento do envio com sucesso |
| `idempotencyKey` | string, opcional | Valor do header `Idempotency-Key`. Só existe quando o cliente mandou o header |
| `requestHash` | string, opcional | SHA-256 do pedido canônico, para distinguir um replay idêntico de um corpo diferente com a mesma chave |

`idempotencyKey` e `requestHash` nunca aparecem nas respostas da API.

### `templates`

Um documento por template, com o código como `_id` (por exemplo `welcome`). Ele traz uma `version`, a lista `requiredVariables` (variáveis que o cliente precisa mandar) e o mapa `variants` com uma variante por canal (`EMAIL`, `SMS`, `WEBHOOK`), cada uma com `subject` e `body` em Mustache. O worker semeia os templates no startup a partir de um arquivo versionado e só sobrescreve um documento quando a versão do arquivo é maior. Como as consultas são sempre pelo `_id`, a coleção não precisa de índice além do padrão.

## Por que as tentativas ficam embutidas

O histórico de tentativas fica num array dentro do próprio documento, e não numa coleção separada:

- **Atomicidade sem transação.** O resultado de uma tentativa é um único update: `$set` do status novo (e de `lastError`, `rendered`, `sentAt`) mais `$push` da tentativa em `attempts`, com filtro no status de origem. Ou tudo é gravado, ou nada. Com uma coleção de tentativas separada, seria preciso uma transação multi-documento ou aceitar um histórico que pode divergir do status.
- **Tamanho limitado.** A escada tem três níveis, então o fluxo automático gera no máximo quatro tentativas por notificação. Cada reprocessamento acrescenta um evento e mais uma rodada de tentativas, mas isso depende de ação administrativa. O documento fica muito longe do limite de 16 MB.
- **Leitura num documento só.** `GET /notifications/{id}` devolve o status e o histórico completo com uma leitura pelo `_id`, sem junção. As listagens usam projeção e deixam `attempts`, `variables` e `rendered` de fora.

## Índices

Os índices são criados explicitamente e de forma idempotente no startup da API e do worker, com nomes estáveis. A ordem das chaves segue a regra **ESR** (Equality, Sort, Range): primeiro os campos comparados por igualdade, depois os de ordenação, por último os de intervalo. Assim o índice já entrega os documentos na ordem pedida e o MongoDB não precisa ordenar em memória.

| Nome | Chaves | Consulta atendida | Justificativa ESR |
|------|--------|-------------------|-------------------|
| `status_channel_createdAt` | `status: 1, channel: 1, createdAt: -1, _id: -1` | `GET /notifications?status=X&channel=Y` e o `count` da paginação com o mesmo filtro | E: `status` e `channel`. S: `createdAt` desc e `_id` desc, a mesma ordem da listagem |
| `channel_createdAt` | `channel: 1, createdAt: -1, _id: -1` | `GET /notifications?channel=Y` | E: `channel`. S: `createdAt`, `_id`. O índice anterior não serve aqui, porque começa por `status` |
| `createdAt` | `createdAt: -1, _id: -1` | `GET /notifications` sem filtro | Sem E. S: `createdAt`, `_id` |
| `status_createdAt` | `status: 1, createdAt: -1, _id: -1` | `GET /notifications?status=X`, lote do `republish-failed` e prefixo da varredura de PENDING | E: `status`. S: `createdAt`, `_id`. O `status_channel_createdAt` não serve sem igualdade em `channel`: entre `status` e `createdAt` haveria um campo sem filtro, e a ordenação viraria SORT em memória |
| `idempotencyKey_unique` | `idempotencyKey: 1`, único e parcial (`{idempotencyKey: {$exists: true}}`) | Replay de um POST com `Idempotency-Key` | E: `idempotencyKey`. A unicidade resolve a corrida entre dois POSTs com a mesma chave: o segundo insert recebe erro de chave duplicada e vira replay |

Detalhes que valem para mais de um índice:

- **`_id` como desempate.** Muitas notificações têm o mesmo `createdAt` (o k6 cria dezenas por milissegundo). Sem um desempate, a ordem entre elas não é estável e uma página poderia repetir ou pular documentos. Com `_id` desc no fim, a ordenação `{createdAt: -1, _id: -1}` é total e a paginação é estável.
- **`status_createdAt` percorrido ao contrário.** O lote do `POST /admin/notifications/republish-failed` lê as PUBLISH_FAILED da mais antiga para a mais nova (`{createdAt: 1, _id: 1}`). Essa é exatamente a ordem inversa do índice, e o MongoDB percorre um índice nos dois sentidos. O plano real registra `direction: "backward"` nesse IXSCAN e `forward` na listagem por status. Um índice só serve as duas consultas.
- **Índice parcial para a chave de idempotência.** A maioria das notificações não tem `Idempotency-Key`. Num índice único comum, todos esses documentos entrariam com valor nulo e disputariam a unicidade entre si. O filtro parcial `{idempotencyKey: {$exists: true}}` deixa de fora quem não tem chave: o índice fica pequeno e a unicidade vale só onde a chave existe. A API nunca grava `idempotencyKey: null`, ela simplesmente omite o campo.

## Planos reais (explain)

Os planos abaixo saíram de `scripts/explain-indices.js`, rodado contra o MongoDB 8.0.32 do `docker compose` depois de rodadas do k6 e de um reprocessamento da DLQ do webhook, com 3229 documentos na coleção. O script roda `explain("executionStats")` nas mesmas consultas do código e imprime uma linha por consulta: o índice escolhido, os estágios do plano vencedor (de fora para dentro), as chaves de índice examinadas, os documentos lidos, os documentos devolvidos e se houve SORT em memória.

### Listagem por status e canal

```
EXPLAIN {"consulta":"listagem status+canal","indice":"status_channel_createdAt","estagios":"LIMIT > FETCH > IXSCAN","chavesExaminadas":20,"docsExaminados":20,"retornados":20,"sortEmMemoria":false}
```

`{status: "SENT", channel: "EMAIL"}` ordenado por `{createdAt: -1, _id: -1}` com `limit(20)`. O IXSCAN em `status_channel_createdAt` já entrega as entradas na ordem certa, então o MongoDB lê 20 chaves, busca 20 documentos e para. Chaves examinadas igual a documentos devolvidos é o melhor caso possível: nenhum documento lido à toa e nenhum estágio SORT.

### Listagem por canal

```
EXPLAIN {"consulta":"listagem canal","indice":"channel_createdAt","estagios":"LIMIT > FETCH > IXSCAN","chavesExaminadas":20,"docsExaminados":20,"retornados":20,"sortEmMemoria":false}
```

`{channel: "SMS"}` com a mesma ordenação. O planejador escolhe `channel_createdAt` e, de novo, 20 chaves para 20 documentos.

### Listagem sem filtro

```
EXPLAIN {"consulta":"listagem sem filtro","indice":"createdAt","estagios":"LIMIT > FETCH > IXSCAN","chavesExaminadas":20,"docsExaminados":20,"retornados":20,"sortEmMemoria":false}
```

Sem filtro, a ordenação sozinha escolhe o índice `createdAt`. Sem ele, a primeira página seria um COLLSCAN seguido de um SORT da coleção inteira.

### Listagem por status

```
EXPLAIN {"consulta":"listagem status","indice":"status_createdAt","estagios":"LIMIT > FETCH > IXSCAN","chavesExaminadas":20,"docsExaminados":20,"retornados":20,"sortEmMemoria":false}
```

`{status: "SENT"}` usa `status_createdAt`, e não `status_channel_createdAt`. É o caso que justifica o quinto índice: sem igualdade em `channel`, o índice de três campos não entregaria a ordem por `createdAt`.

### Lote do republish-failed

```
EXPLAIN {"consulta":"lote republish-failed","indice":"status_createdAt","estagios":"LIMIT > FETCH > IXSCAN","chavesExaminadas":8,"docsExaminados":8,"retornados":8,"sortEmMemoria":false}
```

`{status: "PUBLISH_FAILED"}` ordenado por `{createdAt: 1, _id: 1}` com `limit(100)`. Havia 8 documentos PUBLISH_FAILED na coleção, e o MongoDB examinou exatamente 8 chaves, percorrendo `status_createdAt` de trás para frente. Sem SORT, mesmo com a ordem oposta à do índice.

### Varredura de PENDING sem confirm

```
EXPLAIN {"consulta":"varredura PENDING sem confirm","indice":"status_createdAt","estagios":"FETCH > IXSCAN","chavesExaminadas":0,"docsExaminados":0,"retornados":0,"sortEmMemoria":false}
```

`{status: "PENDING", updatedAt: {$lt: agora - 2min}, claimedAt: null}`. O índice usado é `status_createdAt`, só pelo prefixo `status`. `updatedAt` e `claimedAt` são filtrados no FETCH. No momento da captura não havia nenhuma notificação PENDING, então nenhuma chave foi examinada. A seção abaixo explica por que essa consulta não tem índice próprio.

### Busca por Idempotency-Key

```
EXPLAIN {"consulta":"busca idempotencyKey","indice":"idempotencyKey_unique","estagios":"EXPRESS_IXSCAN","chavesExaminadas":1,"docsExaminados":1,"retornados":1,"sortEmMemoria":false}
```

`{idempotencyKey: "<chave>"}` com a chave de uma notificação real criada com o header. `EXPRESS_IXSCAN` é o caminho rápido do MongoDB 8.0 para igualdade num índice único: ele pula o planejador e faz uma única busca no índice. Uma chave examinada, um documento devolvido.

### Count da paginação

```
EXPLAIN {"consulta":"count da paginação","indice":"status_channel_createdAt","estagios":"COUNT > COUNT_SCAN","chavesExaminadas":821,"docsExaminados":0,"retornados":0,"sortEmMemoria":false}
```

A listagem devolve `totalElements`, calculado com `count` do mesmo filtro. `COUNT_SCAN` conta direto nas entradas do índice: 821 chaves (todas as SENT de e-mail) e **zero documentos lidos**. O custo cresce com o tamanho do resultado do filtro, mas nunca toca a coleção. `retornados` é 0 porque um `count` não devolve documentos.

### Estatísticas (`$group`)

```
EXPLAIN {"consulta":"stats $group","indice":null,"estagios":"GROUP > COLLSCAN","chavesExaminadas":0,"docsExaminados":3229,"retornados":11,"sortEmMemoria":false}
```

O `GET /notifications/stats` agrupa a coleção por status e canal. Não há índice no plano: o MongoDB lê os 3229 documentos (COLLSCAN) e devolve 11 grupos, as combinações de status e canal presentes no momento.

### O que esses planos provam

- Todas as consultas de listagem, o lote do republish e a busca por chave usam o índice esperado, com chaves examinadas iguais aos documentos devolvidos.
- Nenhuma consulta tem estágio SORT. A ordenação sempre vem do índice, inclusive na ordem inversa.
- O count da paginação não lê documentos.
- Só o `$group` do `/stats` varre a coleção, e isso é uma escolha consciente.

## O que ficou sem índice dedicado e por quê

**A varredura de PENDING sem confirm.** A consulta filtra por `status`, `updatedAt` e `claimedAt`, e um índice `{status: 1, updatedAt: 1}` atenderia o intervalo de `updatedAt` direto no índice. Ele não existe de propósito. PENDING é um status transitório: uma notificação fica PENDING só entre ser aceita e ser reivindicada pelo worker, normalmente milissegundos. O número de documentos PENDING em qualquer momento é pequeno, e a varredura usa o prefixo `status` de `status_createdAt` para chegar só neles. As chaves examinadas na varredura são iguais ao número de documentos PENDING (zero na captura acima, com o worker em dia). Um índice a mais custaria uma escrita extra em cada insert e em cada mudança de status de todas as notificações para acelerar uma consulta que já toca poucos documentos e roda a cada 30 segundos.

**O `/stats`.** Um `$group` sobre a coleção inteira não tem índice que o transforme em leitura parcial: ele precisa contar todos os documentos. O COLLSCAN acima é o custo aceito, registrado como "stats sem cache" em [`trade-offs.md`](trade-offs.md). Numa coleção grande, a saída seria manter contadores materializados (atualizados junto com cada mudança de status) ou guardar o resultado em cache por alguns segundos.

## Como reproduzir

Com o stack no ar (`docker compose up --build`), rode a demo do k6 e, se quiser números parecidos com os acima, um reprocessamento da DLQ:

```bash
docker compose --profile bench run --rm k6
curl -X POST -H "X-API-Key: demo-admin-key" "http://localhost:8080/admin/dlq/webhook/reprocess?max=1000"
```

Depois, na raiz do repositório:

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval "$(cat scripts/explain-indices.js)"
```

O comando é para bash (no Windows, o Git Bash), e o `MSYS_NO_PATHCONV=1` só tem efeito no Git Bash. O script é só leitura: `explain()` e `find()` não alteram dados nem índices. Os números (chaves examinadas no count, documentos no COLLSCAN, quantas PUBLISH_FAILED existem, se há alguma notificação com `Idempotency-Key`) mudam com o volume e o conteúdo da coleção no momento. A escolha dos índices e a ausência de SORT não mudam.
