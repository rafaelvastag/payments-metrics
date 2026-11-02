package br.com.exemplo.pagamentos.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Simula o comportamento das dependências externas (banco e Service Bus): latência, erros e timeouts.
 *
 * <p>Funciona de dois jeitos:
 * <ul>
 *   <li><b>Aleatório</b> (padrão, usado pelo gerador automático): cada chamada sorteia latência,
 *       erro e timeout conforme as taxas configuradas. É o que deixa o dashboard "vivo".</li>
 *   <li><b>Determinístico</b> (usado pelos cenários manuais): nenhuma falha aleatória acontece;
 *       só ocorrem as falhas que o cenário pediu explicitamente para uma operação específica.
 *       Assim, cada cenário manual produz sempre as mesmas métricas.</li>
 * </ul>
 *
 * <p>O modo determinístico usa {@link ThreadLocal}: ele vale apenas para a thread que está executando
 * o cenário manual. Se o gerador automático estiver rodando ao mesmo tempo em outras threads, ele
 * continua no modo aleatório e não "rouba" as falhas forçadas do cenário.
 *
 * <p>Os parâmetros do modo aleatório podem ser alterados em tempo de execução via {@code PUT /chaos}.
 */
@Component
public class Chaos {

    /** Pontos do fluxo onde uma falha pode ser forçada por um cenário manual. */
    public enum Alvo { BUSCAR_OPERACAO, ATUALIZAR_STATUS, GRAVAR_OCORRENCIA, ENVIO_SERVICEBUS }

    /** Tipo de falha forçada: erro imediato ou timeout (espera {@link #timeoutMs} e então falha). */
    public enum TipoFalha { ERRO, TIMEOUT }

    // Falhas pedidas pelo cenário manual da thread atual. Cada falha é consumida uma única vez.
    private static final ThreadLocal<Map<Alvo, TipoFalha>> FALHAS_FORCADAS = new ThreadLocal<>();

    // Parâmetros do modo aleatório. "volatile" porque são alterados por uma thread (PUT /chaos)
    // e lidos por várias outras (requisições em andamento).
    public volatile long dbLatenciaMs;   // latência base de uma chamada ao banco
    public volatile long sbLatenciaMs;   // latência base de um envio ao Service Bus
    public volatile double dbTaxaErro;   // probabilidade (0..1) de erro em uma chamada ao banco
    public volatile double sbTaxaErro;   // probabilidade (0..1) de erro em um envio ao Service Bus
    public volatile double taxaTimeout;  // probabilidade (0..1) de timeout em qualquer dependência
    public volatile long timeoutMs;      // quanto tempo a chamada "trava" antes de estourar o timeout

    public Chaos(@Value("${chaos.db-latencia-ms:15}") long dbLatenciaMs,
                 @Value("${chaos.sb-latencia-ms:25}") long sbLatenciaMs,
                 @Value("${chaos.db-taxa-erro:0.01}") double dbTaxaErro,
                 @Value("${chaos.sb-taxa-erro:0.015}") double sbTaxaErro,
                 @Value("${chaos.taxa-timeout:0.003}") double taxaTimeout,
                 @Value("${chaos.timeout-ms:1500}") long timeoutMs) {
        this.dbLatenciaMs = dbLatenciaMs;
        this.sbLatenciaMs = sbLatenciaMs;
        this.dbTaxaErro = dbTaxaErro;
        this.sbTaxaErro = sbTaxaErro;
        this.taxaTimeout = taxaTimeout;
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------------------------------
    // Modo determinístico (cenários manuais)
    // ------------------------------------------------------------------------------------------

    /**
     * Executa {@code acao} no modo determinístico: sem falhas aleatórias, apenas as falhas em
     * {@code falhas}. Ao final, limpa o estado da thread, mesmo que a ação lance exceção.
     */
    public static <T> T deterministico(Map<Alvo, TipoFalha> falhas, Supplier<T> acao) {
        FALHAS_FORCADAS.set(new EnumMap<>(Alvo.class));
        FALHAS_FORCADAS.get().putAll(falhas);
        try {
            return acao.get();
        } finally {
            FALHAS_FORCADAS.remove();
        }
    }

    private static boolean modoDeterministico() {
        return FALHAS_FORCADAS.get() != null;
    }

    // ------------------------------------------------------------------------------------------
    // Simulação de uma chamada a dependência
    // ------------------------------------------------------------------------------------------

    /**
     * Simula uma chamada externa. Chamado pelo repositório e pelo publisher antes de "fazer" a operação.
     *
     * @param alvo          qual operação está sendo chamada (para casar com falhas forçadas)
     * @param latenciaBase  latência base em ms
     * @param multiplicador fator aplicado à latência (ex.: TED é mais lento)
     * @param taxaErro      probabilidade de erro no modo aleatório
     */
    public void simularChamada(Alvo alvo, long latenciaBase, double multiplicador, double taxaErro) {
        if (modoDeterministico()) {
            // 1) Se o cenário pediu falha para esta operação, aplica e consome (remove) a falha.
            TipoFalha forcada = FALHAS_FORCADAS.get().remove(alvo);
            if (forcada == TipoFalha.TIMEOUT) {
                dormir(timeoutMs);
                throw new TimeoutDependenciaException("timeout forçado em " + alvo);
            }
            if (forcada == TipoFalha.ERRO) {
                throw erroPara(alvo, "falha forçada em " + alvo);
            }
            // 2) Sem falha forçada: só a latência normal, sem sorteios.
            dormir((long) (latenciaBase * multiplicador));
            return;
        }

        // Modo aleatório: timeout raro, latência com variação e erro conforme a taxa.
        if (sorteia(taxaTimeout)) {
            dormir(timeoutMs);
            throw new TimeoutDependenciaException("timeout em " + alvo);
        }
        latencia(latenciaBase, multiplicador);
        if (sorteia(taxaErro)) {
            throw erroPara(alvo, "falha simulada em " + alvo);
        }
    }

    /** Escolhe a exceção certa para a dependência: Service Bus ou banco. */
    private static RuntimeException erroPara(Alvo alvo, String msg) {
        return alvo == Alvo.ENVIO_SERVICEBUS ? new FalhaServiceBusException(msg) : new FalhaBancoException(msg);
    }

    /** Latência realista: base ±50% e, em 2% dos casos, 5x mais lenta (gera a "cauda" do p99). */
    private void latencia(long baseMs, double multiplicador) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ms = baseMs * multiplicador * (0.5 + r.nextDouble());
        if (r.nextDouble() < 0.02) ms *= 5;
        dormir((long) ms);
    }

    private static boolean sorteia(double taxa) {
        return ThreadLocalRandom.current().nextDouble() < taxa;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Leitura/alteração dos parâmetros via API (PUT /chaos)
    // ------------------------------------------------------------------------------------------

    /** Fotografia imutável dos parâmetros, usada como corpo de entrada e saída da API. */
    public record Snapshot(long dbLatenciaMs, long sbLatenciaMs, double dbTaxaErro,
                           double sbTaxaErro, double taxaTimeout, long timeoutMs) {}

    public Snapshot snapshot() {
        return new Snapshot(dbLatenciaMs, sbLatenciaMs, dbTaxaErro, sbTaxaErro, taxaTimeout, timeoutMs);
    }

    public void aplicar(Snapshot s) {
        dbLatenciaMs = s.dbLatenciaMs();
        sbLatenciaMs = s.sbLatenciaMs();
        dbTaxaErro = s.dbTaxaErro();
        sbTaxaErro = s.sbTaxaErro();
        taxaTimeout = s.taxaTimeout();
        timeoutMs = s.timeoutMs();
    }
}
