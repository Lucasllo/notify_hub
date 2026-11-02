---
phase: "04"
slug: "observabilidade-demo-e-documenta-o"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 1
created: "2026-11-01"
---

# Phase 04 — Security

> Contrato de segurança da fase: registro de ameaças, riscos aceitos e trilha de auditoria.
> Registro consolidado dos blocos `<threat_model>` de 04-01..04-05-PLAN.md. Nenhum SUMMARY trouxe `## Threat Flags`.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| registro de imagens → build/runtime | Imagens base e de infra baixadas do Docker Hub | Binários de terceiros (integridade) |
| Maven Central → estágio de build | Dependências resolvidas dentro do container | Artefatos de terceiros (integridade) |
| host/LAN → portas do compose | API (chave admin da demo), actuator, Prometheus, Grafana, RabbitMQ | Credenciais locais da demo, métricas |
| Prometheus → actuator das apps | Scrape sem autenticação | Métricas agregadas (baixa sensibilidade) |
| broker → API | Desfecho do publisher confirm traduzido para tag fechada | Metadado de desfecho |
| navegador → Grafana | Acesso anônimo (Viewer) e login admin | Dashboards somente leitura |
| container k6 → API | 1000 requisições com destinatários sintéticos | Dados fictícios |
| script de benchmark / JFR → host | Projeto `notify-bench` e gravação JFR | ENVs da JVM (credenciais locais) |
| documentação → leitor externo | Garantias e limites prometidos | Afirmações públicas |
| script de explain → MongoDB | Comandos `explain`/`find` de leitura | Nenhum dado alterado |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-04-01 | Information Disclosure | actuator da API | medium | mitigate | `include: health,info,prometheus` em notify-api/src/main/resources/application.yml:48 (e worker:50) | closed |
| T-04-02 | Information Disclosure | portas do compose | high | mitigate | Todas as 8 portas publicadas como `127.0.0.1:host:container` (docker-compose.yml:13-126) | closed |
| T-04-03 | Elevation of Privilege | containers api/worker | medium | mitigate | `USER app` em notify-api/Dockerfile:36 e notify-worker/Dockerfile:36 | closed |
| T-04-04 | Information Disclosure | contexto e imagem | low | mitigate | `.dockerignore` exclui `.git`, `.planning`, `.gsd`, `target`; runtime copia só `*-exec.jar` (Dockerfile:34) | closed |
| T-04-05 | Tampering | imagens base | medium | mitigate | `maven:3.9-eclipse-temurin-21`, `eclipse-temurin:21-jre`, `prom/prometheus:v3.13.4` (tags exatas do CLAUDE.md) | closed |
| T-04-SC | Tampering | `micrometer-registry-prometheus` | high | mitigate | Sem `<version>` em notify-api/pom.xml:40-43; versão vem do BOM do Boot 3.5.16 | closed |
| T-04-06 | Information Disclosure | `/actuator/prometheus` | low | accept | Métricas agregadas, `uri` templada, porta no loopback | closed |
| T-04-07 | Information Disclosure | tags de `notify.publish.outcomes` | medium | mitigate | Só tags `outcome`/`reason` (PublishMetrics.java:66-67), `reason` derivado do enum `PublishResult.Outcome`; nenhum `.tag("id"/"recipient")` | closed |
| T-04-08 | Denial of Service | cardinalidade do registry | low | mitigate | Séries pré-registradas no construtor; `hasSize(7)` em NotificationRepublisherTest:105,179 | closed |
| T-04-09 | Repudiation | contagem dupla ou ausente | low | mitigate | Contagem só em NotificationRepublisher (javadoc :55), testes por ramo | closed |
| T-04-10 | Elevation of Privilege | acesso anônimo do Grafana | medium | mitigate | `GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer`, `allowUiUpdates: false`, datasource `editable: false`, dashboard `editable: false` | closed |
| T-04-11 | Spoofing | `admin/admin` versionado | medium | accept | Porta só em 127.0.0.1:3000; registrado em docs/trade-offs.md:15 | closed |
| T-04-12 | Tampering | script k6 | medium | mitigate | Nenhum import `from 'http(s)://'` em k6/ e scripts/ | closed |
| T-04-13 | Information Disclosure | Grafana telefonando para fora | low | mitigate | `GF_ANALYTICS_REPORTING_ENABLED/CHECK_FOR_UPDATES: "false"` (docker-compose.yml:119-120) | closed |
| T-04-14 | Tampering | imagens Grafana/k6 | medium | mitigate | `grafana/grafana:12.4.12`, `grafana/k6:2.3.0` | closed |
| T-04-15 | Denial of Service | carga do k6 contra a API | low | accept | Local, 1000 req/50 VUs, profile `bench` fora do `up` padrão | closed |
| T-04-16 | Repudiation | números publicados | medium | mitigate | Resumo gerado por script e colado verbatim; conferido no UAT (teste 5, pass) | closed |
| T-04-17 | Tampering | config da demo | low | mitigate | Override separado em docker-compose.bench.yml, projeto `notify-bench` | closed |
| T-04-18 | Information Disclosure | `worker-vt.jfr` | low | mitigate | `target/` no .gitignore:1; só credenciais locais da demo na JVM | closed |
| T-04-19 | Denial of Service | host do autor | low | accept | C ≤ 650, teto de 15 min por rodada, `down -v` no fim | closed |
| T-04-20 | Information Disclosure | credenciais na documentação | low | accept | Só credenciais locais da demo, listadas em docs/trade-offs.md:15 | closed |
| T-04-21 | Spoofing | leitor que usa a demo como produção | medium | mitigate | docs/trade-offs.md:3 declara o profile `demo` não pronto para produção e lista os riscos; conferido no UAT (teste 6, pass) | closed |
| T-04-22 | Tampering | `scripts/explain-indices.js` | low | mitigate | Nenhuma chamada `.insert/.update/.delete/.drop/.remove` no script; só `explain`/`find` | closed |
| T-04-23 | Denial of Service | volumes da demo | low | accept | `down -v` intencional; demo se recria com `docker compose up --build` | closed |

*Status: open · closed · open — below high threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-04-01 | T-04-06 | `/actuator/prometheus` sem autenticação expõe só agregados, porta no loopback | plano 04-01 | 2026-11-01 |
| AR-04-02 | T-04-11 | Grafana `admin/admin` versionado; só local, dashboard somente leitura, listado nos trade-offs | plano 04-03 | 2026-11-01 |
| AR-04-03 | T-04-15 | Carga do k6 é local e opt-in (profile `bench`) | plano 04-03 | 2026-11-01 |
| AR-04-04 | T-04-19 | Benchmark limitado em concorrência e duração no host do autor | plano 04-04 | 2026-11-01 |
| AR-04-05 | T-04-20 | Credenciais da demo na documentação são locais e marcadas como tal | plano 04-05 | 2026-11-01 |
| AR-04-06 | T-04-23 | `down -v` apaga volumes da demo de propósito (estado limpo) | plano 04-05 | 2026-11-01 |

*Accepted risks do not resurface in future audit runs.*

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-11-01 | 23 | 23 | 0 | gsd-secure-phase (orquestrador, verificação L1 por grep; auditor não acionado: register do plano + ASVS 1 + 0 abertas) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-11-01
