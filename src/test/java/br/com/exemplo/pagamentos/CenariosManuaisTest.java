package br.com.exemplo.pagamentos;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService;
import br.com.exemplo.pagamentos.infra.Chaos;
import br.com.exemplo.pagamentos.infra.OperacaoRepository;
import br.com.exemplo.pagamentos.infra.ServiceBusPublisher;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas.Variacao;
import br.com.exemplo.pagamentos.metricas.PagamentoMetrics;
import br.com.exemplo.pagamentos.metricas.Tags.Outcome;
import br.com.exemplo.pagamentos.simulador.CenariosManuais;
import br.com.exemplo.pagamentos.simulador.GeradorDeTrafego;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa os cenários manuais: cada um deve ser determinístico e o diff deve mostrar exatamente
 * as métricas que o cenário promete em "oQueObservar".
 */
class CenariosManuaisTest {

    private CenariosManuais cenarios;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Taxas de erro em 100%: prova que o modo determinístico ignora as falhas aleatórias.
        Chaos chaos = new Chaos(0, 0, 1.0, 1.0, 1.0, 0);
        ProcessadorEventoService processador = new ProcessadorEventoService(
                new OperacaoRepository(chaos), new ServiceBusPublisher(chaos), new PagamentoMetrics(registry));
        // Sem gerador automático (como no modo manual).
        var semGerador = new StaticListableBeanFactory().getBeanProvider(GeradorDeTrafego.class);
        cenarios = new CenariosManuais(processador, new InspetorDeMetricas(registry), semGerador);
    }

    private static double delta(List<Variacao> vs, String metrica, String tagsContem) {
        return vs.stream()
                .filter(v -> v.metrica().equals(metrica) && v.tags().toString().contains(tagsContem))
                .mapToDouble(Variacao::delta).sum();
    }

    @Test
    void fluxoFelizIgnoraFalhasAleatorias() {
        var exec = cenarios.executar("fluxo-feliz", Arranjo.TED).orElseThrow();

        assertThat(exec.passos()).hasSize(2);
        assertThat(exec.passos()).allMatch(p -> p.resultado().outcome() == Outcome.SUCCESS);
        assertThat(exec.aviso()).isNull();
        assertThat(delta(exec.metricasAlteradas(), PagamentoMetrics.PROCESSAMENTO, "outcome=success")).isEqualTo(2);
    }

    @Test
    void transicaoInvalidaAparecenoDiff() {
        var vs = cenarios.executar("transicao-invalida", Arranjo.PIX_DINAMICO).orElseThrow().metricasAlteradas();

        assertThat(delta(vs, PagamentoMetrics.TRANSICAO_INVALIDA, "estado_atual=iniciada")).isEqualTo(1);
        assertThat(delta(vs, PagamentoMetrics.DB_OPERACAO, "operacao=gravar_ocorrencia")).isEqualTo(1);
    }

    @Test
    void falhaAoGravarOcorrenciaNaoContaTransicao() {
        var vs = cenarios.executar("falha-gravar-ocorrencia", Arranjo.TEF).orElseThrow().metricasAlteradas();

        assertThat(delta(vs, PagamentoMetrics.TRANSICAO_INVALIDA, "")).isZero();
        assertThat(delta(vs, PagamentoMetrics.DB_OPERACAO, "outcome=error")).isEqualTo(1);
        assertThat(delta(vs, PagamentoMetrics.PROCESSAMENTO, "error.type=db")).isEqualTo(1);
    }

    @Test
    void erroServiceBusComReentregaGeraTransicaoInvalidaEmCascata() {
        var vs = cenarios.executar("erro-servicebus-com-reentrega", Arranjo.BOLETO).orElseThrow().metricasAlteradas();

        assertThat(delta(vs, PagamentoMetrics.SB_ENVIO, "error.type=servicebus")).isEqualTo(1);
        assertThat(delta(vs, PagamentoMetrics.REENTREGA, "arranjo=boleto")).isEqualTo(1);
        assertThat(delta(vs, PagamentoMetrics.TRANSICAO_INVALIDA, "status_evento=pagamento_iniciado")).isEqualTo(1);
    }

    @Test
    void todosOsCenariosExecutam() {
        for (var c : cenarios.listar()) {
            var exec = cenarios.executar(c.id(), Arranjo.PIX_ESTATICO).orElseThrow();
            assertThat(exec.metricasAlteradas()).as(c.id()).isNotEmpty();
        }
    }

    @Test
    void cenarioInexistente() {
        assertThat(cenarios.executar("nao-existe", Arranjo.TED)).isEmpty();
    }
}
