package br.com.exemplo.pagamentos.metricas;

/**
 * Chaves e valores de tag das métricas do fluxo.
 *
 * <p>Regras que esta classe garante:
 * <ul>
 *   <li>Nenhum valor de tag é string solta no código: tudo vem de enum.</li>
 *   <li>Nenhum identificador de alta cardinalidade (id de operação, CPF, mensagem de exceção) vira tag.</li>
 *   <li>Os valores são publicados em minúsculas, via {@code tag()}.</li>
 * </ul>
 */
public final class Tags {

    private Tags() {}

    // ----- Chaves de tag -----
    // Uma mesma métrica deve SEMPRE ter o mesmo conjunto de chaves. Se não há valor, use "none"/"desconhecido".
    public static final String ARRANJO = "arranjo";
    public static final String STATUS_EVENTO = "status_evento";
    public static final String ESTADO_ATUAL = "estado_atual";
    public static final String OUTCOME = "outcome";
    public static final String ERROR_TYPE = "error.type";   // no Prometheus vira error_type
    public static final String OPERACAO = "operacao";
    public static final String DESTINO = "destino";
    public static final String MOTIVO = "motivo";

    /**
     * Resultado do processamento.
     * <ul>
     *   <li>{@code SUCCESS}: processou e publicou.</li>
     *   <li>{@code REJECTED}: regra de negócio recusou (transição inválida, não mapeado). A mensagem é
     *       consumida; não é indisponibilidade.</li>
     *   <li>{@code ERROR}: falha técnica. No consumidor real, a mensagem volta para a fila (abandon).</li>
     * </ul>
     */
    public enum Outcome {
        SUCCESS, REJECTED, ERROR;
        public String tag() { return name().toLowerCase(); }
    }

    /** Categoria do erro. Nunca o nome da classe nem a mensagem da exceção. {@code NONE} = sem erro. */
    public enum ErrorType {
        NONE, DB, SERVICEBUS, TIMEOUT, VALIDACAO, TRANSICAO_INVALIDA, NAO_MAPEADO, DESCONHECIDO;
        public String tag() { return name().toLowerCase(); }
    }

    /**
     * Operações de banco no nível de NEGÓCIO. O detalhe por query (find, update...) fica com a
     * instrumentação automática do driver; aqui queremos saber qual passo do fluxo está lento.
     */
    public enum OperacaoDb {
        BUSCAR_OPERACAO, ATUALIZAR_STATUS, GRAVAR_OCORRENCIA;
        public String tag() { return name().toLowerCase(); }
    }

    /** Por que o evento não pôde ser tratado. */
    public enum MotivoNaoMapeado {
        STATUS_DESCONHECIDO,   // status fora do enum (ex.: o produtor criou um status novo)
        ARRANJO_DESCONHECIDO,  // arranjo fora do enum
        FLUXO_NAO_SUPORTADO,   // status válido, mas não existe para este arranjo
        PAYLOAD_INVALIDO;      // campo obrigatório ausente
        public String tag() { return name().toLowerCase(); }
    }
}
