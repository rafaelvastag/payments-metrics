package br.com.exemplo.pagamentos.infra;

/**
 * Falha técnica ao publicar em uma fila do Service Bus.
 * Classificada nas métricas como {@code error.type=servicebus}.
 */
public class FalhaServiceBusException extends RuntimeException {
    public FalhaServiceBusException(String msg) { super(msg); }
}
