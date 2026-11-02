package br.com.exemplo.pagamentos.api;

import br.com.exemplo.pagamentos.dominio.Arranjo;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas;
import br.com.exemplo.pagamentos.metricas.InspetorDeMetricas.Leitura;
import br.com.exemplo.pagamentos.simulador.CenariosManuais;
import br.com.exemplo.pagamentos.simulador.CenariosManuais.Execucao;
import br.com.exemplo.pagamentos.simulador.CenariosManuais.Resumo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Modo manual: gera cada métrica de forma isolada e mostra o que mudou.
 * Disponível sempre, com o gerador automático ligado ou não.
 *
 * <pre>
 * GET  /manual/cenarios                         lista os cenários e o que observar em cada um
 * POST /manual/cenarios/{id}?arranjo=PIX_DINAMICO  executa o cenário e devolve o diff de métricas
 * GET  /manual/metricas?nome=transicao          estado atual das métricas pagamento.* (legível)
 * </pre>
 */
@RestController
@RequestMapping("/manual")
public class ManualController {

    private final CenariosManuais cenarios;
    private final InspetorDeMetricas inspetor;

    public ManualController(CenariosManuais cenarios, InspetorDeMetricas inspetor) {
        this.cenarios = cenarios;
        this.inspetor = inspetor;
    }

    @GetMapping("/cenarios")
    public List<Resumo> listar() {
        return cenarios.listar();
    }

    /**
     * Executa um cenário. O arranjo é opcional (padrão PIX_DINAMICO); valor inválido devolve 400.
     * Cenário inexistente devolve 404 com a lista de ids válidos.
     */
    @PostMapping("/cenarios/{id}")
    public ResponseEntity<?> executar(@PathVariable String id,
                                      @RequestParam(defaultValue = "PIX_DINAMICO") String arranjo) {
        Arranjo a = Arranjo.parse(arranjo).orElse(null);
        if (a == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "erro", "arranjo inválido: " + arranjo,
                    "validos", List.of(Arranjo.values())));
        }
        return cenarios.executar(id, a)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "erro", "cenário não encontrado: " + id,
                        "validos", cenarios.listar().stream().map(Resumo::id).toList())));
    }

    /** Leitura atual das métricas. {@code nome} filtra por trecho do nome (ex.: "db", "transicao"). */
    @GetMapping("/metricas")
    public List<Leitura> metricas(@RequestParam(required = false) String nome) {
        return inspetor.listar(nome);
    }
}
