package br.com.exemplo.pagamentos.dominio;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static br.com.exemplo.pagamentos.dominio.EstadoOperacao.*;

/**
 * Regras de transição da operação.
 *
 * <p>Para cada {@link StatusEvento} existe uma regra: "de quais estados posso partir" e "para qual
 * estado vou". Se o estado salvo não está entre as origens permitidas, a transição é <b>inválida</b>:
 * o fluxo grava uma ocorrência de falha e incrementa {@code pagamento.transicao.invalida}.
 *
 * <pre>
 *  NOVA ─iniciado─▶ INICIADA ─analise_fraude─▶ EM_ANALISE_FRAUDE ─fraude_aprovada─▶ FRAUDE_APROVADA
 *                      │                              │                                  │
 *                      │                              └─fraude_reprovada─▶ FRAUDE_REPROVADA │
 *                      └───────────── concluido (TED/TEF/boleto) ──────────┐               │
 *                                                                          ▼  ◀─concluido──┘
 *                                                                       CONCLUIDA ─devolvido─▶ DEVOLVIDA
 *  rejeitado: de INICIADA, EM_ANALISE_FRAUDE ou FRAUDE_APROVADA ─▶ REJEITADA
 *  cancelado: de INICIADA ou EM_ANALISE_FRAUDE                   ─▶ CANCELADA
 * </pre>
 */
public final class MaquinaDeEstado {

    /** Uma regra: estados de origem aceitos e estado de destino. */
    private record Regra(Set<EstadoOperacao> origens, EstadoOperacao destino) {}

    private static final Map<StatusEvento, Regra> REGRAS = new EnumMap<>(StatusEvento.class);

    static {
        REGRAS.put(StatusEvento.PAGAMENTO_INICIADO, new Regra(EnumSet.of(NOVA), INICIADA));
        REGRAS.put(StatusEvento.ANALISE_FRAUDE, new Regra(EnumSet.of(INICIADA), EM_ANALISE_FRAUDE));
        REGRAS.put(StatusEvento.FRAUDE_APROVADA, new Regra(EnumSet.of(EM_ANALISE_FRAUDE), EstadoOperacao.FRAUDE_APROVADA));
        REGRAS.put(StatusEvento.FRAUDE_REPROVADA, new Regra(EnumSet.of(EM_ANALISE_FRAUDE), EstadoOperacao.FRAUDE_REPROVADA));
        // TED/TEF/boleto concluem direto de INICIADA; PIX conclui depois da aprovação de fraude.
        REGRAS.put(StatusEvento.PAGAMENTO_CONCLUIDO, new Regra(EnumSet.of(INICIADA, EstadoOperacao.FRAUDE_APROVADA), CONCLUIDA));
        REGRAS.put(StatusEvento.PAGAMENTO_REJEITADO, new Regra(EnumSet.of(INICIADA, EM_ANALISE_FRAUDE, EstadoOperacao.FRAUDE_APROVADA), REJEITADA));
        REGRAS.put(StatusEvento.PAGAMENTO_DEVOLVIDO, new Regra(EnumSet.of(CONCLUIDA), DEVOLVIDA));
        REGRAS.put(StatusEvento.CANCELADO, new Regra(EnumSet.of(INICIADA, EM_ANALISE_FRAUDE), CANCELADA));
    }

    private MaquinaDeEstado() {}

    /**
     * Aplica o evento ao estado atual.
     *
     * @return o novo estado, ou {@code Optional.empty()} se a transição não é permitida
     */
    public static Optional<EstadoOperacao> transicionar(EstadoOperacao atual, StatusEvento evento) {
        Regra regra = REGRAS.get(evento);
        if (regra == null || !regra.origens().contains(atual)) {
            return Optional.empty();
        }
        return Optional.of(regra.destino());
    }

    /**
     * Fila de saída (nome lógico) para cada estado resultante. É o valor da tag {@code destino}:
     * usamos o nome lógico, não o nome físico da fila, para a tag não mudar entre ambientes.
     */
    public static String filaDestino(EstadoOperacao novoEstado) {
        return switch (novoEstado) {
            case EM_ANALISE_FRAUDE -> "antifraude-solicitacao";
            case CONCLUIDA -> "pagamento-concluido";
            case REJEITADA, FRAUDE_REPROVADA -> "pagamento-rejeitado";
            case DEVOLVIDA -> "pagamento-devolvido";
            case CANCELADA -> "pagamento-cancelado";
            default -> "pagamento-status";
        };
    }
}
