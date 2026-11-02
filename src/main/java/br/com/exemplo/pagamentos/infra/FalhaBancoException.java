package br.com.exemplo.pagamentos.infra;

/**
 * Falha técnica do banco (conexão, escrita recusada etc.).
 * Classificada nas métricas como {@code error.type=db}.
 */
public class FalhaBancoException extends RuntimeException {
    public FalhaBancoException(String msg) { super(msg); }
}
