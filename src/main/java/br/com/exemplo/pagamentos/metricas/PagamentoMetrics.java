package br.com.exemplo.pagamentos.metricas;

import br.com.exemplo.pagamentos.infra.FalhaBancoException;
import br.com.exemplo.pagamentos.infra.FalhaServiceBusException;
import br.com.exemplo.pagamentos.infra.TimeoutDependenciaException;
import br.com.exemplo.pagamentos.metricas.Tags.ErrorType;
import br.com.exemplo.pagamentos.metricas.Tags.MotivoNaoMapeado;
import br.com.exemplo.pagamentos.metricas.Tags.OperacaoDb;
import br.com.exemplo.pagamentos.metricas.Tags.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Fachada única de métricas do fluxo. Todo nome de métrica e toda chave de tag vivem aqui.
 *
 * <pre>
 * Métrica                          Tipo    Tags
 * pagamento.evento.processamento   Timer   arranjo, status_evento, outcome, error.type
 * pagamento.evento.espera_fila     Timer   arranjo
 * pagamento.db.operacao            Timer   operacao, outcome, error.type
 * pagamento.servicebus.envio       Timer   destino, arranjo, outcome, error.type
 * pagamento.transicao.invalida     Counter arranjo, estado_atual, status_evento
 * pagamento.evento.nao_mapeado     Counter arranjo, motivo
 * pagamento.evento.reentrega       Counter arranjo
 * </pre>
 *
 * <p><b>Por que não há counters de "recebidos", "sucesso" e "erro"?</b> Porque todo Timer já publica
 * {@code _count} (quantas medições), {@code _sum} (tempo total) e {@code _max} (pior caso). Com a tag
 * {@code outcome}, o {@code _count} do timer de processamento já responde quantos chegaram, quantos
 * deram certo e quantos falharam.
 *
 * <p><b>Sobre {@code Timer.builder(...).register(registry)} a cada chamada:</b> o Micrometer guarda os
 * meters em cache pelo par (nome + tags). A primeira chamada com uma combinação de tags cria a série; as
 * seguintes devolvem a mesma instância. Por isso podemos montar o timer no momento da medição, quando já
 * conhecemos o valor de todas as tags.
 *
 * <p>Os SLO buckets (histograma) não são definidos aqui, e sim no {@code application.yml}
 * ({@code management.metrics.distribution.slo}), para poderem ser ajustados sem mudar código.
 */
@Component
public class PagamentoMetrics {

    // Nomes no padrão Micrometer (pontos). O registry do Prometheus converte para
    // snake_case e acrescenta a unidade: pagamento.evento.processamento -> pagamento_evento_processamento_seconds_*
    public static final String PROCESSAMENTO = "pagamento.evento.processamento";
    public static final String ESPERA_FILA = "pagamento.evento.espera_fila";
    public static final String DB_OPERACAO = "pagamento.db.operacao";
    public static final String SB_ENVIO = "pagamento.servicebus.envio";
    public static final String TRANSICAO_INVALIDA = "pagamento.transicao.invalida";
    public static final String NAO_MAPEADO = "pagamento.evento.nao_mapeado";
    public static final String REENTREGA = "pagamento.evento.reentrega";

    private final MeterRegistry registry;

    public PagamentoMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ==========================================================================================
    // Processamento ponta a ponta
    // ==========================================================================================

    /**
     * Inicia a medição do processamento. O {@link Timer.Sample} só guarda o instante inicial; o timer
     * (com as tags) é escolhido no {@link #registrarProcessamento}, quando o resultado já é conhecido.
     */
    public Timer.Sample iniciar() {
        return Timer.start(registry);
    }

    /** Encerra a medição iniciada em {@link #iniciar()} e grava no timer com as tags do resultado. */
    public void registrarProcessamento(Timer.Sample sample, String arranjo, String statusEvento,
                                       Outcome outcome, ErrorType errorType) {
        sample.stop(Timer.builder(PROCESSAMENTO)
                .description("Tempo de processamento do evento, do recebimento ao ack")
                .tag(Tags.ARRANJO, arranjo)
                .tag(Tags.STATUS_EVENTO, statusEvento)
                .tag(Tags.OUTCOME, outcome.tag())
                .tag(Tags.ERROR_TYPE, errorType.tag())
                .register(registry));
    }

    // ==========================================================================================
    // Tempo de espera na fila (enqueuedTime -> início do processamento)
    // ==========================================================================================

    /**
     * Registra quanto tempo a mensagem ficou parada na fila antes de ser consumida.
     * Mostra backlog, coisa que o timer de processamento não enxerga (ele só começa quando a mensagem chega).
     * Duração negativa (relógios dessincronizados) é registrada como zero.
     */
    public void registrarEsperaFila(String arranjo, Duration espera) {
        Timer.builder(ESPERA_FILA)
                .description("Tempo entre o enfileiramento da mensagem e o início do processamento")
                .tag(Tags.ARRANJO, arranjo)
                .register(registry)
                .record(espera.isNegative() ? Duration.ZERO : espera);
    }

    // ==========================================================================================
    // Banco de dados
    // ==========================================================================================

    /**
     * Executa e cronometra uma operação de banco que devolve valor.
     * Sucesso: outcome=success, error.type=none. Exceção: outcome=error, error.type=categoria,
     * e a exceção é relançada sem alteração (a métrica não muda o comportamento do fluxo).
     */
    public <T> T medirDb(OperacaoDb operacao, Supplier<T> chamada) {
        Timer.Sample sample = Timer.start(registry);
        Outcome outcome = Outcome.SUCCESS;
        ErrorType erro = ErrorType.NONE;
        try {
            return chamada.get();
        } catch (RuntimeException e) {
            outcome = Outcome.ERROR;
            erro = classificar(e);
            throw e;
        } finally {
            // finally: registra tanto no caminho de sucesso quanto no de erro.
            sample.stop(Timer.builder(DB_OPERACAO)
                    .description("Tempo das operações de banco do fluxo")
                    .tag(Tags.OPERACAO, operacao.tag())
                    .tag(Tags.OUTCOME, outcome.tag())
                    .tag(Tags.ERROR_TYPE, erro.tag())
                    .register(registry));
        }
    }

    /** Variante para operações de banco sem retorno (gravações). */
    public void medirDb(OperacaoDb operacao, Runnable chamada) {
        medirDb(operacao, () -> { chamada.run(); return null; });
    }

    // ==========================================================================================
    // Service Bus (envio para as filas de saída)
    // ==========================================================================================

    /** Executa e cronometra um envio para a fila {@code destino}. Mesmo padrão de {@link #medirDb}. */
    public void medirEnvio(String destino, String arranjo, Runnable envio) {
        Timer.Sample sample = Timer.start(registry);
        Outcome outcome = Outcome.SUCCESS;
        ErrorType erro = ErrorType.NONE;
        try {
            envio.run();
        } catch (RuntimeException e) {
            outcome = Outcome.ERROR;
            erro = classificar(e);
            throw e;
        } finally {
            sample.stop(Timer.builder(SB_ENVIO)
                    .description("Tempo de envio para as filas de saída do Service Bus")
                    .tag(Tags.DESTINO, destino)
                    .tag(Tags.ARRANJO, arranjo)
                    .tag(Tags.OUTCOME, outcome.tag())
                    .tag(Tags.ERROR_TYPE, erro.tag())
                    .register(registry));
        }
    }

    // ==========================================================================================
    // Counters de negócio (eventos sem duração relevante)
    // ==========================================================================================

    /**
     * Conta uma transição recusada pela máquina de estado. As tags estado_atual × status_evento formam
     * uma matriz finita (vêm de enums), que mostra exatamente qual transição está quebrando.
     */
    public void transicaoInvalida(String arranjo, String estadoAtual, String statusEvento) {
        Counter.builder(TRANSICAO_INVALIDA)
                .description("Eventos cujo status não é uma transição válida a partir do estado salvo (gera ocorrência)")
                .tag(Tags.ARRANJO, arranjo)
                .tag(Tags.ESTADO_ATUAL, estadoAtual)
                .tag(Tags.STATUS_EVENTO, statusEvento)
                .register(registry)
                .increment();
    }

    /** Conta um evento que o fluxo não sabe tratar, separado por motivo. */
    public void naoMapeado(String arranjo, MotivoNaoMapeado motivo) {
        Counter.builder(NAO_MAPEADO)
                .description("Eventos que o fluxo não sabe tratar")
                .tag(Tags.ARRANJO, arranjo)
                .tag(Tags.MOTIVO, motivo.tag())
                .register(registry)
                .increment();
    }

    /** Conta uma mensagem que chegou de novo (deliveryCount > 1): sinal precoce de DLQ. */
    public void reentrega(String arranjo) {
        Counter.builder(REENTREGA)
                .description("Mensagens recebidas com deliveryCount > 1")
                .tag(Tags.ARRANJO, arranjo)
                .register(registry)
                .increment();
    }

    // ==========================================================================================
    // Classificação de erros
    // ==========================================================================================

    /**
     * Traduz a exceção para uma categoria finita. Em produção, mapeie aqui as exceções reais
     * (ex.: {@code MongoTimeoutException} → TIMEOUT, {@code ServiceBusException} → SERVICEBUS).
     */
    public static ErrorType classificar(Throwable e) {
        if (e instanceof TimeoutDependenciaException) return ErrorType.TIMEOUT;
        if (e instanceof FalhaBancoException) return ErrorType.DB;
        if (e instanceof FalhaServiceBusException) return ErrorType.SERVICEBUS;
        return ErrorType.DESCONHECIDO;
    }
}
