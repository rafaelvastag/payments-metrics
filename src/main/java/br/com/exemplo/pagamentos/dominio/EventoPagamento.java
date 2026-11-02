package br.com.exemplo.pagamentos.dominio;

import java.time.Instant;

/**
 * Evento de entrada. Representa o corpo e as propriedades de uma mensagem do Service Bus.
 *
 * <p>No consumidor real:
 * <ul>
 *   <li>{@code enqueuedTime} vem de {@code ServiceBusReceivedMessage.getEnqueuedTime()};</li>
 *   <li>{@code deliveryCount} vem de {@code ServiceBusReceivedMessage.getDeliveryCount()}.</li>
 * </ul>
 *
 * <p>{@code arranjo} e {@code status} são {@code String} (e não os enums) de propósito: assim
 * conseguimos receber valores desconhecidos e contá-los como "não mapeado" em vez de falhar
 * na desserialização.
 *
 * @param operacaoId    id da operação a que o evento se refere
 * @param arranjo       arranjo (ex.: "PIX_DINAMICO")
 * @param status        status pedido (ex.: "PAGAMENTO_CONCLUIDO")
 * @param enqueuedTime  quando a mensagem entrou na fila (opcional; usado na métrica de espera)
 * @param deliveryCount quantas vezes a mensagem já foi entregue (opcional; > 1 = reentrega)
 */
public record EventoPagamento(
        String operacaoId,
        String arranjo,
        String status,
        Instant enqueuedTime,
        Integer deliveryCount) {
}
