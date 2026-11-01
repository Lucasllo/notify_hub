// Vazão de drenagem do benchmark, calculada no MongoDB (mongosh).
//
// Uso: mongosh notify --quiet --eval "var warmup=<W>; $(cat scripts/bench/drain-stats.js)"
// O scripts/bench/run-bench.sh roda assim dentro do container mongo, depois que todas as notificações
// da rodada chegaram a SENT.
//
// Método: ordena as SENT por sentAt e descarta as W primeiras conclusões como aquecimento (JIT, pools e
// conexões ainda esquentando). A janela vai da W-ésima conclusão até a última, e a vazão é o número de
// conclusões medidas dividido por essa janela. Aquecimento e medição acontecem na mesma JVM.
//
// Saída: uma linha JSON {"total":..,"medidas":..,"janelaMs":..,"msgPorSeg":..}, ou {"erro":"poucas SENT"}
// quando há menos de W+2 notificações SENT.

const W = Number(typeof warmup !== 'undefined' ? warmup : 0);
const sent = db.notifications
  .find({ status: 'SENT' }, { sentAt: 1, _id: 0 })
  .sort({ sentAt: 1 })
  .toArray();
const total = sent.length;

if (total < W + 2) {
  print(JSON.stringify({ erro: 'poucas SENT' }));
} else {
  const medidas = total - W;
  const inicio = sent[W > 0 ? W - 1 : 0].sentAt;
  const fim = sent[total - 1].sentAt;
  const janelaMs = fim.getTime() - inicio.getTime();
  const msgPorSeg = janelaMs > 0 ? Number(((medidas * 1000) / janelaMs).toFixed(1)) : 0;
  print(JSON.stringify({ total, medidas, janelaMs, msgPorSeg }));
}
