package br.com.exemplo.pagamentos.infra;

/**
 * Timeout em qualquer dependência (banco ou Service Bus).
 * Classificada nas métricas como {@code error.type=timeout}, independentemente da dependência:
 * qual dependência estourou aparece na métrica da própria dependência
 * ({@code pagamento.db.operacao} ou {@code pagamento.servicebus.envio}).
 */
public class TimeoutDependenciaException extends RuntimeException {
    public TimeoutDependenciaException(String msg) { super(msg); }
}
