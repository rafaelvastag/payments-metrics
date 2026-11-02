package br.com.exemplo.pagamentos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * POC de métricas Micrometer do fluxo de eventos de pagamento.
 *
 * <p>Pacotes:
 * <ul>
 *   <li>{@code dominio}: enums, máquina de estado e o serviço que processa o evento;</li>
 *   <li>{@code metricas}: fachada de métricas, tags e o inspetor usado no modo manual;</li>
 *   <li>{@code infra}: banco e Service Bus simulados, mais o {@code Chaos} (latência/falhas);</li>
 *   <li>{@code simulador}: gerador automático de tráfego e cenários manuais;</li>
 *   <li>{@code api}: endpoints HTTP.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} habilita o {@code @Scheduled} do gerador automático.
 */
@SpringBootApplication
@EnableScheduling
public class PagamentosMetricasApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagamentosMetricasApplication.class, args);
    }
}
