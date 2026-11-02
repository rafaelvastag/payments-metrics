package br.com.exemplo.pagamentos.api;

import br.com.exemplo.pagamentos.simulador.GeradorDeTrafego;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controle do gerador automático em tempo de execução.
 * Só existe quando {@code simulador.automatico=true}, junto com o próprio gerador.
 */
@RestController
@RequestMapping("/simulador")
@ConditionalOnProperty(name = "simulador.automatico", havingValue = "true", matchIfMissing = true)
public class SimuladorController {

    private final GeradorDeTrafego gerador;

    public SimuladorController(GeradorDeTrafego gerador) {
        this.gerador = gerador;
    }

    /** Estado atual: ligado, vazão e atraso de fila. */
    @GetMapping
    public String estado() { return gerador.estado(); }

    /** Pausa a geração (útil para rodar cenários manuais sem desligar a aplicação). */
    @PostMapping("/desligar")
    public String desligar() { gerador.desligar(); return gerador.estado(); }

    @PostMapping("/ligar")
    public String ligar() { gerador.ligar(); return gerador.estado(); }

    /**
     * Ajusta a vazão e/ou o atraso de fila.
     * Ex.: {@code PUT /simulador?eventosPorSegundo=80&atrasoFilaMs=3000} simula backlog de 3s.
     */
    @PutMapping
    public String ajustar(@RequestParam(required = false) Integer eventosPorSegundo,
                          @RequestParam(required = false) Long atrasoFilaMs) {
        if (eventosPorSegundo != null) gerador.taxa(eventosPorSegundo);
        if (atrasoFilaMs != null) gerador.atrasoFila(atrasoFilaMs);
        return gerador.estado();
    }
}
