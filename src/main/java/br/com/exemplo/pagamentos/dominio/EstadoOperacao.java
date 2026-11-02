package br.com.exemplo.pagamentos.dominio;

/**
 * Estado persistido da operação: o lado "banco" da máquina de estado.
 * Fonte dos valores da tag {@code estado_atual} na métrica de transição inválida.
 *
 * <p>{@link #NOVA} representa uma operação que ainda não existe no banco: é o estado assumido
 * quando o primeiro evento de uma operação chega.
 */
public enum EstadoOperacao {
    NOVA,
    INICIADA,
    EM_ANALISE_FRAUDE,
    FRAUDE_APROVADA,
    FRAUDE_REPROVADA,
    CONCLUIDA,
    REJEITADA,
    DEVOLVIDA,
    CANCELADA;

    /** Valor da tag: nome em minúsculas (ex.: {@code em_analise_fraude}). */
    public String tag() {
        return name().toLowerCase();
    }
}
