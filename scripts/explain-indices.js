// Planos de execução reais das consultas da coleção notifications.
//
// O que faz: roda explain("executionStats") nas mesmas consultas que a API e o worker fazem
// (listagens paginadas, lote do republish-failed, varredura de PENDING sem confirm, busca por
// Idempotency-Key, count da paginação e o $group do /notifications/stats) e imprime uma linha
// por consulta:
//   EXPLAIN {"consulta":...,"indice":...,"estagios":...,"chavesExaminadas":...,
//            "docsExaminados":...,"retornados":...,"sortEmMemoria":...}
//
// Como rodar (com o stack do docker compose no ar, a partir da raiz do repositório):
//   MSYS_NO_PATHCONV=1 docker compose exec -T mongo mongosh notify --quiet --eval "$(cat scripts/explain-indices.js)"
// O comando é para bash (no Windows, o Git Bash). O MSYS_NO_PATHCONV=1 só tem efeito no Git Bash e
// é inofensivo no bash do Linux e do macOS.
//
// Rode depois do k6 (docker compose --profile bench run --rm k6) e de um reprocessamento da DLQ,
// para a coleção ter volume e vários status. Os números mudam com o tamanho da coleção.
//
// Só leitura: explain() e find() não alteram dados nem índices.

const col = db.getCollection('notifications');
const ORDEM_LISTAGEM = { createdAt: -1, _id: -1 };

// Percorre o plano vencedor e devolve os estágios em ordem (de fora para dentro) e o primeiro
// indexName encontrado. Cobre find (queryPlan/inputStage/inputStages) e aggregate (stages com
// $cursor e estágios de pipeline como $group).
function percorrerPlano(no, estagios, estado) {
  if (no === null || typeof no !== 'object') return;
  if (Array.isArray(no)) {
    no.forEach((n) => percorrerPlano(n, estagios, estado));
    return;
  }
  if (typeof no.stage === 'string') estagios.push(no.stage);
  if (estado.indice === null && typeof no.indexName === 'string') estado.indice = no.indexName;
  for (const chave of ['queryPlan', 'inputStage', 'inputStages']) {
    if (chave in no) percorrerPlano(no[chave], estagios, estado);
  }
}

function planoVencedor(ex) {
  if (ex.queryPlanner && ex.queryPlanner.winningPlan) return ex.queryPlanner.winningPlan;
  if (Array.isArray(ex.stages) && ex.stages.length > 0) {
    const cursor = ex.stages[0].$cursor;
    if (cursor && cursor.queryPlanner) return cursor.queryPlanner.winningPlan;
  }
  return null;
}

function estatisticas(ex) {
  if (ex.executionStats) return ex.executionStats;
  if (Array.isArray(ex.stages) && ex.stages.length > 0) {
    const cursor = ex.stages[0].$cursor;
    if (cursor && cursor.executionStats) return cursor.executionStats;
  }
  return {};
}

// Estágios de pipeline do aggregate (ex.: $group) vêm depois do $cursor, fora do winningPlan.
function estagiosDoPipeline(ex) {
  if (!Array.isArray(ex.stages)) return [];
  return ex.stages.slice(1).map((s) => Object.keys(s)[0].replace(/^\$/, '').toUpperCase());
}

function imprimir(consulta, ex) {
  const estagios = [];
  const estado = { indice: null };
  // No aggregate com pipeline simples o servidor pode empurrar o $group para o plano (GROUP).
  percorrerPlano(planoVencedor(ex), estagios, estado);
  const pipeline = estagiosDoPipeline(ex);
  const todos = pipeline.reverse().concat(estagios);
  const st = estatisticas(ex);
  const valor = (v) => (v === undefined || v === null ? null : Number(v));
  const linha = {
    consulta,
    indice: estado.indice,
    estagios: todos.join(' > '),
    chavesExaminadas: valor(st.totalKeysExamined),
    docsExaminados: valor(st.totalDocsExamined),
    retornados: valor(st.nReturned),
    sortEmMemoria: todos.includes('SORT'),
  };
  print('EXPLAIN ' + JSON.stringify(linha));
}

function explicarFind(consulta, filtro, ordem, limite) {
  const ex = col.find(filtro).sort(ordem).limit(limite).explain('executionStats');
  imprimir(consulta, ex);
}

// Listagem GET /notifications: find(filtro).sort({createdAt:-1,_id:-1}).skip(page*size).limit(size)
explicarFind('listagem status+canal', { status: 'SENT', channel: 'EMAIL' }, ORDEM_LISTAGEM, 20);
explicarFind('listagem canal', { channel: 'SMS' }, ORDEM_LISTAGEM, 20);
explicarFind('listagem sem filtro', {}, ORDEM_LISTAGEM, 20);
explicarFind('listagem status', { status: 'SENT' }, ORDEM_LISTAGEM, 20);

// Lote do POST /admin/notifications/republish-failed: mais antigas primeiro, até max (padrão 100)
explicarFind('lote republish-failed', { status: 'PUBLISH_FAILED' }, { createdAt: 1, _id: 1 }, 100);

// Varredura agendada da API: PENDING sem claim com updatedAt mais velho que o corte (padrão 2 minutos)
imprimir(
  'varredura PENDING sem confirm',
  col
    .find({ status: 'PENDING', updatedAt: { $lt: new Date(Date.now() - 120000) }, claimedAt: null })
    .explain('executionStats'),
);

// Replay com Idempotency-Key: busca pela chave (usa uma chave real se existir)
const comChave = col.findOne({ idempotencyKey: { $exists: true } }, { idempotencyKey: 1 });
const chave = comChave ? comChave.idempotencyKey : 'chave-de-exemplo';
imprimir('busca idempotencyKey', col.find({ idempotencyKey: chave }).explain('executionStats'));

// Total da paginação: count(filtro) com o mesmo filtro da listagem
imprimir(
  'count da paginação',
  col.explain('executionStats').count({ status: 'SENT', channel: 'EMAIL' }),
);

// GET /notifications/stats: um único $group por status + canal
imprimir(
  'stats $group',
  col
    .explain('executionStats')
    .aggregate([{ $group: { _id: { status: '$status', channel: '$channel' }, count: { $sum: 1 } } }]),
);
