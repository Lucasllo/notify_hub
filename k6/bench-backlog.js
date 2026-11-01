// Backlog do benchmark virtual threads x threads de plataforma.
//
// Objetivo: criar ITERATIONS notificações (W de aquecimento + N medidas) com o worker parado, para que
// o worker suba depois e drene um backlog conhecido. Só destinatários normais, em domínios reservados
// (example.com) e telefones fictícios (+5511...): nenhuma regra mágica de falha e nenhum dado real.
// Os canais giram pelo índice da iteração (EMAIL, SMS, WEBHOOK), então cada canal recebe 1/3.
//
// Uso (o scripts/bench/run-bench.sh chama assim, com o projeto notify-bench no ar):
//   docker compose -p notify-bench -f docker-compose.yml -f docker-compose.bench.yml \
//     --profile bench run --rm --no-deps -e ITERATIONS=36000 k6 run --quiet /scripts/bench-backlog.js
// ENVs: ITERATIONS (obrigatória), VUS (padrão 100), API_URL (padrão http://api:8080).
// O k6 sai com código diferente de 0 se mais de 1% dos POSTs falhar.

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

if (!__ENV.ITERATIONS) {
  throw new Error('defina ITERATIONS (quantidade de notificações do backlog)');
}
const ITERATIONS = Number(__ENV.ITERATIONS);
const VUS = Number(__ENV.VUS || 100);
const API = __ENV.API_URL || 'http://api:8080';
const JSON_HDR = { headers: { 'Content-Type': 'application/json' } };
const CHANNELS = ['EMAIL', 'SMS', 'WEBHOOK'];

export const options = {
  discardResponseBodies: true,
  scenarios: {
    backlog: { executor: 'shared-iterations', vus: VUS, iterations: ITERATIONS, maxDuration: '30m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

function recipient(channel, i) {
  if (channel === 'EMAIL') return `bench${i}@example.com`;
  // +5511 e 9 dígitos: 14 caracteres
  if (channel === 'SMS') return `+5511${900000000 + i}`;
  return `https://example.com/bench/${i}`;
}

export default function () {
  const i = exec.scenario.iterationInTest;
  const channel = CHANNELS[i % 3];
  const body = {
    channel,
    recipient: recipient(channel, i),
    templateCode: 'welcome',
    variables: { name: `Bench ${i}` },
  };
  const res = http.post(`${API}/notifications`, JSON.stringify(body), JSON_HDR);
  check(res, { '202 Accepted': (r) => r.status === 202 });
}
