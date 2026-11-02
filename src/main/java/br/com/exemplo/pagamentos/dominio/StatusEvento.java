package br.com.exemplo.pagamentos.dominio;

import java.util.Arrays;
import java.util.Optional;

/**
 * Status que podem chegar no evento de entrada. Fonte dos valores da tag {@code status_evento}.
 *
 * <p>Não confundir com {@link EstadoOperacao}: o status é o que o evento <i>pede</i>;
 * o estado é o que está <i>salvo no banco</i>. A máquina de estado decide se o pedido é aceito.
 */
public enum StatusEvento {
    PAGAMENTO_INICIADO,
    ANALISE_FRAUDE,
    FRAUDE_APROVADA,
    FRAUDE_REPROVADA,
    PAGAMENTO_CONCLUIDO,
    PAGAMENTO_REJEITADO,
    PAGAMENTO_DEVOLVIDO,
    CANCELADO;

    /** Valor da tag quando chega um status que não existe neste enum. */
    public static final String TAG_DESCONHECIDO = "desconhecido";

    /** Status que só fazem sentido para arranjos com antifraude (usado na validação de "fluxo não suportado"). */
    public boolean ehDeAntifraude() {
        return this == ANALISE_FRAUDE || this == FRAUDE_APROVADA || this == FRAUDE_REPROVADA;
    }

    /** Valor da tag: nome em minúsculas (ex.: {@code pagamento_concluido}). */
    public String tag() {
        return name().toLowerCase();
    }

    /** Converte o texto do evento sem lançar exceção; inválido vira {@code Optional.empty()}. */
    public static Optional<StatusEvento> parse(String valor) {
        if (valor == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(valor.trim())).findFirst();
    }
}
