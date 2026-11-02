package br.com.exemplo.pagamentos.api;

import br.com.exemplo.pagamentos.infra.Chaos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ajusta, em tempo de execução, a latência e as taxas de erro do modo aleatório do {@link Chaos}
 * (o modo usado pelo gerador automático). Não afeta os cenários manuais, que são determinísticos,
 * exceto pelas latências base e pelo {@code timeoutMs}.
 */
@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private final Chaos chaos;

    public ChaosController(Chaos chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public Chaos.Snapshot atual() {
        return chaos.snapshot();
    }

    /** Substitui todos os parâmetros. Ex.: sbTaxaErro=0.2 simula o Service Bus degradado. */
    @PutMapping
    public Chaos.Snapshot alterar(@RequestBody Chaos.Snapshot novo) {
        chaos.aplicar(novo);
        return chaos.snapshot();
    }
}
