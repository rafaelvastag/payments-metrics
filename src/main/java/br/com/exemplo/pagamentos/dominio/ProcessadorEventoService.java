package br.com.exemplo.pagamentos.dominio;

import br.com.exemplo.pagamentos.infra.OperacaoRepository;
import br.com.exemplo.pagamentos.infra.OperacaoRepository.Ocorrencia;
import br.com.exemplo.pagamentos.infra.ServiceBusPublisher;
import br.com.exemplo.pagamentos.metricas.PagamentoMetrics;
import br.com.exemplo.pagamentos.metricas.Tags.ErrorType;
import br.com.exemplo.pagamentos.metricas.Tags.MotivoNaoMapeado;
import br.com.exemplo.pagamentos.metricas.Tags.OperacaoDb;
import br.com.exemplo.pagamentos.metricas.Tags.Outcome;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fluxo de processamento de um evento: equivale ao handler do consumidor do Service Bus.
 *
 * <ol>
 *   <li>Mede o tempo ponta a ponta (timer iniciado na entrada, fechado em um único ponto na saída).</li>
 *   <li>Registra sinais da fila: tempo de espera e reentrega.</li>
 *   <li>Valida arranjo, status e se o status existe no fluxo do arranjo (senão: "não mapeado").</li>
 *   <li>Busca a operação no banco.</li>
 *   <li>Aplica a máquina de estado (transição inválida: grava ocorrência e conta).</li>
 *   <li>Grava o novo estado.</li>
 *   <li>Publica na fila de saída correspondente.</li>
 * </ol>
 *
 * <p>Toda medição passa por {@link PagamentoMetrics}; este serviço não usa o {@code MeterRegistry} diretamente.
 */
@Service
public class ProcessadorEventoService {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorEventoService.class);

    /**
     * Resultado do processamento, devolvido ao chamador (e, na POC, como corpo da resposta HTTP).
     *
     * @param outcome        success / rejected / error: o mesmo valor publicado na tag {@code outcome}
     * @param errorType      categoria do erro: o mesmo valor publicado na tag {@code error.type}
     * @param estadoAnterior estado salvo antes do evento (null se não chegou a buscar)
     * @param estadoNovo     estado depois do evento (null se não houve transição)
     * @param detalhe        texto livre para depuração (nunca vira tag)
     */
    public record Resultado(Outcome outcome, ErrorType errorType, String estadoAnterior,
                            String estadoNovo, String detalhe) {}

    private final OperacaoRepository repository;
    private final ServiceBusPublisher publisher;
    private final PagamentoMetrics metrics;

    public ProcessadorEventoService(OperacaoRepository repository, ServiceBusPublisher publisher,
                                    PagamentoMetrics metrics) {
        this.repository = repository;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public Resultado processar(EventoPagamento evento) {
        // (1) Inicia o cronômetro ANTES de qualquer trabalho. O Timer.Sample guarda o instante inicial;
        // o timer de destino (com as tags) só é escolhido no final, quando sabemos o outcome.
        Timer.Sample sample = metrics.iniciar();

        // Converte arranjo/status para os valores de tag. Valor desconhecido vira "desconhecido",
        // nunca o texto recebido: isso evita que um produtor com bug crie séries novas sem limite.
        Optional<Arranjo> arranjoOpt = Arranjo.parse(evento.arranjo());
        Optional<StatusEvento> statusOpt = StatusEvento.parse(evento.status());
        String arranjoTag = arranjoOpt.map(Arranjo::tag).orElse(Arranjo.TAG_DESCONHECIDO);
        String statusTag = statusOpt.map(StatusEvento::tag).orElse(StatusEvento.TAG_DESCONHECIDO);

        Resultado resultado;
        try {
            // (2) Sinais da fila de entrada.
            registrarSinaisDeFila(evento, arranjoTag);
            // (3) a (7): o fluxo de negócio. Rejeições de regra voltam como Resultado, não como exceção.
            resultado = executar(evento, arranjoOpt, statusOpt, arranjoTag);
        } catch (RuntimeException e) {
            // Qualquer exceção aqui é falha TÉCNICA (banco, Service Bus, timeout).
            // Classificamos por categoria; a mensagem da exceção vai só para o log.
            ErrorType tipo = PagamentoMetrics.classificar(e);
            log.warn("Erro técnico ao processar operacao={} arranjo={} status={}: {}",
                    evento.operacaoId(), arranjoTag, statusTag, e.getMessage());
            resultado = new Resultado(Outcome.ERROR, tipo, null, null, e.getMessage());
        }

        // Ponto ÚNICO de fechamento do timer: garante exatamente uma medição por mensagem,
        // em qualquer caminho (sucesso, rejeição ou erro).
        metrics.registrarProcessamento(sample, arranjoTag, statusTag, resultado.outcome(), resultado.errorType());
        return resultado;
    }

    /** Tempo de espera na fila (enqueuedTime → agora) e contagem de reentregas. */
    private void registrarSinaisDeFila(EventoPagamento evento, String arranjoTag) {
        if (evento.enqueuedTime() != null) {
            metrics.registrarEsperaFila(arranjoTag, Duration.between(evento.enqueuedTime(), Instant.now()));
        }
        // deliveryCount começa em 1. Acima disso, a mensagem já falhou antes e voltou para a fila.
        if (evento.deliveryCount() != null && evento.deliveryCount() > 1) {
            metrics.reentrega(arranjoTag);
        }
    }

    private Resultado executar(EventoPagamento evento, Optional<Arranjo> arranjoOpt,
                               Optional<StatusEvento> statusOpt, String arranjoTag) {
        // (3) Validações de domínio. Todas são "não mapeado": o fluxo não sabe tratar o evento.
        // São rejeições de regra (outcome=rejected), não erros técnicos, e não chegam a tocar o banco.
        if (evento.operacaoId() == null || evento.operacaoId().isBlank()) {
            return naoMapeado(arranjoTag, MotivoNaoMapeado.PAYLOAD_INVALIDO, ErrorType.VALIDACAO, "operacaoId ausente");
        }
        if (arranjoOpt.isEmpty()) {
            return naoMapeado(arranjoTag, MotivoNaoMapeado.ARRANJO_DESCONHECIDO, ErrorType.NAO_MAPEADO,
                    "arranjo não mapeado: " + evento.arranjo());
        }
        if (statusOpt.isEmpty()) {
            return naoMapeado(arranjoTag, MotivoNaoMapeado.STATUS_DESCONHECIDO, ErrorType.NAO_MAPEADO,
                    "status não mapeado: " + evento.status());
        }
        Arranjo arranjo = arranjoOpt.get();
        StatusEvento status = statusOpt.get();
        // Status válido, mas que não existe no fluxo deste arranjo (ex.: antifraude em TED).
        if (status.ehDeAntifraude() && !arranjo.passaPorAntifraude()) {
            return naoMapeado(arranjoTag, MotivoNaoMapeado.FLUXO_NAO_SUPORTADO, ErrorType.NAO_MAPEADO,
                    "status " + status + " não existe no fluxo de " + arranjo);
        }

        // (4) Busca a operação. medirDb cronometra a chamada e registra sucesso/erro com a tag operacao.
        // Se não existe no banco, é o primeiro evento da operação: começa em NOVA.
        Operacao atual = metrics.medirDb(OperacaoDb.BUSCAR_OPERACAO,
                        () -> repository.buscar(evento.operacaoId(), arranjo))
                .orElseGet(() -> Operacao.nova(evento.operacaoId(), arranjo));

        // (5) Máquina de estado.
        Optional<EstadoOperacao> novoEstado = MaquinaDeEstado.transicionar(atual.estado(), status);
        if (novoEstado.isEmpty()) {
            // Transição inválida: primeiro grava a ocorrência (medida como operação de banco)...
            metrics.medirDb(OperacaoDb.GRAVAR_OCORRENCIA, () -> repository.gravarOcorrencia(
                    new Ocorrencia(atual.id(), arranjo, atual.estado(), status.name(), Instant.now())));
            // ...e só depois conta. Se a gravação falhar, a exceção sobe, o counter NÃO é incrementado
            // e o processamento termina como erro técnico (db/timeout), que é o que realmente aconteceu.
            metrics.transicaoInvalida(arranjo.tag(), atual.estado().tag(), status.tag());
            return new Resultado(Outcome.REJECTED, ErrorType.TRANSICAO_INVALIDA, atual.estado().name(), null,
                    "transição " + atual.estado() + " -> " + status + " não permitida; ocorrência gravada");
        }

        // (6) Grava o novo estado.
        Operacao atualizada = atual.com(novoEstado.get());
        metrics.medirDb(OperacaoDb.ATUALIZAR_STATUS, () -> repository.salvar(atualizada));

        // (7) Publica na fila de saída. medirEnvio cronometra e registra sucesso/erro por destino.
        String destino = MaquinaDeEstado.filaDestino(atualizada.estado());
        metrics.medirEnvio(destino, arranjo.tag(), () -> publisher.enviar(destino, atualizada));

        return new Resultado(Outcome.SUCCESS, ErrorType.NONE, atual.estado().name(),
                atualizada.estado().name(), "publicado em " + destino);
    }

    /** Conta o evento não mapeado e devolve o resultado de rejeição correspondente. */
    private Resultado naoMapeado(String arranjoTag, MotivoNaoMapeado motivo, ErrorType tipo, String detalhe) {
        metrics.naoMapeado(arranjoTag, motivo);
        return new Resultado(Outcome.REJECTED, tipo, null, null, detalhe);
    }
}
