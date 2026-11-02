package br.com.exemplo.pagamentos;

import br.com.exemplo.pagamentos.dominio.EventoPagamento;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService;
import br.com.exemplo.pagamentos.infra.Chaos;
import br.com.exemplo.pagamentos.infra.OperacaoRepository;
import br.com.exemplo.pagamentos.infra.ServiceBusPublisher;
import br.com.exemplo.pagamentos.metricas.PagamentoMetrics;
import br.com.exemplo.pagamentos.metricas.Tags.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa que cada caminho do fluxo gera as métricas certas, com as tags certas.
 *
 * <p>Usa {@link SimpleMeterRegistry} (registry em memória, sem Prometheus) e um {@link Chaos}
 * com latência zero e sem falhas, para os testes serem rápidos e determinísticos.
 */
class ProcessadorEventoServiceTest {

    private SimpleMeterRegistry registry;
    private Chaos chaos;
    private ProcessadorEventoService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        chaos = new Chaos(0, 0, 0, 0, 0, 0); // sem latência nem falhas
        service = new ProcessadorEventoService(new OperacaoRepository(chaos),
                new ServiceBusPublisher(chaos), new PagamentoMetrics(registry));
    }

    private EventoPagamento evento(String id, String arranjo, String status) {
        return new EventoPagamento(id, arranjo, status, Instant.now().minusMillis(20), 1);
    }

    @Test
    void fluxoFelizPixRegistraSucessoComTags() {
        // Ciclo completo do PIX: 4 eventos, todos com sucesso.
        for (String s : new String[]{"PAGAMENTO_INICIADO", "ANALISE_FRAUDE", "FRAUDE_APROVADA", "PAGAMENTO_CONCLUIDO"}) {
            assertThat(service.processar(evento("op-1", "PIX_DINAMICO", s)).outcome()).isEqualTo(Outcome.SUCCESS);
        }

        // 4 medições de sucesso, espalhadas em 4 séries (uma por status_evento): somamos todas.
        long sucessos = registry.get(PagamentoMetrics.PROCESSAMENTO)
                .tags("arranjo", "pix_dinamico", "outcome", "success").timers()
                .stream().mapToLong(t -> t.count()).sum();
        assertThat(sucessos).isEqualTo(4);
        // A conclusão publica na fila pagamento-concluido.
        assertThat(registry.get(PagamentoMetrics.SB_ENVIO)
                .tags("destino", "pagamento-concluido", "outcome", "success").timer().count()).isEqualTo(1);
        // Cada evento grava o novo status.
        assertThat(registry.get(PagamentoMetrics.DB_OPERACAO)
                .tags("operacao", "atualizar_status").timer().count()).isEqualTo(4);
        // Todos tinham enqueuedTime, então todos mediram espera na fila.
        assertThat(registry.get(PagamentoMetrics.ESPERA_FILA).timer().count()).isEqualTo(4);
    }

    @Test
    void transicaoInvalidaGravaOcorrenciaEContaComEstadoAtual() {
        service.processar(evento("op-2", "TED", "PAGAMENTO_INICIADO"));
        // Devolução sem ter concluído: INICIADA -> DEVOLVIDA não é permitido.
        var r = service.processar(evento("op-2", "TED", "PAGAMENTO_DEVOLVIDO"));

        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(registry.get(PagamentoMetrics.TRANSICAO_INVALIDA)
                .tags("arranjo", "ted", "estado_atual", "iniciada", "status_evento", "pagamento_devolvido")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.DB_OPERACAO)
                .tags("operacao", "gravar_ocorrencia").timer().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.PROCESSAMENTO)
                .tags("outcome", "rejected", "error.type", "transicao_invalida").timer().count()).isEqualTo(1);
    }

    @Test
    void cenariosNaoMapeadosUsamValorDesconhecidoNaTag() {
        service.processar(evento("op-3", "DOC", "PAGAMENTO_INICIADO"));          // arranjo desconhecido
        service.processar(evento("op-4", "PIX_ESTATICO", "PAGAMENTO_AGENDADO")); // status desconhecido
        service.processar(evento("op-5", "BOLETO", "ANALISE_FRAUDE"));           // fluxo não suportado

        // O texto recebido ("DOC") nunca vira tag: vira "desconhecido".
        assertThat(registry.get(PagamentoMetrics.NAO_MAPEADO)
                .tags("arranjo", "desconhecido", "motivo", "arranjo_desconhecido").counter().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.NAO_MAPEADO)
                .tags("motivo", "status_desconhecido").counter().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.NAO_MAPEADO)
                .tags("arranjo", "boleto", "motivo", "fluxo_nao_suportado").counter().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.PROCESSAMENTO)
                .tags("status_evento", "desconhecido").timer().count()).isEqualTo(1);
    }

    @Test
    void erroTecnicoDeServiceBusEClassificado() {
        chaos.sbTaxaErro = 1.0; // todo envio falha
        var r = service.processar(evento("op-6", "TEF", "PAGAMENTO_INICIADO"));

        assertThat(r.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(registry.get(PagamentoMetrics.SB_ENVIO)
                .tags("outcome", "error", "error.type", "servicebus").timer().count()).isEqualTo(1);
        assertThat(registry.get(PagamentoMetrics.PROCESSAMENTO)
                .tags("arranjo", "tef", "outcome", "error", "error.type", "servicebus").timer().count()).isEqualTo(1);
    }

    @Test
    void reentregaContada() {
        service.processar(new EventoPagamento("op-7", "TED", "PAGAMENTO_INICIADO", Instant.now(), 3));
        assertThat(registry.get(PagamentoMetrics.REENTREGA).tags("arranjo", "ted").counter().count()).isEqualTo(1);
    }
}
