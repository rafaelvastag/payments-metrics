Você vai escrever uma **história de usuário técnica** para instrumentar com Micrometer o fluxo de processamento de eventos de pagamento deste repositório. A história será entregue a um time de desenvolvimento. Ela precisa ser concreta e ancorada no código real: classes, métodos, enums, filas, clientes HTTP e exceções que existem aqui. Nada de exemplos genéricos.

**Não altere nenhum código.** O único arquivo que você deve criar é a história, em `docs/historias/HU-metricas-fluxo-pagamentos.md` (crie a pasta se não existir).

---

## Contexto do fluxo

O serviço consome eventos do Azure Service Bus. Cada evento recebido tem:

- um **arranjo**: PIX dinâmico, PIX estático, TED, TEF etc.;
- um **tipo** (status do evento): pagamento concluído, análise de fraude etc.

Durante o processamento, o serviço:

1. acessa o banco: busca a operação, grava o novo status e grava a ocorrência de falha quando a transição não é permitida na máquina de estado;
2. **envia mensagens de saída** (informes) para filas do Service Bus. Cada informe também tem **arranjo** e **tipo** (ex.: preliminar, segregação, iniciada, atualizada, baixa, entre outros). Um único evento recebido pode gerar vários informes;
3. **faz chamadas HTTP a sistemas externos**, por exemplo:
   - o **cancelamento de segregação**;
   - chamadas feitas a um **mock** (que substitui um sistema externo);
   - qualquer outra integração HTTP que exista no código.

## O que precisa ser medido

**Regra geral: sempre por arranjo e por tipo, e TODA chamada HTTP externa e TODO envio para fila do código devem ser medidos, sem exceção.**

1. **Eventos recebidos:** quantidade por tipo e por arranjo; quantos tiveram sucesso; quantos deram erro; tempo de processamento de cada mensagem.
2. **Mensagens de saída (informes):** para cada envio, o tempo que demorou, quantos deram sucesso e quantos deram erro, por **tipo do informe** e por **arranjo**.
3. **Chamadas HTTP externas:** para cada integração (cancelamento de segregação, mock e todas as demais), a quantidade de chamadas, quantas deram sucesso, quantas deram erro e o tempo de cada chamada, por **arranjo** e por **tipo**.
4. **Banco:** tempo, sucesso e erro por operação.
5. **Máquina de estado:** ocorrências de transição inválida e eventos não mapeados.
6. **Fila de entrada:** tempo de espera e reentregas.

---

## Passo 1 — Explore o repositório antes de escrever

Leia o código e levante, com caminho de arquivo e linha:

1. **Stack:** versões de Java, Spring Boot e Micrometer. Diga se já existem `spring-boot-starter-actuator` e `micrometer-registry-prometheus`, e se o endpoint `/actuator/prometheus` está exposto. Diga também se a Observation API está disponível: ela exige Boot 3.
2. **Métricas que já existem:** uso de `MeterRegistry`, `@Timed`, `@Counted`, `Observation`, `management.metrics.*`, tags comuns e configuração de histograma/SLO. Verifique também se a métrica automática `http.client.requests` já está sendo gerada: ela só existe se os clientes HTTP forem criados a partir dos builders gerenciados pelo Spring.
3. **Consumidor do Service Bus:**
   - Qual classe e método recebem a mensagem.
   - Como é feito o complete, o abandon e o dead-letter.
   - Se `enqueuedTime` e `deliveryCount` estão acessíveis.
   - Qual é a configuração de concorrência e do limite de entregas.
4. **Domínio:**
   - Onde estão definidos o arranjo, o tipo/status do evento recebido, o estado persistido e o **tipo do informe de saída**. Liste **todos os valores reais** de cada um.
   - O que acontece hoje quando chega um arranjo ou tipo que não existe no domínio.
5. **Máquina de estado:** onde estão as regras de transição e onde a ocorrência de falha é gravada.
6. **Banco:** tecnologia, operações que o fluxo faz e instrumentação automática já existente (ex.: `MongoMetricsCommandListener`, Hikari).
7. **INVENTÁRIO COMPLETO DE INTEGRAÇÕES DE SAÍDA.** Este é o item mais importante: nenhuma integração pode ficar de fora.
   - Procure **todos** os pontos que enviam mensagens: `ServiceBusSenderClient`, `ServiceBusTemplate`, `StreamBridge`, `JmsTemplate`, `@SendTo`, `KafkaTemplate` e wrappers próprios do projeto.
   - Procure **todos** os pontos que fazem chamadas HTTP: `RestTemplate`, `WebClient`, `RestClient`, `@FeignClient`, `@HttpExchange`, OkHttp, Apache HttpClient, `java.net.http.HttpClient` e wrappers próprios.
   - Para cada integração encontrada, registre:
     - classe e método;
     - destino (nome lógico da fila ou do sistema e, para HTTP, endpoint e verbo);
     - em que momento do fluxo é chamada;
     - quais dados de **arranjo** e **tipo** estão disponíveis naquele ponto;
     - timeout configurado;
     - se há retry (Resilience4j, Spring Retry, retry manual) e em qual camada;
     - se há circuit breaker;
     - exceções possíveis;
     - como o erro sobe para o fluxo: interrompe o processamento, é ignorado ou é logado.
   - **Cancelamento de segregação:** identifique exatamente onde e quando é chamado e o que acontece quando falha.
   - **Mock:** identifique o que ele substitui, como é ativado (profile, feature flag, propriedade, URL por ambiente) e se roda em produção. Diga se a métrica do mock deve ficar separada da integração real ou se as duas compartilham a métrica, diferenciadas por tag.
   - **Informes de saída:** identifique quem decide qual informe é enviado para cada evento, se a relação tipo do informe → fila é 1:1 e se um evento gera vários informes, em sequência ou em paralelo.
8. **Ordem e consistência:**
   - O status é gravado antes de publicar os informes e de chamar as integrações HTTP?
   - Se um envio ou uma chamada falhar e a mensagem for reentregue, o que acontece? Ela vira transição inválida? Há informes duplicados?
   - Existe idempotência ou outbox?
9. **Testes:** framework, convenções e testes existentes que possam ser estendidos com `SimpleMeterRegistry`.

Se algo não puder ser determinado pelo código, **não invente**. Registre como pergunta em aberto na história.

---

## Passo 2 — Diretrizes de desenho que a história deve seguir

- **Poucas métricas, muitas tags.** Uma métrica por conceito: uma para todos os informes de saída e uma para todas as chamadas HTTP externas. Nunca uma métrica por fila, por integração, por tipo ou por arranjo. A integração e o tipo são **tags**.
- **Timer já é counter.** `_count` responde quantos, a tag `outcome` separa sucesso de erro, `_sum`/`_count` dá o tempo médio e os buckets dão os percentis. Não crie counters paralelos de "chamadas/sucesso/erro".
- **Counter só para eventos sem duração:** transição inválida, não mapeado, reentrega, retentativa.
- **Sempre `arranjo` e `tipo`** em todas as métricas de evento, de informe e de chamada externa. Se em algum ponto o dado não estiver disponível, a tag recebe `desconhecido`, nunca é omitida, e o caso é registrado na história como lacuna a resolver.
- **`outcome`:**
  - `success`: deu certo;
  - `rejected`: regra de negócio recusou (transição inválida, não mapeado, recusa de negócio esperada de uma API externa, se existir);
  - `error`: falha técnica.
  Mapeie esses valores para o que o código real faz em cada caso.
- **`error.type` por categoria finita**, nunca a classe nem a mensagem da exceção. Sugestão: `none`, `db`, `servicebus`, `http_4xx`, `http_5xx`, `timeout`, `conexao`, `circuit_aberto`, `validacao`, `transicao_invalida`, `nao_mapeado`, `desconhecido`. Mapeie as **exceções reais** encontradas no Passo 1 para essas categorias.
- **Cardinalidade controlada.** Tags só com valores de domínio fechado (enums ou listas fixas). Nunca use id de operação, endToEndId, txId, CPF/CNPJ, payload, **URL completa**, query string ou status HTTP exato. Para HTTP, use o nome lógico da integração e, no máximo, a classe do status (`2xx`, `4xx`, `5xx`) dentro de `error.type`.
- **Mesmo conjunto de chaves de tag sempre.** A tag nunca é omitida: use `none` ou `desconhecido`.
- **Retry:** o timer mede a chamada lógica de ponta a ponta, incluindo as retentativas. As retentativas são contadas à parte, em um counter. Explique onde fica cada medição de acordo com a camada de retry encontrada.
- **Medição não muda o comportamento.** A exceção original é relançada sem alteração. Uma falha na instrumentação nunca derruba o fluxo.
- **Histograma via SLO buckets explícitos** em `management.metrics.distribution.slo`, incluindo o valor do SLO. Não use `percentiles()` no cliente nem `publishPercentileHistogram`.
- **Tags comuns** (`application`, `env` e, se fizer sentido, `region`) via `management.metrics.tags`.
- **Fachada única** (ex.: `PagamentoMetrics`) com nomes e chaves centralizados e enums de valores de tag (incluindo um enum de integrações). O código de negócio não chama `MeterRegistry` diretamente. Para HTTP, avalie e recomende uma de duas abordagens:
  - **(a)** um wrapper ou interceptor por cliente que usa a fachada;
  - **(b)** customizar a `http.client.requests` automática via `ObservationConvention` para adicionar `arranjo`, `tipo` e `integracao`.
  Justifique a escolha com base no código, e garanta que nenhuma chamada seja medida duas vezes no mesmo conceito.
- **Timer de processamento fechado em um único ponto:** exatamente uma medição por mensagem recebida.
- **Ocorrência antes do counter:** o counter de transição inválida só incrementa depois que a ocorrência foi gravada com sucesso.

### Catálogo de referência

Adapte nomes e valores ao código real e justifique cada desvio.

| Métrica | Tipo | Tags | Responde |
|---|---|---|---|
| `pagamento.evento.processamento` | Timer | `arranjo`, `tipo_evento`, `outcome`, `error.type` | Eventos recebidos por tipo e arranjo; sucesso; erro; tempo de cada mensagem |
| `pagamento.evento.espera_fila` | Timer | `arranjo`, `tipo_evento` | Tempo entre o enfileiramento e o início do processamento (backlog) |
| `pagamento.informe.envio` | Timer | `tipo_informe` (preliminar, segregacao, iniciada, atualizada, baixa...), `arranjo`, `fila` (nome lógico; remova se for 1:1 com o tipo), `outcome`, `error.type` | Cada informe de saída: tempo, sucesso e erro por tipo e arranjo |
| `pagamento.integracao.http` | Timer | `integracao` (ex.: `cancelamento_segregacao`, `mock_<nome>` e demais), `operacao` (só se a mesma integração tiver mais de uma operação), `arranjo`, `tipo`, `outcome`, `error.type` | Cada chamada HTTP externa: quantidade, sucesso, erro e tempo por integração, arranjo e tipo |
| `pagamento.integracao.retentativa` | Counter | `integracao`, `arranjo` | Retentativas (só se houver retry) |
| `pagamento.db.operacao` | Timer | `operacao`, `outcome`, `error.type` | Latência e erro de banco por operação |
| `pagamento.transicao.invalida` | Counter | `arranjo`, `estado_atual`, `tipo_evento` | Ocorrências de falha da máquina de estado |
| `pagamento.evento.nao_mapeado` | Counter | `arranjo`, `motivo` | Eventos que o fluxo não sabe tratar |
| `pagamento.evento.reentrega` | Counter | `arranjo`, `tipo_evento` | Mensagens com `deliveryCount > 1` |

Sobre o catálogo:

- Defina o que é **`tipo`** em `pagamento.integracao.http`: tipo do evento recebido, tipo do informe ou tipo da operação de negócio. Use o que fizer sentido para cada integração e documente. Use sempre a mesma chave `tipo` com domínio fechado.
- Se o repositório já tiver métricas, convenções de nome ou um prefixo próprio, **siga o padrão existente** e explique a escolha. Use nos nomes de tag o vocabulário do código (ex.: se o código chama de `status`, explique a correspondência com `tipo_evento`).

---

## Passo 3 — Estrutura da história

Escreva em português, em Markdown, com estas seções:

1. **Título e história:** como / quero / para.
2. **Contexto:** o fluxo real, com o caminho de cada etapa no código (arquivo:linha). Inclua um diagrama (Mermaid) mostrando o evento recebido, o banco, cada informe de saída e cada chamada HTTP, e qual métrica mede cada um.
3. **Situação atual:** o que já existe de observabilidade e o que falta.
4. **Inventário de integrações de saída:** tabela com todas as filas e chamadas HTTP encontradas. Para cada uma: classe:linha, destino, momento no fluxo, arranjo/tipo disponíveis, timeout, retry, circuit breaker, comportamento em falha, **métrica + valores de tag que vão medi-la**. Feche com uma linha de verificação: "N integrações encontradas, N cobertas". Se algum número não bater, explique.
5. **Princípios de desenho:** os do Passo 2, de forma resumida.
6. **Catálogo de métricas:** a tabela final adaptada, com o nome no Prometheus (`*_seconds_count`, `*_seconds_bucket`, `*_total`).
7. **Domínio de cada tag:** os valores **reais**, extraídos do código, com o arquivo de origem. Inclua o domínio completo de `tipo_informe` e de `integracao`.
8. **Mapeamento de erros:** exceção real → `error.type`, separado por banco, Service Bus e HTTP.
9. **Mapeamento de outcome:** `outcome` → ação real (complete, abandon, dead-letter, ignora, loga).
10. **Implementação:**
    - **Pontos de instrumentação:** para cada métrica e cada integração do inventário, a classe e o método exatos e o que muda ali.
    - **Esboço da fachada e dos enums de tags:** código Java coerente com a versão do Spring Boot e com o estilo do projeto. Inclua `medirInforme(...)` e `medirIntegracao(...)`, ou equivalentes.
    - **Abordagem escolhida para HTTP**, (a) ou (b), com justificativa.
    - **Configuração `application.yml`:** exposição do endpoint, tags comuns e SLO buckets por métrica. Proponha valores e deixe o SLO como premissa a validar.
    - **Testes:** casos com `SimpleMeterRegistry`. No mínimo: evento com sucesso; evento com erro; cada tipo de informe com sucesso e com erro; cancelamento de segregação com sucesso, 4xx, 5xx e timeout; mock com sucesso e com erro; retentativa (se houver); transição inválida; não mapeado.
11. **Estimativa de cardinalidade:** calculada com os tamanhos **reais** dos domínios. Mostre a conta de cada métrica e o total estimado de séries por pod, incluindo buckets. Se passar de uns poucos milhares, proponha onde cortar (ex.: tirar `error.type` do histograma, tirar `fila` se for redundante com o tipo).
12. **Perguntas → queries.** Para cada pergunta abaixo, escreva a query PromQL que a responde, usando `$__rate_interval`, `$__range` e as variáveis `$env`, `$arranjo`, `$tipo_evento`, `$tipo_informe` e `$integracao`:
    - quantos eventos recebi por tipo e por arranjo (taxa e total no período);
    - quantos eventos foram processados com sucesso e quantos com erro, por tipo e arranjo, e a % de erro;
    - tempo de processamento por mensagem: médio, p95, p99 e máximo, por tipo e arranjo;
    - quantos informes de saída enviei por tipo e arranjo; quantos com sucesso e quantos com erro; % de erro;
    - tempo de envio de cada tipo de informe: médio e p95, por arranjo;
    - quantas chamadas de cancelamento de segregação fiz, quantas com sucesso e quantas com erro (separando 4xx, 5xx e timeout), e o tempo médio e o p95, por arranjo e tipo;
    - as mesmas perguntas para o mock e para cada outra integração HTTP;
    - ranking das integrações mais lentas e das que mais erram;
    - quantos informes cada evento gera, em média, por tipo de evento (razão entre as duas métricas);
    - % dentro do SLO (lido do bucket `le`), heatmap de latência e matriz de transições inválidas.
13. **Cenários de monitoramento:** tabela com cenário, sinal, query e ação esperada. Cubra no mínimo:
    - queda ou pico de vazão de eventos;
    - aumento de erro de processamento, total e por arranjo;
    - um tipo de informe específico falhando ou lento;
    - fila de saída indisponível;
    - cancelamento de segregação falhando (4xx vs 5xx vs timeout) ou lento;
    - mock lento ou falhando (e alerta se métrica do mock aparecer em produção, caso ele não deva rodar lá);
    - integração com retentativas altas;
    - circuit breaker aberto (se houver);
    - banco lento e banco com falha;
    - backlog na fila de entrada;
    - risco de DLQ (reentregas);
    - eventos duplicados ou fora de ordem (assinatura na matriz `estado_atual` × `tipo_evento`);
    - tipo de evento ou arranjo desconhecido;
    - queda no número de informes por evento (informe deixou de ser enviado);
    - quebra de SLO;
    - cauda de latência;
    - se o Passo 1 confirmar o risco, transição inválida e informes duplicados em cascata por falha depois da gravação, seguida de reentrega.
14. **Dashboard Grafana:** variáveis (`env`, `arranjo`, `tipo_evento`, `tipo_informe`, `integracao`, todas via `label_values`, multi-seleção) e as linhas abaixo, cada uma com a pergunta que responde. Use cores fixas para `outcome`.
    - Visão geral.
    - Eventos recebidos: volume por tipo × arranjo, sucesso/erro, latência.
    - Informes de saída: volume, sucesso/erro e latência por tipo de informe × arranjo, mais a tabela tipo × arranjo × outcome.
    - Integrações HTTP: uma visão por integração (cancelamento de segregação, mock e demais) com chamadas/s, % de erro por categoria, p95 e máximo, por arranjo e tipo, mais o ranking de lentidão e de erro.
    - Banco.
    - Máquina de estado, não mapeados e reentregas.
15. **Alertas sugeridos:** expressão, janela e severidade. Inclua:
    - erro de processamento por arranjo;
    - p95 de processamento;
    - backlog;
    - erro de envio por tipo de informe;
    - erro e latência do cancelamento de segregação;
    - erro e latência de cada integração HTTP;
    - retentativas altas;
    - falha ao gravar ocorrência;
    - surto de transição inválida;
    - eventos não mapeados;
    - reentregas.
    Diga que os limiares devem ser calibrados com dados reais.
16. **Critérios de aceite:** numerados e verificáveis. Inclua obrigatoriamente: "todas as integrações do inventário (seção 4) estão instrumentadas e aparecem em `/actuator/prometheus` com `arranjo` e `tipo`" e "nenhuma chamada é medida duas vezes no mesmo conceito".
17. **Subtarefas** em ordem de execução, com uma subtarefa por grupo de integrações.
18. **Riscos e perguntas em aberto:** tudo que o código não respondeu; integrações em que arranjo ou tipo não estão disponíveis; mock em produção; gravar antes de publicar; ausência de idempotência.
19. **Fora de escopo e Definition of Done.**

---

## Regras finais

- Toda referência a código deve apontar arquivo e, quando possível, linha.
- Todo valor de tag listado deve existir no código, ou estar marcado como proposta.
- O inventário de integrações deve ser exaustivo. Antes de finalizar, faça uma segunda busca no repositório pelos clientes HTTP e de mensageria listados no Passo 1, item 7, e confirme que todos estão na seção 4.
- Não proponha tags com identificadores, URLs completas ou texto livre.
- Se o fluxo real divergir do contexto descrito, adapte a história ao que existe e explique a divergência no início.
- Ao terminar, me responda com:
  - um resumo de 5 linhas;
  - o caminho do arquivo gerado;
  - o número de integrações encontradas (filas e HTTP);
  - as perguntas em aberto mais importantes.
