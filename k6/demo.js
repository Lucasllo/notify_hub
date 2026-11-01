// Demo de carga do Notify Hub (DEM-02).
//
// Objetivo: disparar exatamente 1000 POST /notifications (50 VUs dividindo 1000 iterações) com um
// mix determinístico dos 3 canais, dos 3 templates do seed e de destinatários mágicos, para que o
// dashboard do Grafana mostre o circuit breaker do webhook abrindo, as filas de delay enchendo e as
// DLQs crescendo. O mix é decidido pelo índice global da iteração, então toda rodada tem a mesma
// distribuição.
//
// Uso (com o stack no ar, a partir da raiz do repositório):
//   docker compose --profile bench run --rm k6
// O caminho do script fica no "command" do docker-compose.yml: digitado no Git Bash, /scripts/demo.js
// seria convertido para um caminho do Windows.
//
// O que é impresso no fim:
//   - o resumo padrão do k6 (latência, taxa de erro, checks e thresholds);
//   - "==== RESUMO NOTIFY HUB (k6) ====", com as notificações novas da rodada, os não terminais
//     (PENDING, PROCESSING e RETRYING) e, por status e por canal, os desfechos terminais da rodada.
//     As contagens são diferenças contra a linha de base lida no setup(), porque o volume do Mongo
//     persiste entre um "up" e outro e o GET /notifications/stats é global.
// O k6 sai com código diferente de 0 se um threshold falhar (mais de 1% de erro HTTP ou menos de 99%
// de checks 202) ou se a rodada não terminar de drenar em 3 minutos ("RESUMO INCOMPLETO").
//
// Destinatários: só domínios reservados (example.com, fail.test) e telefones fictícios
// (+55119... e os códigos mágicos +5500...). Nenhum envio sai da máquina: os adapters são simulados.

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const API = __ENV.API_URL || 'http://api:8080';
const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };

const CHANNELS = ['EMAIL', 'SMS', 'WEBHOOK'];
const NON_TERMINAL = ['PENDING', 'PROCESSING', 'RETRYING'];
const TERMINAL = ['SENT', 'FAILED', 'DEAD_LETTERED', 'PUBLISH_FAILED'];

const POLL_INTERVAL_S = 2;
const POLL_LIMIT_MS = 180000;
// Até 1% dos POSTs pode falhar sem reprovar o threshold; o polling espera pelo menos 990 novas
const MIN_NEW = 990;

export const options = {
  scenarios: {
    demo: { executor: 'shared-iterations', vus: 50, iterations: 1000, maxDuration: '5m' },
  },
  // A escada do demo (1s, 5s, 25s) leva mais de 31s depois do último POST; o padrão de 60s cortaria o polling
  teardownTimeout: '4m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

// Destinatários por canal. k é a posição do item dentro do próprio canal (0..19), o que dá as proporções:
// webhook com 35% de always-transient (mais 5% de fail-until-reprocess) abre o CB com certeza, já que a
// taxa de falha fica em torno de 4t / (1 + 3t), acima dos 50% do limiar; e-mail e SMS ficam com 5% de cada
// roteiro mágico.
function recipient(channel, k, i) {
  if (channel === 'WEBHOOK') {
    if (k <= 6) return 'https://fail.test/always-transient';
    if (k === 7) return 'https://fail.test/always-permanent';
    if (k === 8) return 'https://fail.test/fail-2-then-ok';
    if (k === 9) return 'https://fail.test/fail-until-reprocess';
    return `https://example.com/webhooks/notify-hub/${i}`;
  }
  if (channel === 'EMAIL') {
    if (k === 0) return 'always-transient@fail.test';
    if (k === 1) return 'always-permanent@fail.test';
    if (k === 2) return 'fail-2-then-ok@fail.test';
    if (k === 3) return 'fail-until-reprocess@fail.test';
    return `cliente${i}@example.com`;
  }
  // SMS: os mágicos têm 13 caracteres; o normal tem 14 (+55119 e 8 dígitos) e nunca casa com eles
  if (k === 0) return '+550000000001';
  if (k === 1) return '+550000000002';
  if (k === 2) return '+550000000123';
  if (k === 3) return '+550000000004';
  return `+55119${String(i).padStart(8, '0')}`;
}

// Templates do seed com variante nos 3 canais e suas variáveis obrigatórias
function template(t, i) {
  const name = `Cliente ${i}`;
  if (t === 0) return { templateCode: 'welcome', variables: { name } };
  if (t === 1) return { templateCode: 'order-shipped', variables: { name, orderId: `PED-${i}` } };
  return {
    templateCode: 'password-reset',
    variables: { name, resetCode: String(100000 + ((i * 7919) % 900000)) },
  };
}

function body(i) {
  const channel = CHANNELS[i % 3];
  const group = Math.floor(i / 3);
  return {
    channel,
    recipient: recipient(channel, group % 20, i),
    ...template(group % 3, i),
  };
}

function stats() {
  const res = http.get(`${API}/notifications/stats`, { tags: { name: 'stats' } });
  if (res.status !== 200) {
    throw new Error(`GET /notifications/stats respondeu ${res.status}: ${res.body}`);
  }
  return res.json();
}

export function setup() {
  return { base: stats() };
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const res = http.post(`${API}/notifications`, JSON.stringify(body(i)), JSON_HDR);
  check(res, { '202 Accepted': (r) => r.status === 202 });
}

// Não terminais da rodada: o quanto cada status cresceu desde o setup(). O valor absoluto do /stats não
// serve aqui porque inclui documentos de rodadas antigas que ficaram para trás no volume do Mongo
// (por exemplo, um PROCESSING abandonado antes da Fase 2); esses não pertencem a esta rodada.
function nonTerminalOfRound(s, base) {
  const out = {};
  for (const k of NON_TERMINAL) out[k] = Math.max(0, s.byStatus[k] - base.byStatus[k]);
  return out;
}

function sumValues(o) {
  return Object.values(o).reduce((a, b) => a + b, 0);
}

function line(prefix, now, base, keys) {
  return prefix + keys.map((k) => `${k}=${now[k] - base[k]}`).join(' ');
}

export function teardown(data) {
  const base = data.base;
  const start = Date.now();
  let s = stats();
  let zeroStreak = 0;
  while (Date.now() - start < POLL_LIMIT_MS) {
    const pending = sumValues(nonTerminalOfRound(s, base));
    zeroStreak = pending === 0 && s.total - base.total >= MIN_NEW ? zeroStreak + 1 : 0;
    // Duas leituras seguidas sem não terminais: evita parar no instante em que uma mensagem
    // está saindo de uma fila de delay e ainda não foi reivindicada
    if (zeroStreak >= 2) break;
    sleep(POLL_INTERVAL_S);
    s = stats();
  }

  const nt = nonTerminalOfRound(s, base);
  const waited = Math.round((Date.now() - start) / 1000);
  const ntLine = `não terminais: PENDING=${nt.PENDING} PROCESSING=${nt.PROCESSING} RETRYING=${nt.RETRYING}`;

  if (sumValues(nt) > 0 || zeroStreak < 2) {
    console.log('==== RESUMO INCOMPLETO NOTIFY HUB (k6) ====');
    console.log(`RESUMO INCOMPLETO: novas=${s.total - base.total} aguardou=${waited}s ${ntLine}`);
    console.log(line('terminais até agora: ', s.byStatus, base.byStatus, TERMINAL));
    throw new Error(`a rodada não drenou em ${POLL_LIMIT_MS / 1000}s (${ntLine})`);
  }

  console.log('==== RESUMO NOTIFY HUB (k6) ====');
  console.log(`novas=${s.total - base.total} aguardou=${waited}s`);
  console.log(ntLine);
  console.log(line('terminais da rodada: ', s.byStatus, base.byStatus, TERMINAL));
  for (const ch of CHANNELS) {
    console.log(line(`${ch}: `, s.byChannelAndStatus[ch], base.byChannelAndStatus[ch], TERMINAL));
  }
  const absNt = NON_TERMINAL.map((k) => `${k}=${s.byStatus[k]}`).join(' ');
  if (absNt !== NON_TERMINAL.map((k) => `${k}=0`).join(' ')) {
    console.log(`(no banco inteiro, contando rodadas anteriores: ${absNt})`);
  }
  console.log(
    'Dica: DEAD_LETTERED e FAILED estão nas DLQs e podem ser reprocessados com ' +
      'POST /admin/dlq/{canal}/reprocess (veja docs/demo.md)',
  );
}
