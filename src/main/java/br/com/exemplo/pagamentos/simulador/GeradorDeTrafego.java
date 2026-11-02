package br.com.exemplo.pagamentos.simulador;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.dominio.EventoPagamento;
import br.com.exemplo.pagamentos.dominio.StatusEvento;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static br.com.exemplo.pagamentos.dominio.StatusEvento.*;

/**
 * Gerador AUTOMÁTICO de tráfego: o "modo geração de métricas".
 *
 * <p>Só existe quando {@code simulador.automatico=true} (padrão). Com {@code false}, este bean nem é
 * criado e as métricas só mudam pelos cenários manuais ({@code /manual/cenarios}) ou por
 * {@code POST /eventos}.
 *
 * <p>A cada 100ms dispara chamadas HTTP contra {@code POST /eventos}, simulando o ciclo de vida real
 * das operações por arranjo, com anomalias realistas:
 * <ul>
 *   <li>evento duplicado ou fora de ordem → transição inválida;</li>
 *   <li>status e arranjo desconhecidos, fluxo não suportado → não mapeado;</li>
 *   <li>reentrega quando o processamento devolve erro técnico (503), até 5 tentativas ("DLQ").</li>
 * </ul>
 * As falhas técnicas (banco/Service Bus) vêm do {@code Chaos} no modo aleatório.
 */
@Component
@ConditionalOnProperty(name = "simulador.automatico", havingValue = "true", matchIfMissing = true)
public class GeradorDeTrafego {

    private static final Logger log = LoggerFactory.getLogger(GeradorDeTrafego.class);
    private static final int MAX_ENTREGAS = 5; // depois disso, a mensagem é abandonada (iria para a DLQ)

    private final RestClient http;
    // Virtual threads: cada evento é uma chamada HTTP bloqueante; com virtual threads isso é barato.
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    // Operações "em andamento": cada uma tem seu roteiro de status a percorrer.
    private final List<Roteiro> ativos = new ArrayList<>();
    // Só começa a gerar depois que a aplicação subiu (o endpoint HTTP precisa estar no ar).
    private final AtomicBoolean pronto = new AtomicBoolean(false);

    private volatile boolean ligado = true;    // liga/desliga em tempo de execução (/simulador/ligar|desligar)
    private volatile int eventosPorSegundo;    // vazão alvo
    private volatile long atrasoFilaMs;        // atraso extra no enqueuedTime, para simular backlog

    public GeradorDeTrafego(@Value("${server.port:8080}") int porta,
                            @Value("${simulador.eventos-por-segundo:40}") int eventosPorSegundo) {
        this.eventosPorSegundo = eventosPorSegundo;
        this.http = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                // 422 e 503 são respostas esperadas; não queremos exceção por causa delas.
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        pronto.set(true);
        log.info("Gerador automático LIGADO a {} eventos/s (simulador.automatico=true)", eventosPorSegundo);
    }

    /**
     * Roda a cada 100ms e dispara ~eventosPorSegundo/10 eventos. A parte fracionária é sorteada,
     * para que 15 eventos/s resultem, em média, em 1,5 por tick.
     */
    @Scheduled(fixedRate = 100)
    public void tick() {
        if (!ligado || !pronto.get()) return;
        double porTick = eventosPorSegundo / 10.0;
        int quantidade = (int) porTick + (ThreadLocalRandom.current().nextDouble() < porTick % 1 ? 1 : 0);
        for (int i = 0; i < quantidade; i++) {
            Roteiro r = proximoRoteiro();
            if (r != null) executor.submit(() -> enviar(r));
        }
    }

    /**
     * Escolhe qual operação recebe o próximo evento: às vezes cria uma nova, às vezes avança uma existente.
     * Cada roteiro só tem um evento "em voo" por vez (flag {@code ocupado}), para preservar a ordem.
     */
    private synchronized Roteiro proximoRoteiro() {
        ativos.removeIf(Roteiro::terminado);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // Mantém um "estoque" de operações em andamento proporcional à vazão.
        if (ativos.size() < Math.max(5, eventosPorSegundo) || rnd.nextDouble() < 0.3) {
            Roteiro novo = Roteiro.novo();
            ativos.add(novo);
            if (novo.ocupado.compareAndSet(false, true)) return novo;
        }
        for (int tentativa = 0; tentativa < 5; tentativa++) {
            Roteiro r = ativos.get(rnd.nextInt(ativos.size()));
            if (r.ocupado.compareAndSet(false, true)) return r;
        }
        return null; // todos ocupados neste instante; tenta de novo no próximo tick
    }

    /** Monta o evento do próximo passo do roteiro (às vezes com anomalia), envia e trata a resposta. */
    private void enviar(Roteiro r) {
        try {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            String arranjo = r.arranjo.name();
            String status = r.proximo().name();
            boolean anomalia = false;

            // Anomalias só na 1ª entrega (reentregas repetem a mensagem original).
            double sorteio = rnd.nextDouble();
            if (r.entregas == 1) {
                if (sorteio < 0.03 && r.passo > 0) {                              // 3%: repete o status anterior (duplicado)
                    status = r.passos.get(r.passo - 1).name(); anomalia = true;
                } else if (sorteio < 0.05 && r.passo + 1 < r.passos.size()) {     // 2%: pula um status (fora de ordem)
                    status = r.passos.get(r.passo + 1).name(); anomalia = true;
                } else if (sorteio < 0.057) {                                     // 0,7%: status desconhecido
                    status = "PAGAMENTO_AGENDADO"; anomalia = true;
                } else if (sorteio < 0.060) {                                     // 0,3%: arranjo desconhecido
                    arranjo = "DOC"; anomalia = true;
                } else if (sorteio < 0.063 && !r.arranjo.passaPorAntifraude()) {  // antifraude em arranjo sem antifraude
                    status = ANALISE_FRAUDE.name(); anomalia = true;
                }
            }

            // enqueuedTime no passado: 2 a 82ms de espera "natural" + atraso de backlog configurado.
            Instant enqueued = Instant.now().minusMillis(2 + rnd.nextLong(80) + atrasoFilaMs);
            EventoPagamento evento = new EventoPagamento(r.id, arranjo, status, enqueued, r.entregas);

            int codigo = http.post().uri("/eventos")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(evento)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode().value();

            if (anomalia) return;                // anomalia não altera o roteiro da operação
            if (codigo == 503) {                 // erro técnico: simula a reentrega do Service Bus
                r.entregas++;
                if (r.entregas > MAX_ENTREGAS) r.abandonar();
            } else {                             // 200 ou 422: mensagem consumida, segue o roteiro
                r.avancar();
            }
        } catch (Exception e) {
            log.debug("Falha no simulador: {}", e.getMessage());
        } finally {
            r.ocupado.set(false);
        }
    }

    // ---------------- controle (usado pelo SimuladorController) ----------------
    public void ligar() { ligado = true; }
    public void desligar() { ligado = false; }
    public boolean ligado() { return ligado; }
    public void taxa(int eps) { eventosPorSegundo = Math.max(0, eps); }
    public void atrasoFila(long ms) { atrasoFilaMs = Math.max(0, ms); }
    public String estado() {
        return "ligado=%s, eventosPorSegundo=%d, atrasoFilaMs=%d".formatted(ligado, eventosPorSegundo, atrasoFilaMs);
    }

    @PreDestroy
    void parar() { executor.shutdownNow(); }

    /**
     * Sequência de status de uma operação, sorteada conforme o arranjo, com probabilidades realistas
     * de desfecho (concluído, rejeitado, fraude reprovada, cancelado, devolvido).
     */
    static final class Roteiro {
        final String id = UUID.randomUUID().toString();
        final Arranjo arranjo;
        final List<StatusEvento> passos;
        final AtomicBoolean ocupado = new AtomicBoolean(false);
        volatile int passo = 0;     // índice do próximo status a enviar
        volatile int entregas = 1;  // deliveryCount da mensagem atual

        private Roteiro(Arranjo arranjo, List<StatusEvento> passos) {
            this.arranjo = arranjo;
            this.passos = passos;
        }

        static Roteiro novo() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            // Distribuição de volume por arranjo: PIX domina, como na vida real.
            double a = rnd.nextDouble();
            Arranjo arranjo = a < 0.35 ? Arranjo.PIX_DINAMICO
                    : a < 0.60 ? Arranjo.PIX_ESTATICO
                    : a < 0.68 ? Arranjo.PIX_AUTOMATICO
                    : a < 0.80 ? Arranjo.TED
                    : a < 0.90 ? Arranjo.TEF
                    : Arranjo.BOLETO;

            List<StatusEvento> passos = new ArrayList<>();
            passos.add(PAGAMENTO_INICIADO);
            double p = rnd.nextDouble();
            if (arranjo.passaPorAntifraude()) {
                passos.add(ANALISE_FRAUDE);
                if (p < 0.06) {
                    passos.add(FRAUDE_REPROVADA);                 // 6% reprovado na fraude
                } else if (p < 0.09) {
                    passos.add(CANCELADO);                        // 3% cancelado durante a análise
                } else {
                    passos.add(FRAUDE_APROVADA);
                    if (p < 0.13) passos.add(PAGAMENTO_REJEITADO);   // 4% rejeitado depois da aprovação
                    else {
                        passos.add(PAGAMENTO_CONCLUIDO);
                        if (p > 0.97) passos.add(PAGAMENTO_DEVOLVIDO); // 3% devolvido depois de concluído
                    }
                }
            } else {
                if (p < 0.05) passos.add(PAGAMENTO_REJEITADO);
                else if (p < 0.07) passos.add(CANCELADO);
                else {
                    passos.add(PAGAMENTO_CONCLUIDO);
                    if (p > 0.98) passos.add(PAGAMENTO_DEVOLVIDO);
                }
            }
            return new Roteiro(arranjo, passos);
        }

        StatusEvento proximo() { return passos.get(Math.min(passo, passos.size() - 1)); }
        void avancar() { passo++; entregas = 1; }
        void abandonar() { passo = passos.size(); }
        boolean terminado() { return passo >= passos.size(); }
    }
}
