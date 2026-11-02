package br.com.exemplo.pagamentos.infra;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.dominio.Operacao;
import br.com.exemplo.pagamentos.infra.Chaos.Alvo;
import org.springframework.stereotype.Component;

/**
 * Publisher simulado das filas de saída do Service Bus.
 *
 * <p>Em produção seria um {@code ServiceBusSenderClient} (um por fila) ou o {@code StreamBridge}
 * do Spring Cloud Stream. Assim como o repositório, não conhece métricas: a medição fica em
 * {@code PagamentoMetrics.medirEnvio(...)}.
 */
@Component
public class ServiceBusPublisher {

    private final Chaos chaos;

    public ServiceBusPublisher(Chaos chaos) {
        this.chaos = chaos;
    }

    /**
     * "Envia" a operação para a fila indicada. Pode lançar {@link FalhaServiceBusException}
     * ou {@link TimeoutDependenciaException}, conforme o {@link Chaos}.
     */
    public void enviar(String fila, Operacao operacao) {
        // TED fica 30% mais lento no envio, só para diferenciar no painel "p95 de envio".
        double multiplicador = operacao.arranjo() == Arranjo.TED ? 1.3 : 1.0;
        chaos.simularChamada(Alvo.ENVIO_SERVICEBUS, chaos.sbLatenciaMs, multiplicador, chaos.sbTaxaErro);
    }
}
