# pagamentos-metricas

POC de instrumentação com **Micrometer** do fluxo de eventos de pagamento: recebe o evento (aqui via HTTP, no lugar do consumidor do Service Bus), aplica a máquina de estado, grava no "banco" e publica na fila de saída — tudo simulado, com latência e falhas configuráveis. Prometheus coleta as métricas e o Grafana sobe com um dashboard pronto.

## Como rodar

```bash
docker compose up -d --build
```

| Serviço    | URL                                        |
|------------|--------------------------------------------|
| App        | http://localhost:8080                      |
| Métricas   | http://localhost:8080/actuator/prometheus  |
| Prometheus | http://localhost:9090 (aba *Alerts* com as regras sugeridas) |
| Grafana    | http://localhost:3000 — o dashboard **Pagamentos — fluxo de eventos** é a home |

No Grafana, o acesso anônimo tem papel **Editor**, então dá para editar e salvar sem login (admin / admin para configurações). As edições ficam no volume `grafana-data` e sobrevivem a `docker compose down`, mas somem com `down -v`. Para versionar uma alteração, exporte o JSON (*Export → Export as JSON*) e substitua `observabilidade/grafana/dashboards/pagamentos.json`.

Sem Docker: `mvn spring-boot:run` (só a app).

## Rodando a aplicação no IntelliJ

A aplicação roda no IntelliJ (com debug, breakpoints etc.) e só Prometheus + Grafana ficam no Docker.

1. **Suba só a observabilidade:**
   ```bash
   docker compose -f docker-compose.local.yml up -d
   ```
   Esse compose usa `observabilidade/prometheus/prometheus-local.yml`, que coleta de `host.docker.internal:8080` (sua máquina) em vez do container `app`. Não rode junto com o `docker-compose.yml` completo: os dois usam as portas 9090 e 3000.

2. **Rode a aplicação pelo IntelliJ.** Ao abrir o projeto, aparecem duas configurações prontas (pasta `.run/`, funcionam no Community e no Ultimate):
   - **App - modo automatico**: gerador de tráfego ligado; o dashboard enche sozinho.
   - **App - modo manual**: ativa o perfil `manual` (`application-manual.yml`, `simulador.automatico=false`); as métricas só mudam pelos cenários.

   Se preferir configurar à mão: classe `PagamentosMetricasApplication`, e para o modo manual adicione `-Dspring.profiles.active=manual` em *VM options* (ou a variável de ambiente `SIMULADOR_AUTOMATICO=false`). Requer JDK 21.

3. **Gere as métricas** com o `requests.http`: o HTTP Client do IntelliJ executa cada bloco direto do editor (ícone ▶ ao lado de cada requisição).

4. **Veja o resultado:** a resposta do cenário mostra o diff; o Prometheus (http://localhost:9090) e o Grafana (http://localhost:3000) refletem em ~5–10s.

Se o target aparecer `down` em http://localhost:9090/targets: confira se a aplicação está no ar na porta 8080 e se um firewall não está bloqueando conexões vindas do Docker para essa porta.

## Modos de geração de métricas

A propriedade `simulador.automatico` (variável `SIMULADOR_AUTOMATICO`) escolhe o modo:

| Valor | Modo | Como as métricas são geradas |
|---|---|---|
| `true` (padrão) | **Automático** | Um gerador chama `POST /eventos` sozinho (~40 eventos/s) com ciclos de vida reais por arranjo e anomalias: duplicados, fora de ordem, status/arranjo desconhecidos, fluxo não suportado, reentregas. Em 1–2 minutos o dashboard está populado. |
| `false` | **Manual** | Nada acontece sozinho. Você dispara cada cenário e vê exatamente quais métricas mudaram. |

```bash
# Modo manual com Docker: troque SIMULADOR_AUTOMATICO para "false" no docker-compose.yml, ou:
SIMULADOR_AUTOMATICO=false mvn spring-boot:run
```

### Modo manual: gerar cada métrica e ver o que mudou

```bash
# Lista os cenários e o que observar em cada um
curl localhost:8080/manual/cenarios

# Executa um cenário (arranjo opcional, padrão PIX_DINAMICO)
curl -X POST 'localhost:8080/manual/cenarios/transicao-invalida?arranjo=TED'

# Estado atual das métricas, legível (filtro opcional por trecho do nome)
curl 'localhost:8080/manual/metricas?nome=transicao'
```

A resposta de cada cenário traz os eventos enviados, o resultado de cada um e o **diff de métricas**: só as séries que mudaram, com valor antes, depois, delta e, para timers, o tempo médio das medições do cenário. Exemplo (resumido) de `transicao-invalida`:

```
+1  pagamento.evento.processamento{arranjo=ted, status_evento=pagamento_iniciado, outcome=success, error.type=none}
+1  pagamento.evento.processamento{arranjo=ted, status_evento=pagamento_devolvido, outcome=rejected, error.type=transicao_invalida}
+1  pagamento.db.operacao{operacao=gravar_ocorrencia, outcome=success}
+1  pagamento.transicao.invalida{arranjo=ted, estado_atual=iniciada, status_evento=pagamento_devolvido}
...
```

Os cenários rodam no fluxo real, em modo determinístico: as falhas aleatórias do `/chaos` são ignoradas e só acontecem as falhas que o cenário força (em uma operação específica). O mesmo cenário sempre gera as mesmas métricas.

| Cenário | O que demonstra |
|---|---|
| `fluxo-feliz` | Ciclo completo com sucesso (4 eventos no PIX, 2 nos demais) |
| `transicao-invalida` | Máquina de estado recusa a transição e grava ocorrência |
| `evento-duplicado` | Mesma mensagem duas vezes: `estado_atual` = destino do próprio status |
| `fora-de-ordem` | Conclusão antes do início: `estado_atual=nova` |
| `status-desconhecido` | `nao_mapeado{motivo=status_desconhecido}` |
| `arranjo-desconhecido` | `arranjo=desconhecido`; o texto recebido nunca vira tag |
| `fluxo-nao-suportado` | Antifraude em arranjo sem antifraude |
| `payload-invalido` | Sem `operacaoId`: `error.type=validacao` |
| `reentrega` | `deliveryCount=3` |
| `backlog-fila` | Espera na fila de 5s com processamento rápido |
| `erro-banco` | Falha ao buscar: `error.type=db` |
| `timeout-banco` | Timeout ao gravar status: `_max` sobe para o timeout |
| `erro-servicebus` | Falha no envio: `error.type=servicebus` |
| `erro-servicebus-com-reentrega` | Envio falha, mensagem volta e vira transição inválida em cascata |
| `timeout-servicebus` | Timeout no envio |
| `falha-gravar-ocorrencia` | Ocorrência não gravada: erro técnico e o counter de transição **não** sobe |

Os endpoints `/manual` também funcionam no modo automático, mas aí o diff pode incluir tráfego do gerador (a resposta avisa). Para pausar o gerador sem reiniciar: `POST /simulador/desligar`.

## Brincando com o modo automático

Veja `requests.http` (IntelliJ / VS Code REST Client). Os mais úteis para ver o dashboard reagir:

```bash
# Service Bus degradado: 20% de erro no envio
curl -X PUT localhost:8080/chaos -H 'Content-Type: application/json' \
  -d '{"dbLatenciaMs":15,"sbLatenciaMs":25,"dbTaxaErro":0.01,"sbTaxaErro":0.2,"taxaTimeout":0.003,"timeoutMs":1500}'

# Backlog: mensagens esperando 3s na fila
curl -X PUT 'localhost:8080/simulador?eventosPorSegundo=80&atrasoFilaMs=3000'

# Evento manual
curl -X POST localhost:8080/eventos -H 'Content-Type: application/json' \
  -d '{"operacaoId":"op-1","arranjo":"PIX_DINAMICO","status":"PAGAMENTO_INICIADO","deliveryCount":1}'
```

Respostas: `200` sucesso · `422` rejeitado por regra (transição inválida / não mapeado) · `503` erro técnico (em produção: *abandon* → reentrega).

## Catálogo de métricas

| Métrica (Micrometer)            | Prometheus                                    | Tipo    | Tags |
|---------------------------------|-----------------------------------------------|---------|------|
| `pagamento.evento.processamento`| `pagamento_evento_processamento_seconds_*`    | Timer   | `arranjo`, `status_evento`, `outcome`, `error.type` |
| `pagamento.evento.espera_fila`  | `pagamento_evento_espera_fila_seconds_*`      | Timer   | `arranjo` |
| `pagamento.db.operacao`         | `pagamento_db_operacao_seconds_*`             | Timer   | `operacao`, `outcome`, `error.type` |
| `pagamento.servicebus.envio`    | `pagamento_servicebus_envio_seconds_*`        | Timer   | `destino`, `arranjo`, `outcome`, `error.type` |
| `pagamento.transicao.invalida`  | `pagamento_transicao_invalida_total`          | Counter | `arranjo`, `estado_atual`, `status_evento` |
| `pagamento.evento.nao_mapeado`  | `pagamento_evento_nao_mapeado_total`          | Counter | `arranjo`, `motivo` |
| `pagamento.evento.reentrega`    | `pagamento_evento_reentrega_total`            | Counter | `arranjo` |

Tags comuns (`application`, `env`) vêm de `management.metrics.tags` e valem para todas.

`outcome` tem três valores: `success`, `rejected` (regra de negócio recusou — transição inválida ou não mapeado) e `error` (falha técnica). Separar `rejected` de `error` evita que um surto de eventos fora de ordem dispare o alerta de indisponibilidade.

### Decisões de desenho (o que levar para o código real)

- **Timer já é counter.** `_count` dá vazão, sucesso e erro por tag; não há counters paralelos de recebidos/sucesso/erro.
- **Fachada única** (`PagamentoMetrics`) concentra nomes e chaves de tag; valores de tag vêm de enums (`Tags`). Nenhum id de transação vira tag.
- **Mesmas chaves de tag sempre** — valores ausentes viram `desconhecido` / `none`.
- **SLO buckets** em `application.yml` (`management.metrics.distribution.slo`). O histograma agrega entre pods e permite ler direto "% dentro do SLO" pelo bucket `le="0.25"`. Nada de `percentiles()` no cliente.
- **Espera na fila** calculada a partir do `enqueuedTime` da mensagem: mostra backlog, que o timer de processamento não enxerga.
- **Banco medido por operação de negócio** (`buscar_operacao`, `atualizar_status`, `gravar_ocorrencia`). O detalhe por query fica com a instrumentação do driver (ex.: `MongoMetricsCommandListener`).
- **Cardinalidade** atual da POC: ~80 séries no timer principal × 10 buckets. Recalcule ao adicionar tags.

## Dashboard sugerido

Filtros no topo: **Ambiente**, **Arranjo**, **Status do evento** (variáveis do Grafana alimentadas por `label_values`, multi-seleção). Todos os painéis respeitam os filtros onde a métrica tem a tag.

| Linha | Painéis | Pergunta que responde |
|---|---|---|
| Visão geral | Eventos/s · % sucesso · % rejeitado · % erro técnico · p95 · % dentro do SLO (≤ 250ms) | "Está tudo bem agora?" |
| Vazão e resultado por tag | Eventos/s por arranjo · por status · por outcome · não-sucesso por `error_type` · taxa de erro por arranjo · tabela arranjo × status × resultado | "De onde vem o volume e onde está o erro?" |
| Latência | p50/p95/p99 (+ max no eixo direito) · p95 por arranjo · tempo médio por status · espera na fila p95 · heatmap dos buckets | "Está lento? Para quem? É processamento ou fila?" |
| Banco de dados | p95 por operação · ops/s por operação e resultado · erros por tipo | "O banco é o gargalo?" |
| Service Bus | Envios/s por destino · falhas por destino e tipo · p95 por destino | "Alguma fila de saída está falhando?" |
| Máquina de estado | Transições inválidas/min (`estado_atual → status_evento`) · matriz de transições inválidas no período · não mapeados por motivo · reentregas por arranjo | "Que inconsistência de ordem/estado está acontecendo?" |

Queries-padrão usadas (para reaproveitar em outros dashboards):

```promql
# vazão por tag
sum by (arranjo) (rate(pagamento_evento_processamento_seconds_count{arranjo=~"$arranjo"}[$__rate_interval]))

# taxa de erro técnico
sum(rate(pagamento_evento_processamento_seconds_count{outcome="error"}[$__rate_interval]))
  / sum(rate(pagamento_evento_processamento_seconds_count[$__rate_interval]))

# p95 por arranjo (a partir dos SLO buckets)
histogram_quantile(0.95, sum by (le, arranjo) (rate(pagamento_evento_processamento_seconds_bucket[$__rate_interval])))

# % dentro do SLO de 250ms (exato, sem estimar percentil)
sum(rate(pagamento_evento_processamento_seconds_bucket{le="0.25"}[$__rate_interval]))
  / sum(rate(pagamento_evento_processamento_seconds_count[$__rate_interval]))

# tempo médio por status
sum by (status_evento) (rate(pagamento_evento_processamento_seconds_sum[$__rate_interval]))
  / sum by (status_evento) (rate(pagamento_evento_processamento_seconds_count[$__rate_interval]))

# matriz de transições inválidas no período selecionado
sum by (arranjo, estado_atual, status_evento) (increase(pagamento_transicao_invalida_total[$__range]))
```

O JSON do dashboard é gerado por `observabilidade/grafana/gera_dashboard.py` (`python3 gera_dashboard.py dashboards/pagamentos.json`); dá para editar pela UI também.

## Alertas sugeridos

Em `observabilidade/prometheus/alertas.yml`: erro técnico > 5% por arranjo, p95 > 500ms, backlog na fila > 5s, falha de envio por destino, surto de transição inválida e qualquer evento não mapeado.

## Estrutura

```
src/main/java/.../dominio      máquina de estado, serviço de processamento
src/main/java/.../metricas     PagamentoMetrics (fachada), Tags (enums), InspetorDeMetricas (diff do modo manual)
src/main/java/.../infra        repositório e publisher simulados, Chaos (modo aleatório e determinístico)
src/main/java/.../simulador    GeradorDeTrafego (modo automático) e CenariosManuais (modo manual)
src/main/java/.../api          /eventos, /manual, /simulador, /chaos
observabilidade/               prometheus.yml, alertas, provisioning e dashboard do Grafana
```
