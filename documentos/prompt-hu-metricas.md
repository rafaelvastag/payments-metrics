Você vai escrever uma **história de usuário técnica** para instrumentar com Micrometer o fluxo de processamento de eventos de pagamento deste repositório. A história será entregue a um time de desenvolvimento. Ela precisa ser concreta e ancorada no código real: classes, métodos, enums, filas e exceções que existem aqui. Nada de exemplos genéricos.

**Não altere nenhum código.** O único arquivo que você deve criar é a história, em `docs/historias/HU-metricas-fluxo-pagamentos.md` (crie a pasta se não existir).

---

## Contexto do fluxo

O serviço consome eventos do Azure Service Bus. Cada evento tem:

- um **arranjo**: PIX dinâmico, PIX estático, TED, TEF etc.;
- um **status**: pagamento concluído, análise de fraude etc.

O processamento:

1. busca a operação no banco;
2. valida, na máquina de estado, se a transição do estado salvo para o status recebido é permitida;
3. grava o novo status;
4. publica em filas de saída do Service Bus.

Quando a transição não é permitida, o serviço grava uma **ocorrência de falha**.

O objetivo é medir:

- tempo de processamento;
- volume por arranjo e por status;
- sucessos e erros;
- tempo de banco;
- envios para as filas de saída, com sucesso e com erro;
- cenários não mapeados;
- ocorrências de transição inválida.

---

## Passo 1 — Explore o repositório antes de escrever

Leia o código e levante, com caminho de arquivo e linha:

1. **Stack:** versões de Java, Spring Boot e Micrometer. Diga se já existem `spring-boot-starter-actuator` e `micrometer-registry-prometheus`, e se o endpoint `/actuator/prometheus` está exposto. Diga também se a Observation API está disponível: ela exige Boot 3.
2. **Métricas que já existem:** uso de `MeterRegistry`, `@Timed`, `@Counted`, `Observation`, `management.metrics.*`, tags comuns e configuração de histograma/SLO.
3. **Consumidor do Service Bus:**
   - Qual classe e método recebem a mensagem (`ServiceBusProcessorClient`, `@ServiceBusListener`, Spring Cloud Stream etc.).
   - Como é feito o complete, o abandon e o dead-letter.
   - Se `enqueuedTime` e `deliveryCount` estão acessíveis.
   - Qual é a configuração de concorrência e do limite de entregas.
4. **Domínio:**
   - Onde estão definidos arranjo, status do evento e estado persistido (enums ou constantes). Liste **todos os valores reais**.
   - O que acontece hoje quando chega um arranjo ou status que não existe no domínio: exceção, log, descarte ou outra coisa.
   - Se existem status que não se aplicam a certos arranjos (ex.: antifraude só para PIX).
5. **Máquina de estado:**
   - Onde estão as regras de transição.
   - Onde e como a ocorrência de falha é gravada.
   - Se a gravação da ocorrência pode falhar sem que ninguém perceba.
6. **Banco:** tecnologia (Mongo, JPA, JDBC), quais operações o fluxo faz (busca, atualização de status, gravação de ocorrência) e se já existe instrumentação automática do driver (ex.: `MongoMetricsCommandListener`, Hikari).
7. **Filas de saída:**
   - Quais são (nomes lógicos e físicos).
   - Quem decide para qual fila cada evento vai.
   - Como o envio é feito.
8. **Erros:**
   - Quais exceções podem ocorrer no banco, no envio e por timeout.
   - Como elas sobem até o consumidor.
   - Existe retry? Em qual camada?
9. **Ordem e consistência:**
   - O status é gravado no banco **antes** de publicar na fila de saída?
   - Se o envio falhar e a mensagem for reentregue, o que acontece? Ela vira transição inválida?
   - Existe idempotência ou outbox?
10. **Testes:** framework, convenções e se há testes do consumidor ou do serviço que possam ser estendidos com `SimpleMeterRegistry`.

Se algo não puder ser determinado pelo código, **não invente**. Registre como pergunta em aberto na história.

---

## Passo 2 — Diretrizes de desenho que a história deve seguir

- **Poucas métricas, muitas tags.** Uma métrica por conceito. Nunca uma métrica por status ou por arranjo.
- **Timer já é counter.** `_count`, `_sum`, `_max` e buckets respondem volume, sucesso, erro e latência. Não crie counters paralelos de "recebidos/sucesso/erro".
- **Counter só para eventos sem duração:** transição inválida, não mapeado, reentrega.
- **`outcome` com três valores:**
  - `success`: processou e publicou;
  - `rejected`: regra de negócio recusou (transição inválida, não mapeado), e a mensagem é consumida;
  - `error`: falha técnica, com abandon e reentrega.
  Mapeie esses valores para o que o consumidor real faz (complete, abandon, dead-letter).
- **`error.type` por categoria finita** (ex.: `none`, `db`, `servicebus`, `timeout`, `validacao`, `transicao_invalida`, `nao_mapeado`, `desconhecido`). Nunca use o nome da classe nem a mensagem da exceção. Mapeie as **exceções reais** encontradas no Passo 1 para essas categorias.
- **Cardinalidade controlada.** Tags só com valores de domínio fechado, vindos de enums. Nunca use id de operação, endToEndId, txId, CPF/CNPJ ou payload. Valor fora do domínio vira `desconhecido`, nunca o texto recebido.
- **Mesmo conjunto de chaves de tag sempre.** A tag nunca é omitida: use `none` ou `desconhecido`.
- **Histograma via SLO buckets explícitos** em `management.metrics.distribution.slo`, incluindo o valor do SLO. Não use `percentiles()` no cliente nem `publishPercentileHistogram`.
- **Tags comuns** (`application`, `env` e, se fizer sentido, `region`) via `management.metrics.tags`.
- **Fachada única** (ex.: `PagamentoMetrics`) com nomes e chaves centralizados. Enums de valores de tag. O código de negócio não chama `MeterRegistry` diretamente.
- **Timer de processamento fechado em um único ponto:** exatamente uma medição por mensagem, em qualquer caminho.
- **Ocorrência antes do counter:** o counter de transição inválida só incrementa depois que a ocorrência foi gravada com sucesso. Se a gravação falhar, é erro técnico.

### Catálogo de referência

Adapte nomes e valores ao código real e justifique cada desvio.

| Métrica | Tipo | Tags |
|---|---|---|
| `pagamento.evento.processamento` | Timer | `arranjo`, `status_evento`, `outcome`, `error.type` |
| `pagamento.evento.espera_fila` | Timer (enqueuedTime → início) | `arranjo` |
| `pagamento.db.operacao` | Timer | `operacao` (ex.: `buscar_operacao`, `atualizar_status`, `gravar_ocorrencia`), `outcome`, `error.type` |
| `pagamento.servicebus.envio` | Timer | `destino` (nome **lógico** da fila), `arranjo`, `outcome`, `error.type` |
| `pagamento.transicao.invalida` | Counter | `arranjo`, `estado_atual`, `status_evento` |
| `pagamento.evento.nao_mapeado` | Counter | `arranjo`, `motivo` (`status_desconhecido`, `arranjo_desconhecido`, `fluxo_nao_suportado`, `payload_invalido`) |
| `pagamento.evento.reentrega` | Counter | `arranjo` (`deliveryCount > 1`) |

Se o repositório já tiver métricas, convenções de nome ou um prefixo próprio, **siga o padrão existente** e explique a escolha.

---

## Passo 3 — Estrutura da história

Escreva em português, em Markdown, com estas seções:

1. **Título e história:** como / quero / para.
2. **Contexto:** o fluxo real, com o caminho de cada etapa no código (arquivo:linha). Inclua um diagrama simples (texto ou Mermaid) mostrando qual métrica mede cada etapa.
3. **Situação atual:** o que já existe de observabilidade e o que falta.
4. **Princípios de desenho:** os do Passo 2, de forma resumida.
5. **Catálogo de métricas:** a tabela final adaptada, com o nome no Prometheus (`*_seconds_count`, `*_total` etc.).
6. **Domínio de cada tag:** os valores **reais**, extraídos dos enums do código, com o arquivo de origem.
7. **Mapeamento de erros:** exceção real → `error.type`.
8. **Mapeamento de outcome:** `outcome` → ação real no Service Bus.
9. **Implementação:**
   - **Pontos de instrumentação:** para cada métrica, a classe e o método exatos onde medir e o que muda ali.
   - **Esboço da fachada de métricas e dos enums de tags:** código Java coerente com a versão do Spring Boot e com o estilo do projeto.
   - **Configuração `application.yml`:** exposição do endpoint, tags comuns e SLO buckets. Proponha valores de bucket e deixe o SLO como premissa a validar.
   - **Testes:** quais casos cobrir com `SimpleMeterRegistry`, aproveitando os testes existentes.
10. **Estimativa de cardinalidade:** calculada com os tamanhos **reais** dos enums. Mostre a conta e o total estimado de séries por pod, incluindo buckets.
11. **Cenários de monitoramento:** tabela com cenário, sinal, query PromQL e ação esperada. Cubra no mínimo:
    - queda ou pico de vazão;
    - aumento de erro técnico, total e por arranjo;
    - banco lento e banco com falha;
    - fila de saída lenta e com falha, por destino;
    - timeouts;
    - backlog na fila de entrada;
    - risco de DLQ (reentregas);
    - eventos duplicados (assinatura: `estado_atual` = destino do próprio status);
    - eventos fora de ordem (assinatura: estado inicial, ex.: `nova`);
    - transição nova não prevista;
    - status novo do upstream;
    - arranjo desconhecido;
    - fluxo não suportado;
    - mudança no mix de status (ex.: mais fraude reprovada);
    - quebra de SLO;
    - cauda de latência (`_max`, p99);
    - etapa lenta por status;
    - falha ao gravar ocorrência;
    - se o Passo 1 confirmar o risco, **transição inválida em cascata** causada por envio que falha depois da gravação, seguido de reentrega.
12. **Catálogo de queries PromQL:** agrupado em vazão/resultado, latência, banco, Service Bus e máquina de estado. Use `$__rate_interval`, `$__range` e as variáveis `$env`, `$arranjo` e `$status_evento`. Inclua também:
    - "% dentro do SLO", lido direto do bucket `le`;
    - o heatmap;
    - a matriz de transições inválidas, com `increase(...[$__range])`;
    - as variáveis do dashboard, com `label_values`.
13. **Dashboard Grafana:** linhas e painéis (visão geral; vazão e resultado por tag; latência; banco; Service Bus; máquina de estado, não mapeados e reentregas), com a pergunta que cada linha responde e cores fixas para `outcome`.
14. **Alertas sugeridos:** expressão, janela e severidade. Inclua erro técnico, p95, backlog, falha de envio por destino, falha ao gravar ocorrência, surto de transição inválida, eventos não mapeados e reentregas. Diga que os limiares devem ser calibrados com dados reais.
15. **Critérios de aceite:** numerados, verificáveis, no formato dado/quando/então quando fizer sentido.
16. **Subtarefas** em ordem de execução.
17. **Riscos e perguntas em aberto:** tudo que o código não respondeu e os riscos encontrados no Passo 1, como gravar antes de publicar, ocorrência que pode se perder ou ausência de idempotência.
18. **Fora de escopo e Definition of Done.**

---

## Regras finais

- Toda referência a código deve apontar arquivo e, quando possível, linha.
- Todo valor de tag listado deve existir no código, ou estar marcado como proposta.
- Não proponha tags com identificadores ou texto livre.
- Se o fluxo real divergir do contexto descrito (ex.: não usa Service Bus, não tem ocorrência), adapte a história ao que existe e explique a divergência no início.
- Ao terminar, me responda com:
  - um resumo de 5 linhas;
  - o caminho do arquivo gerado;
  - as perguntas em aberto mais importantes.
