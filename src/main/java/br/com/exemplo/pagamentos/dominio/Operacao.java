package br.com.exemplo.pagamentos.dominio;

import java.time.Instant;

/**
 * Operação de pagamento como está gravada no banco. Imutável: cada mudança de estado gera uma nova instância.
 *
 * @param id           identificador da operação (NUNCA vira tag de métrica: cardinalidade infinita)
 * @param arranjo      arranjo da operação
 * @param estado       estado atual na máquina de estado
 * @param atualizadaEm momento da última mudança
 */
public record Operacao(String id, Arranjo arranjo, EstadoOperacao estado, Instant atualizadaEm) {

    /** Operação que ainda não existe no banco: começa no estado {@link EstadoOperacao#NOVA}. */
    public static Operacao nova(String id, Arranjo arranjo) {
        return new Operacao(id, arranjo, EstadoOperacao.NOVA, Instant.now());
    }

    /** Cópia da operação com o novo estado. */
    public Operacao com(EstadoOperacao novoEstado) {
        return new Operacao(id, arranjo, novoEstado, Instant.now());
    }
}
