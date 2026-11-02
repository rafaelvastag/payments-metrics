package br.com.exemplo.pagamentos.infra;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.dominio.EstadoOperacao;
import br.com.exemplo.pagamentos.dominio.Operacao;
import br.com.exemplo.pagamentos.infra.Chaos.Alvo;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Banco" em memória com latência e falhas simuladas pelo {@link Chaos}.
 *
 * <p>Em produção, aqui estaria o repositório real (Mongo, JPA etc.). Repare que este repositório
 * <b>não conhece métricas</b>: a medição fica na camada de serviço, via
 * {@code PagamentoMetrics.medirDb(...)}. Assim, trocar a implementação do banco não muda as métricas.
 */
@Repository
public class OperacaoRepository {

    /** Registro gravado quando a máquina de estado recusa uma transição. */
    public record Ocorrencia(String operacaoId, Arranjo arranjo, EstadoOperacao estadoAtual,
                             String statusEvento, Instant em) {}

    // "Tabela" de operações, indexada pelo id. ConcurrentHashMap porque várias threads processam ao mesmo tempo.
    private final Map<String, Operacao> operacoes = new ConcurrentHashMap<>();
    // Só contamos as ocorrências; na POC não precisamos guardá-las.
    private final AtomicLong ocorrencias = new AtomicLong();
    private final Chaos chaos;

    public OperacaoRepository(Chaos chaos) {
        this.chaos = chaos;
    }

    /** Busca a operação pelo id. Leitura é ~30% mais rápida que escrita (peso 0.7). */
    public Optional<Operacao> buscar(String id, Arranjo arranjo) {
        chaos.simularChamada(Alvo.BUSCAR_OPERACAO, chaos.dbLatenciaMs, 0.7 * multiplicador(arranjo), chaos.dbTaxaErro);
        return Optional.ofNullable(operacoes.get(id));
    }

    /** Grava (insere ou atualiza) a operação com o novo estado. */
    public void salvar(Operacao operacao) {
        chaos.simularChamada(Alvo.ATUALIZAR_STATUS, chaos.dbLatenciaMs, multiplicador(operacao.arranjo()), chaos.dbTaxaErro);
        operacoes.put(operacao.id(), operacao);
        // Proteção da POC: evita que a memória cresça sem limite com o gerador ligado por horas.
        if (operacoes.size() > 200_000) operacoes.clear();
    }

    /** Grava a ocorrência de falha de transição. */
    public void gravarOcorrencia(Ocorrencia ocorrencia) {
        chaos.simularChamada(Alvo.GRAVAR_OCORRENCIA, chaos.dbLatenciaMs, multiplicador(ocorrencia.arranjo()), chaos.dbTaxaErro);
        ocorrencias.incrementAndGet();
    }

    public long totalOcorrencias() {
        return ocorrencias.get();
    }

    /**
     * Cada arranjo tem um perfil de latência diferente, só para os painéis "por arranjo"
     * mostrarem diferenças visíveis. Não representa nenhum comportamento real.
     */
    static double multiplicador(Arranjo arranjo) {
        return switch (arranjo) {
            case TED -> 1.8;
            case BOLETO -> 1.4;
            case PIX_AUTOMATICO -> 1.2;
            default -> 1.0;
        };
    }
}
