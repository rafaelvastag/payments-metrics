package br.com.exemplo.pagamentos.simulador;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.dominio.EventoPagamento;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService.Resultado;
import br.com.exemplo.pagamentos.dominio.StatusEvento;
import br.com.exemplo.pagamentos.infra.Chaos;
import br.com.exemplo.pagamentos.infra.Chaos.Alvo;
import br.com.exemplo.pagamentos.infra.Chaos.TipoFalha;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas.Leitura;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas.Variacao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static br.com.exemplo.pagamentos.dominio.StatusEvento.*;

/**
 * Cenários manuais: cada um dispara um conjunto pequeno e determinístico de eventos pelo fluxo REAL
 * ({@link ProcessadorEventoService}) e devolve quais métricas mudaram, com valor antes e depois.
 *
 * <p>Serve para entender, uma métrica por vez, o que cada situação do fluxo gera. Os cenários rodam
 * no modo determinístico do {@link Chaos}: nada de falha aleatória, só as falhas que o cenário pede.
 *
 * <p>Para que o diff mostre só o efeito do cenário, rode com o gerador automático desligado
 * ({@code simulador.automatico=false}). Se ele estiver ligado, o diff também inclui o tráfego automático
 * e a resposta traz um aviso.
 */
@Component
public class CenariosManuais {

    /**
     * Um evento a enviar, com as falhas que devem ser forçadas durante o seu processamento.
     * O {@link EventoPagamento} é criado só no momento do envio ({@link #evento()}), para que o
     * enqueuedTime reflita a espera desejada e não o tempo gasto nos passos anteriores.
     *
     * @param espera         quanto tempo a mensagem "ficou na fila" antes de ser consumida
     * @param deliveryCount  1 = primeira entrega; maior que 1 = reentrega
     */
    public record Passo(String descricao, String operacaoId, String arranjo, String status,
                        Duration espera, int deliveryCount, Map<Alvo, TipoFalha> falhasForcadas) {
        EventoPagamento evento() {
            return new EventoPagamento(operacaoId, arranjo, status, Instant.now().minus(espera), deliveryCount);
        }
    }

    /**
     * Definição de um cenário.
     *
     * @param id           identificador usado na URL (ex.: transicao-invalida)
     * @param titulo       nome legível
     * @param oQueObservar explicação das métricas que devem mudar e por quê
     * @param passos       função que monta os passos a partir do arranjo escolhido
     */
    public record Definicao(String id, String titulo, String oQueObservar,
                            Function<Arranjo, List<Passo>> passos) {}

    /** Resumo de um cenário, para a listagem. */
    public record Resumo(String id, String titulo, String oQueObservar) {}

    /** O que aconteceu em um passo. */
    public record ResultadoPasso(String descricao, EventoPagamento evento, Map<Alvo, TipoFalha> falhasForcadas,
                                 Resultado resultado, long duracaoMs) {}

    /** Resposta completa da execução de um cenário. */
    public record Execucao(String cenario, String titulo, String arranjo, String oQueObservar, String aviso,
                           List<ResultadoPasso> passos, List<Variacao> metricasAlteradas) {}

    private final ProcessadorEventoService processador;
    private final InspetorDeMetricas inspetor;
    private final ObjectProvider<GeradorDeTrafego> gerador;   // pode não existir (modo manual)
    private final Map<String, Definicao> cenarios = new LinkedHashMap<>();

    public CenariosManuais(ProcessadorEventoService processador, InspetorDeMetricas inspetor,
                           ObjectProvider<GeradorDeTrafego> gerador) {
        this.processador = processador;
        this.inspetor = inspetor;
        this.gerador = gerador;
        registrarCenarios();
    }

    // ==========================================================================================
    // Execução
    // ==========================================================================================

    public List<Resumo> listar() {
        return cenarios.values().stream().map(d -> new Resumo(d.id(), d.titulo(), d.oQueObservar())).toList();
    }

    public Optional<Execucao> executar(String id, Arranjo arranjo) {
        Definicao def = cenarios.get(id);
        if (def == null) return Optional.empty();

        // 1) Foto das métricas antes.
        Map<String, Leitura> antes = inspetor.fotografar(null);

        // 2) Executa cada passo pelo fluxo real, no modo determinístico com as falhas pedidas.
        List<ResultadoPasso> resultados = new ArrayList<>();
        for (Passo passo : def.passos().apply(arranjo)) {
            EventoPagamento evento = passo.evento();
            long inicio = System.nanoTime();
            Resultado r = Chaos.deterministico(passo.falhasForcadas(), () -> processador.processar(evento));
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            resultados.add(new ResultadoPasso(passo.descricao(), evento, passo.falhasForcadas(), r, ms));
        }

        // 3) Foto depois e diff: só as séries que mudaram.
        List<Variacao> variacoes = inspetor.comparar(antes, inspetor.fotografar(null));

        String aviso = geradorAutomaticoLigado()
                ? "O gerador automático está ligado: o diff inclui tráfego dele. Para ver só o efeito do cenário, "
                  + "use simulador.automatico=false ou POST /simulador/desligar."
                : null;
        return Optional.of(new Execucao(def.id(), def.titulo(), arranjo.name(), def.oQueObservar(), aviso,
                resultados, variacoes));
    }

    private boolean geradorAutomaticoLigado() {
        GeradorDeTrafego g = gerador.getIfAvailable();
        return g != null && g.ligado();
    }

    // ==========================================================================================
    // Catálogo de cenários
    // ==========================================================================================

    private void registrarCenarios() {
        add("fluxo-feliz", "Ciclo de vida completo com sucesso",
                "pagamento.evento.processamento{outcome=success} sobe 1 por evento (4 no PIX, 2 nos demais), "
                + "um por status_evento. pagamento.evento.espera_fila conta cada evento. pagamento.db.operacao "
                + "registra buscar_operacao e atualizar_status por evento. pagamento.servicebus.envio registra "
                + "um envio por evento, com o destino de cada etapa.",
                a -> {
                    String id = novoId("feliz");
                    List<StatusEvento> ciclo = a.passaPorAntifraude()
                            ? List.of(PAGAMENTO_INICIADO, ANALISE_FRAUDE, FRAUDE_APROVADA, PAGAMENTO_CONCLUIDO)
                            : List.of(PAGAMENTO_INICIADO, PAGAMENTO_CONCLUIDO);
                    return ciclo.stream().map(s -> passo(s.name(), id, a.name(), s.name())).toList();
                });

        add("transicao-invalida", "Transição recusada pela máquina de estado",
                "1º evento: sucesso normal (iniciada). 2º evento pede PAGAMENTO_DEVOLVIDO com a operação em "
                + "INICIADA, que não é permitido: pagamento.transicao.invalida{estado_atual=iniciada, "
                + "status_evento=pagamento_devolvido} +1, pagamento.db.operacao{operacao=gravar_ocorrencia} +1 "
                + "e processamento{outcome=rejected, error.type=transicao_invalida} +1. Não há envio para fila.",
                a -> {
                    String id = novoId("transicao");
                    return List.of(
                            passo("cria a operação", id, a.name(), PAGAMENTO_INICIADO.name()),
                            passo("pede devolução sem ter concluído", id, a.name(), PAGAMENTO_DEVOLVIDO.name()));
                });

        add("evento-duplicado", "Mesmo evento entregue duas vezes",
                "O 2º PAGAMENTO_INICIADO encontra a operação já em INICIADA: transicao.invalida{estado_atual=iniciada, "
                + "status_evento=pagamento_iniciado} +1. Esse par (estado = destino do próprio status) é a "
                + "assinatura de duplicidade no painel de transições inválidas.",
                a -> {
                    String id = novoId("duplicado");
                    return List.of(passo("primeira entrega", id, a.name(), PAGAMENTO_INICIADO.name()),
                            passo("mesma mensagem de novo", id, a.name(), PAGAMENTO_INICIADO.name()));
                });

        add("fora-de-ordem", "Evento chega antes do anterior",
                "PAGAMENTO_CONCLUIDO chega para uma operação que ainda não existe (estado NOVA): "
                + "transicao.invalida{estado_atual=nova, status_evento=pagamento_concluido} +1. Estado NOVA na "
                + "matriz é a assinatura de eventos fora de ordem.",
                a -> List.of(passo("conclusão antes do início", novoId("ordem"), a.name(), PAGAMENTO_CONCLUIDO.name())));

        add("status-desconhecido", "Status que o fluxo não conhece",
                "pagamento.evento.nao_mapeado{motivo=status_desconhecido} +1 e processamento{status_evento=desconhecido, "
                + "outcome=rejected, error.type=nao_mapeado} +1. Nenhuma chamada ao banco nem à fila.",
                a -> List.of(passo("status PAGAMENTO_AGENDADO", novoId("status"), a.name(), "PAGAMENTO_AGENDADO")));

        add("arranjo-desconhecido", "Arranjo que o fluxo não conhece",
                "nao_mapeado{arranjo=desconhecido, motivo=arranjo_desconhecido} +1 e processamento{arranjo=desconhecido} +1. "
                + "O texto recebido (DOC) não vira tag: só o valor fixo 'desconhecido'. O arranjo informado é ignorado.",
                a -> List.of(passo("arranjo DOC", novoId("arranjo"), "DOC", PAGAMENTO_INICIADO.name())));

        add("fluxo-nao-suportado", "Status válido que não existe no arranjo",
                "ANALISE_FRAUDE para um arranjo sem antifraude: nao_mapeado{motivo=fluxo_nao_suportado} +1. "
                + "Se o arranjo informado for PIX, o cenário usa TED.",
                a -> {
                    Arranjo semFraude = a.passaPorAntifraude() ? Arranjo.TED : a;
                    return List.of(passo("antifraude em " + semFraude, novoId("fluxo"), semFraude.name(), ANALISE_FRAUDE.name()));
                });

        add("payload-invalido", "Evento sem operacaoId",
                "nao_mapeado{motivo=payload_invalido} +1 e processamento{outcome=rejected, error.type=validacao} +1.",
                a -> List.of(passo("operacaoId vazio", "", a.name(), PAGAMENTO_INICIADO.name())));

        add("reentrega", "Mensagem entregue pela 3ª vez",
                "pagamento.evento.reentrega{arranjo} +1 (deliveryCount=3). O processamento em si é normal (success).",
                a -> List.of(new Passo("deliveryCount = 3", novoId("reentrega"), a.name(), PAGAMENTO_INICIADO.name(),
                        Duration.ZERO, 3, Map.of())));

        add("backlog-fila", "Mensagem que esperou 5s na fila",
                "pagamento.evento.espera_fila registra ~5000ms (veja mediaNoCenarioMs), enquanto o processamento "
                + "continua rápido. É exatamente o que distingue backlog de lentidão no painel 'Espera na fila'.",
                a -> List.of(new Passo("enqueuedTime = agora - 5s", novoId("backlog"), a.name(), PAGAMENTO_INICIADO.name(),
                        Duration.ofSeconds(5), 1, Map.of())));

        add("erro-banco", "Banco falha ao buscar a operação",
                "db.operacao{operacao=buscar_operacao, outcome=error, error.type=db} +1 e processamento{outcome=error, "
                + "error.type=db} +1. Não há atualização nem envio. No consumidor real a mensagem voltaria para a fila.",
                a -> List.of(passoComFalha("busca falha", novoId("erro-db"), a.name(), PAGAMENTO_INICIADO.name(),
                        Alvo.BUSCAR_OPERACAO, TipoFalha.ERRO)));

        add("timeout-banco", "Banco estoura timeout ao gravar o status",
                "Busca ok; atualizar_status trava pelo timeout configurado (chaos.timeout-ms) e falha: "
                + "db.operacao{operacao=atualizar_status, outcome=error, error.type=timeout} +1 com tempo alto, e "
                + "processamento{outcome=error, error.type=timeout} +1. Veja o _max subir no painel de latência.",
                a -> List.of(passoComFalha("gravação com timeout", novoId("timeout-db"), a.name(), PAGAMENTO_INICIADO.name(),
                        Alvo.ATUALIZAR_STATUS, TipoFalha.TIMEOUT)));

        add("erro-servicebus", "Falha ao publicar na fila de saída",
                "Banco ok; servicebus.envio{destino=pagamento-status, outcome=error, error.type=servicebus} +1 e "
                + "processamento{outcome=error, error.type=servicebus} +1. Atenção: o status já foi gravado no banco "
                + "antes do envio falhar; na reentrega isso vira transição inválida (veja o cenário seguinte).",
                a -> List.of(passoComFalha("envio falha", novoId("erro-sb"), a.name(), PAGAMENTO_INICIADO.name(),
                        Alvo.ENVIO_SERVICEBUS, TipoFalha.ERRO)));

        add("erro-servicebus-com-reentrega", "Envio falha e a mensagem é reentregue",
                "1º: grava INICIADA e falha no envio (error/servicebus). 2º: a mesma mensagem volta com deliveryCount=2: "
                + "reentrega +1 e, como o estado já é INICIADA, transicao.invalida{estado_atual=iniciada, "
                + "status_evento=pagamento_iniciado} +1. Mostra por que gravar antes de publicar sem idempotência/outbox "
                + "gera transições inválidas em cascata.",
                a -> {
                    String id = novoId("cascata");
                    return List.of(
                            passoComFalha("1ª entrega: envio falha", id, a.name(), PAGAMENTO_INICIADO.name(),
                                    Alvo.ENVIO_SERVICEBUS, TipoFalha.ERRO),
                            new Passo("2ª entrega da mesma mensagem", id, a.name(), PAGAMENTO_INICIADO.name(),
                                    Duration.ZERO, 2, Map.of()));
                });

        add("timeout-servicebus", "Envio estoura timeout",
                "servicebus.envio{outcome=error, error.type=timeout} +1 com tempo ~ chaos.timeout-ms, e "
                + "processamento{outcome=error, error.type=timeout} +1.",
                a -> List.of(passoComFalha("envio com timeout", novoId("timeout-sb"), a.name(), PAGAMENTO_INICIADO.name(),
                        Alvo.ENVIO_SERVICEBUS, TipoFalha.TIMEOUT)));

        add("falha-gravar-ocorrencia", "Transição inválida e a ocorrência não é gravada",
                "2º evento é transição inválida, mas a gravação da ocorrência falha: db.operacao{operacao=gravar_ocorrencia, "
                + "outcome=error} +1, processamento{outcome=error, error.type=db} +1 e transicao.invalida NÃO muda. "
                + "Por isso existe um alerta específico para gravar_ocorrencia com erro: a ocorrência se perderia.",
                a -> {
                    String id = novoId("ocorrencia");
                    return List.of(
                            passo("cria a operação", id, a.name(), PAGAMENTO_INICIADO.name()),
                            passoComFalha("transição inválida + falha ao gravar ocorrência",
                                    id, a.name(), PAGAMENTO_DEVOLVIDO.name(),
                                    Alvo.GRAVAR_OCORRENCIA, TipoFalha.ERRO));
                });
    }

    // ---------- utilitários para montar os cenários ----------

    private void add(String id, String titulo, String oQueObservar, Function<Arranjo, List<Passo>> passos) {
        cenarios.put(id, new Definicao(id, titulo, oQueObservar, passos));
    }

    /** Id único por execução, para cada cenário começar de uma operação "limpa". */
    private static String novoId(String prefixo) {
        return "manual-" + prefixo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Passo comum: mensagem recém-enfileirada (espera ~0ms), primeira entrega, sem falhas. */
    private static Passo passo(String descricao, String id, String arranjo, String status) {
        return new Passo(descricao, id, arranjo, status, Duration.ZERO, 1, Map.of());
    }

    /** Passo com uma falha forçada em uma operação específica do fluxo. */
    private static Passo passoComFalha(String descricao, String id, String arranjo, String status,
                                       Alvo alvo, TipoFalha tipo) {
        return new Passo(descricao, id, arranjo, status, Duration.ZERO, 1, Map.of(alvo, tipo));
    }
}
