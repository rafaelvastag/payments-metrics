package br.com.exemplo.pagamentos.api;

import br.com.exemplo.pagamentos.dominio.EventoPagamento;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService;
import br.com.exemplo.pagamentos.dominio.ProcessadorEventoService.Resultado;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP que substitui o consumidor do Service Bus na POC.
 *
 * <p>O código HTTP espelha o que o consumidor real faria com a mensagem:
 * <ul>
 *   <li>200 (success) → complete: mensagem processada.</li>
 *   <li>422 (rejected) → complete: regra recusou, ocorrência já registrada; reprocessar não adianta.</li>
 *   <li>503 (error) → abandon: falha técnica, a mensagem volta para a fila (reentrega).</li>
 * </ul>
 */
@RestController
@RequestMapping("/eventos")
public class EventoController {

    private final ProcessadorEventoService processador;

    public EventoController(ProcessadorEventoService processador) {
        this.processador = processador;
    }

    @PostMapping
    public ResponseEntity<Resultado> receber(@RequestBody EventoPagamento evento) {
        Resultado r = processador.processar(evento);
        HttpStatus http = switch (r.outcome()) {
            case SUCCESS -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(http).body(r);
    }
}
