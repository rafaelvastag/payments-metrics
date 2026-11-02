Você vai fazer uma **análise de débitos técnicos** deste repositório e gerar um relatório que o time possa transformar em backlog. A análise deve ser baseada em **evidências do código**, com arquivo e linha. Nada de recomendações genéricas de boas práticas.

**Não altere nenhum código.** O único arquivo que você deve criar é o relatório, em `docs/debitos-tecnicos/relatorio-debitos-tecnicos.md` (crie a pasta se não existir). Você pode executar apenas comandos de leitura, como `mvn -q dependency:tree`, `git log` e `git shortlog`. Não rode nada que altere arquivos, banco ou ambientes.

---

## Passo 0 — Mapa do sistema

Antes de procurar problemas, entenda o sistema e registre:

1. **Stack:**
   - versões de Java, Spring Boot, frameworks e bibliotecas principais;
   - ferramenta de build;
   - como roda (Dockerfile, Helm, pipelines em `.github/`, `azure-pipelines.yml` etc.).
2. **Módulos e camadas:** estrutura de pacotes, padrão arquitetural pretendido (Clean Architecture, hexagonal, camadas) e como os módulos dependem entre si.
3. **Fluxos críticos:** pontos de entrada (consumidores de fila, controllers, jobs agendados) e o caminho que cada fluxo de negócio principal percorre até banco, filas de saída e APIs externas.
4. **Integrações:** bancos, filas e tópicos, APIs HTTP externas, caches.
5. **Áreas quentes:** use `git log` para identificar os arquivos que mais mudam e os mais recentes. Arquivos com muita mudança **e** muita complexidade são prioridade.

Use esse mapa para **priorizar a análise**. Os fluxos críticos e as áreas quentes recebem análise profunda; o resto, análise por amostragem.

---

## Passo 1 — O que procurar

Analise as categorias abaixo. Os itens são pontos de partida, não uma lista exaustiva.

### 1. Arquitetura e design
- Violações da arquitetura pretendida (ex.: domínio dependendo de infraestrutura, controller com regra de negócio, entidade de banco vazando para a API).
- Dependências cíclicas entre pacotes ou módulos.
- Classes "deus" (muitas responsabilidades, muitas dependências injetadas).
- Lógica de negócio duplicada em lugares diferentes.
- Acoplamento a detalhes de framework onde deveria haver uma abstração.
- Código morto: classes, métodos, flags e endpoints sem uso.

### 2. Qualidade de código
- Métodos e classes muito longos ou com complexidade ciclomática alta (cite os piores).
- Tratamento de exceções:
  - `catch` genérico que engole erro;
  - `e.printStackTrace()`;
  - exceção convertida em `null` ou `Optional.empty()` sem log;
  - perda de causa ao relançar.
- Uso inadequado de `Optional`, nulls não tratados, `static` mutável.
- Números e strings mágicos com significado de negócio espalhados.
- TODO, FIXME e HACK antigos (use `git blame` para estimar a idade).
- Código comentado deixado no lugar.

### 3. Resiliência e consistência (prioridade alta em fluxos de pagamento e mensageria)
- Chamadas externas (HTTP, banco, filas) **sem timeout**, ou com timeout inadequado. Antes de afirmar que não há timeout, verifique também a configuração global.
- Retry sem backoff, sem limite ou aplicado a operações não idempotentes.
- Ausência de circuit breaker onde uma dependência lenta pode derrubar o serviço.
- **Idempotência:**
  - o consumidor trata reentrega e mensagens duplicadas?
  - há chave de idempotência?
- **Atomicidade entre banco e mensageria:** grava no banco e publica na fila sem outbox ou transação. O que acontece se uma das duas operações falhar?
- Tratamento de DLQ: há processo de reprocessamento? Mensagens podem se perder?
- Ordem de mensagens: o fluxo assume uma ordem que a infraestrutura não garante?
- Concorrência:
  - condições de corrida em atualização de estado;
  - ausência de lock otimista ou versionamento;
  - estado mutável compartilhado.

### 4. Dados
- Consultas sem índice correspondente (para Mongo, compare as queries com os índices declarados).
- N+1, carregamento excessivo, ausência de paginação.
- Transações ausentes, ou longas demais.
- Migrações de schema não versionadas.
- Dados sensíveis sem criptografia em repouso, quando aplicável.

### 5. Segurança e conformidade
- Segredos no código ou em arquivos de configuração versionados.
- **Dados pessoais e sensíveis em logs** (CPF/CNPJ, conta, nome, payload inteiro). Considere LGPD e as exigências do BACEN.
- Validação de entrada ausente.
- Endpoints sem autenticação ou autorização.
- Actuator exposto além do necessário.
- Dependências com vulnerabilidades conhecidas: cite as versões suspeitas e indique a verificação com OWASP Dependency-Check ou Dependabot. Não invente CVEs.
- Trilha de auditoria: mudanças de estado relevantes para o negócio ou para o regulador são registradas de forma rastreável?

### 6. Observabilidade
- Logs:
  - sem correlação (traceId, id da operação);
  - em nível inadequado;
  - não estruturados;
  - excessivos em caminho quente.
- Ausência de métricas de negócio e de integrações: volume, erro e latência por fluxo, fila e API externa.
- Ausência de tracing distribuído.
- Health checks que não refletem as dependências reais.

### 7. Testes
- Cobertura dos fluxos críticos. Se houver relatório JaCoCo, use-o; senão, estime pela presença de testes por classe crítica.
- Testes que não testam nada (sem assert, com mock de tudo, ou que testam o mock).
- Ausência de testes de integração para banco, filas e APIs (Testcontainers, WireMock).
- Testes frágeis: sleep, dependência de ordem, de horário, de rede.
- Cenários de erro não testados (timeout, falha de envio, reentrega, transição inválida).

### 8. Dependências e plataforma
- Versões fora de suporte (EOL) de Java, Spring Boot e bibliotecas principais.
- Dependências duplicadas, conflitantes ou não usadas.
- Bibliotecas abandonadas.
- Plugins de build desatualizados.

### 9. Configuração e operação
- Configuração duplicada entre perfis.
- Valores de ambiente fixos no código.
- Feature flags permanentes.
- Mocks ou stubs que podem ser ativados em produção.
- Dockerfile:
  - imagem base pesada ou desatualizada;
  - roda como root;
  - sem healthcheck;
  - JVM sem ajuste para container.
- Pipeline sem etapa de testes, análise estática ou verificação de segurança.

### 10. Performance
- Operações bloqueantes onde se espera não bloqueio.
- Pools mal dimensionados.
- Serialização repetida.
- Objetos caros criados por requisição (ex.: `ObjectMapper`, clientes HTTP).
- Ausência de cache onde há leitura repetida de dado estável, ou cache sem invalidação.

### 11. Documentação e conhecimento
- README desatualizado ou ausente.
- Ausência de documentação dos fluxos, das integrações e das decisões (ADRs).
- Regras de negócio complexas sem explicação.
- Conhecimento concentrado: arquivos críticos alterados por uma única pessoa (use `git shortlog`).

---

## Passo 2 — Como registrar cada débito

Cada débito deve ter:

| Campo | Conteúdo |
|---|---|
| **ID** | `TD-001`, `TD-002`... |
| **Título** | Curto e específico |
| **Categoria** | Uma das 11 acima |
| **Severidade** | Crítica / Alta / Média / Baixa (critérios abaixo) |
| **Confiança** | Confirmado (evidência direta) / Provável (indício forte, precisa validar) |
| **Evidência** | `arquivo:linha` e um trecho curto do código. Se o padrão se repete, cite 2 ou 3 exemplos e o total de ocorrências. |
| **Impacto** | O que pode acontecer, concreto para este sistema (ex.: "mensagem de pagamento perdida se o envio falhar após gravar o status") |
| **Recomendação** | O que fazer, específico para este código |
| **Esforço** | P (até 1 dia) / M (até 1 sprint) / G (mais que 1 sprint) |
| **Risco de não fazer** | O que piora com o tempo |
| **Quick win?** | Sim, se o impacto for Alto ou Crítico e o esforço for P |

### Critérios de severidade
- **Crítica:** pode causar perda ou corrupção de dados financeiros, incidente de segurança, exposição de dados pessoais, descumprimento regulatório ou indisponibilidade do fluxo principal.
- **Alta:** pode causar falhas recorrentes em produção, inconsistência recuperável, degradação séria de desempenho, ou impede evoluir uma parte importante do sistema.
- **Média:** aumenta o custo de manutenção, o risco de bugs ou o tempo de diagnóstico, sem impacto direto imediato.
- **Baixa:** melhoria de legibilidade ou organização com benefício limitado.

---

## Passo 3 — Estrutura do relatório

Escreva em português, em Markdown:

1. **Sumário executivo** (até 10 linhas):
   - estado geral;
   - os 5 débitos mais importantes;
   - total por severidade;
   - recomendação de por onde começar.
2. **Mapa do sistema:** resumo do Passo 0, com um diagrama Mermaid dos fluxos críticos e das integrações.
3. **Visão geral:** tabela com a quantidade de débitos por categoria × severidade.
4. **Matriz de priorização:** impacto × esforço, com os IDs posicionados em quatro quadrantes:
   - quick wins;
   - projetos estratégicos;
   - preenchimento;
   - evitar ou reavaliar.
5. **Débitos por categoria:** cada débito no formato do Passo 2, ordenados por severidade.
6. **Plano de ação sugerido:**
   - **Agora (próximas 2 sprints):** críticos e quick wins.
   - **Próximo trimestre.**
   - **Contínuo:** práticas e guardrails para não acumular de novo (ex.: análise estática no pipeline, ArchUnit para regras de arquitetura, Dependabot).
7. **Pontos fortes:** o que está bem feito e deve ser preservado. Isso evita que o time "conserte" o que funciona.
8. **Limitações da análise:**
   - o que não foi analisado e por quê;
   - o que depende de informação de runtime (volumes, métricas, incidentes) para confirmar.
9. **Perguntas para o time:** pontos que o código não responde.

---

## Regras

- **Evidência sempre.** Todo débito tem `arquivo:linha`. Sem evidência, não é débito; no máximo, é uma pergunta para o time.
- **Verifique antes de afirmar.** Antes de dizer "não há timeout", "não há teste", "não há índice" ou "não há tratamento", procure em configuração global, classes base, aspects, interceptors e testes de outros módulos.
- **Não infle o relatório.**
  - Agrupe ocorrências do mesmo padrão em um único débito, com a contagem.
  - Ignore estilo e formatação que um linter resolve, a menos que haja impacto real.
  - Prefira 20 débitos relevantes a 100 superficiais.
- **Seja específico ao domínio.** Este é um sistema de pagamentos: consistência, idempotência, rastreabilidade, segurança de dados e conformidade pesam mais que elegância de código.
- **Não proponha reescritas** sem justificar por que uma refatoração incremental não resolve.
- Se o repositório for grande demais para analisar por completo, diga quais partes foram analisadas em profundidade e quais por amostragem.

Ao terminar, me responda com:
- o caminho do relatório;
- o total de débitos por severidade;
- os 5 débitos mais críticos, em uma linha cada;
- os quick wins.
