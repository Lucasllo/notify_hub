# Trade-offs e limitações conhecidas

O Notify Hub é um projeto de portfólio feito em cerca de uma semana, para rodar como demo local com `docker compose up`. Várias escolhas abaixo são conscientes: resolvem o problema na escala de uma demo e deixam claro o que mudaria num serviço de produção. Nada aqui é escondido. O profile `demo`, em particular, **não está pronto para produção**: ele tem credenciais fixas, destinatários mágicos que forçam falhas e nenhuma autenticação na API pública.

| Limitação | Impacto | O que faria em produção |
|-----------|---------|-------------------------|
| Sem outbox transacional | Uma queda entre gravar no MongoDB e publicar no broker deixa a notificação PENDING sem mensagem. A varredura a marca como PUBLISH_FAILED depois de 2 minutos e ela precisa de republicação | Outbox transacional no MongoDB com um relay que publica e marca como enviado |
| Entrega at-least-once | Um crash depois do envio ao provedor e antes de gravar SENT faz a notificação ser enviada de novo | Chave de idempotência repassada ao provedor e deduplicação no destino |
| Spring Boot 3.5 fora do suporte OSS | Sem patches de segurança gratuitos desde 30/06/2026 | Migrar para Spring Boot 4.x |
| Backoff sem jitter | Os retries de um lote que falhou junto voltam juntos, nos mesmos instantes | Jitter no atraso, com mais filas por nível ou um agendador próprio |
| Sondagem de `Idempotency-Key` | Quem chuta chaves descobre, pela diferença entre 202 e 422, se uma chave já foi usada, e o replay devolve id e status | Chaves com escopo por cliente autenticado |
| `recipient` na listagem | `GET /notifications` devolve destinatários (dado pessoal) sem autenticação | Autenticação, autorização por cliente e mascaramento do destinatário |
| `/notifications/stats` sem cache | Cada chamada faz um `$group` que varre a coleção inteira; o custo cresce com o volume | Contadores materializados ou cache com expiração curta |
| API pública sem limite de taxa | Um cliente pode inundar a API, o MongoDB e o broker | Rate limit por cliente no gateway ou na própria API |
| Credenciais da demo versionadas | `demo-admin-key`, Grafana `admin/admin` e RabbitMQ `notify/notify` estão no repositório | Segredos em cofre ou variáveis de ambiente, nunca no código |
| Cópia extra no replay de PENDING não reivindicado | Um replay com a mesma `Idempotency-Key` pode publicar uma segunda mensagem para uma notificação que já tinha a sua na fila. O worker descarta a cópia, mas ela é processada a mais | Republicar no replay só a partir da outbox, nunca direto |
| Log do replay com status desatualizado | Em uma corrida, o log do replay registra "PENDING" enquanto o documento ainda está PUBLISH_FAILED | Logar o status relido do documento depois da gravação |
| Recusa do circuit breaker consome um nível | Durante uma abertura longa, notificações válidas esgotam a escada e terminam DEAD_LETTERED | Devolver a mensagem para o mesmo nível sem contar tentativa, com teto próprio |

### Sem outbox transacional

A API grava a notificação como PENDING e depois publica a mensagem. São duas escritas em sistemas diferentes, sem transação entre elas. Se a API cair entre uma e outra, ou se o broker não confirmar, o documento fica sem mensagem. A escolha foi não construir uma outbox no prazo do projeto e cobrir a lacuna de três jeitos: o 202 só sai com confirm do broker (sem confirm, o cliente recebe 503 e o documento vira PUBLISH_FAILED); uma varredura agendada marca como PUBLISH_FAILED todo documento PENDING sem claim do worker e com mais de 2 minutos; e `POST /admin/notifications/republish-failed` republica esses documentos. Um replay do POST com a mesma `Idempotency-Key` também republica. O risco aceito é a recuperação depender de uma ação administrativa, e não acontecer sozinha.

### Entrega at-least-once

O worker reivindica cada notificação com um update condicional no MongoDB antes de enviar. Uma mensagem duplicada ou reentregue não casa com o filtro e é descartada sem envio, o que cobre as reentregas comuns do RabbitMQ. O que esse claim não cobre é um crash do worker depois de o provedor aceitar o envio e antes de o resultado SENT ser gravado. A mensagem volta, o lease vence e a notificação é enviada outra vez. Exactly-once de ponta a ponta exigiria a colaboração do provedor, que aqui é simulado. A garantia documentada é at-least-once, com duplicata possível só nesse cenário.

### Spring Boot 3.5 fora do suporte OSS

O projeto usa Spring Boot 3.5.16, o último patch gratuito da linha 3.x. O suporte OSS da 3.5 terminou em 30/06/2026 e hoje só existe suporte comercial. A escolha veio da restrição de usar Spring Boot 3.x, com a versão mais nova dela. Não há vulnerabilidade conhecida que afete a demo, mas novas correções não vão sair de graça. Spring Boot 4 muda o baseline (Spring Framework 7, Testcontainers 2.x e springdoc 3.x), então a migração é um trabalho próprio, não uma troca de versão.

### Backoff sem jitter

Cada nível da escada é uma fila com TTL fixo (5s, 25s e 125s por padrão). Todas as mensagens que falharam no mesmo instante voltam no mesmo instante, o que pode sincronizar picos contra um provedor que já está com problemas. Filas com TTL fixo foram escolhidas porque são nativas do RabbitMQ, visíveis no painel e não sofrem bloqueio de cabeça de fila. Jitter exigiria várias filas por nível ou um agendador fora do broker. O risco é menor nesta demo, porque o circuit breaker por canal já limita a pressão sobre um provedor com falha.

### Sondagem de `Idempotency-Key`

Um POST com uma chave já usada e o mesmo corpo devolve 202 com o id e o status da notificação original. Com outro corpo, devolve 422. Como a API não tem autenticação, as chaves não têm escopo por cliente, e qualquer um que adivinhe uma chave descobre que ela existe e, com o mesmo corpo, vê o id e o status. O risco foi aceito porque chaves aleatórias (UUIDs, por exemplo) são impraticáveis de adivinhar e a demo não tem clientes reais. O 422 não ecoa a chave nem o id.

### `recipient` na listagem

`GET /notifications` e `GET /notifications/{id}` devolvem o destinatário de cada notificação, sem autenticação. Numa base real isso permitiria enumerar e-mails e telefones. Na demo os destinatários são fictícios (`example.com`, domínios `.test` e telefones `+55 00…`) e a API só escuta em `127.0.0.1`. A chave de idempotência e o hash do pedido nunca aparecem nas respostas.

### `/notifications/stats` sem cache

O endpoint de estatísticas faz um único `$group` por status e canal, e o MongoDB atende essa agregação com uma varredura completa da coleção (COLLSCAN, ver [`modelagem-mongo.md`](modelagem-mongo.md)). Com alguns milhares de documentos a resposta é instantânea. Com milhões, cada chamada seria cara, e o endpoint é público. Para a demo, a precisão imediata (o k6 usa o `/stats` para fechar o resumo) vale mais que o custo.

### API pública sem limite de taxa

`POST /notifications` e as consultas não têm rate limit. Um cliente pode criar notificações sem parar, encher o MongoDB e o broker e, em replays com `Idempotency-Key`, manter requisições esperando a publicação anterior terminar. A demo roda em loopback e o k6 é o único gerador de carga. Os endpoints `/admin` têm chave e um teto de itens por chamada (`max` até 1000).

### Credenciais da demo versionadas

A chave administrativa `demo-admin-key` existe só no profile `demo`. Fora dele, a chave vem da variável de ambiente `NOTIFY_ADMIN_API_KEY` e, se estiver vazia, todos os endpoints `/admin` recusam o acesso. O Grafana usa `admin/admin` (o acesso anônimo é só de leitura) e o RabbitMQ usa `notify/notify`. Todas as portas do compose ficam presas a `127.0.0.1`, então nada disso é alcançável de fora da máquina. Elas estão no repositório de propósito, para a demo subir sem nenhum passo manual. Nenhum segredo real existe no projeto.

### Cópia extra no replay de PENDING não reivindicado

Quando um replay com `Idempotency-Key` encontra a notificação ainda PENDING e a janela de espera vence, a API republica a mensagem para garantir que ela chegue ao worker. Se a publicação original já tinha sido confirmada e só estava esperando na fila, passam a existir duas mensagens para a mesma notificação. O claim do worker garante que só uma delas gere envio: a segunda não casa com o filtro e é descartada. O custo é uma mensagem processada a mais. Foi aceito porque a alternativa, não republicar, poderia deixar uma notificação sem mensagem nenhuma.

### Log do replay com status desatualizado

Numa corrida entre o replay e a republicação, a linha de log do replay pode registrar o status como "PENDING" enquanto o documento relido ainda está PUBLISH_FAILED. É só observabilidade: o estado gravado e a resposta HTTP estão corretos. Ficou registrado aqui para quem for depurar pelos logs não confiar nessa linha isolada.

### Recusa do circuit breaker consome um nível

Quando o circuit breaker de um canal está aberto, ou o bulkhead está cheio, a tentativa é recusada antes de chegar ao provedor. Essa recusa conta como falha transitória e consome um nível da escada, como qualquer outra. A escolha mantém um único caminho de retry, sem estado extra, e garante que a mensagem nunca fique presa num laço de recusas. O efeito colateral aparece na demo: notificações válidas do webhook que pegam o circuito aberto nas quatro tentativas terminam DEAD_LETTERED. O reprocessamento da DLQ as recupera, e o tempo de circuito aberto é limitado ao primeiro nível da escada para reduzir esse caso.

## Próximos passos

- **Outbox transacional.** Gravar a notificação e o evento na mesma escrita do MongoDB e publicar por um relay, eliminando a escrita dupla, a varredura de PENDING e a cópia extra do replay.
- **Migração para Spring Boot 4.** Voltar ao suporte OSS, com Spring Framework 7, Testcontainers 2.x e springdoc 3.x.
- **Jitter no backoff.** Espalhar os retries de um mesmo lote para não sincronizar picos contra o provedor.
- **Correlation ID.** Propagar um identificador de correlação do HTTP para os headers AMQP e para os logs da API e do worker, para seguir uma notificação de ponta a ponta.
