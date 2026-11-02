package br.com.exemplo.pagamentos.metricas;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lê as métricas do fluxo direto do {@link MeterRegistry}, em formato legível, e calcula o que mudou
 * entre duas leituras. É a base do modo manual: tira uma "foto" antes do cenário, outra depois,
 * e mostra só as séries que mudaram.
 *
 * <p>Ferramenta de aprendizado e depuração. Não é necessária no serviço de produção.
 */
@Component
public class InspetorDeMetricas {

    /** Tags comuns (aplicadas a todas as métricas via application.yml): omitidas para não poluir a saída. */
    private static final Set<String> TAGS_COMUNS = Set.of("application", "env");

    /**
     * Estado de uma série (métrica + combinação de tags) em um instante.
     *
     * @param metrica      nome Micrometer (ex.: pagamento.db.operacao)
     * @param tipo         TIMER ou COUNTER
     * @param tags         tags da série, sem as tags comuns
     * @param contagem     Timer: quantas medições (_count). Counter: valor acumulado (_total)
     * @param tempoTotalMs só Timer: soma das durações (_sum), em ms
     * @param maxMs        só Timer: maior duração recente (_max), em ms
     */
    public record Leitura(String metrica, String tipo, Map<String, String> tags,
                          double contagem, Double tempoTotalMs, Double maxMs) {}

    /**
     * O que mudou em uma série durante o cenário.
     *
     * @param delta                 quanto a contagem aumentou
     * @param tempoNoCenarioMs      só Timer: tempo total acumulado pelas medições do cenário
     * @param mediaNoCenarioMs      só Timer: tempo médio de cada medição do cenário
     */
    public record Variacao(String metrica, String tipo, Map<String, String> tags,
                           double antes, double depois, double delta,
                           Double tempoNoCenarioMs, Double mediaNoCenarioMs) {}

    private final MeterRegistry registry;

    public InspetorDeMetricas(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Fotografa todas as séries {@code pagamento.*}, opcionalmente filtrando por nome.
     * A chave do mapa identifica a série (nome + tags) para comparar depois.
     */
    public Map<String, Leitura> fotografar(String filtroNome) {
        Map<String, Leitura> foto = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            String nome = meter.getId().getName();
            if (!nome.startsWith("pagamento.")) continue;
            if (filtroNome != null && !filtroNome.isBlank() && !nome.contains(filtroNome)) continue;

            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag t : meter.getId().getTags()) {
                if (!TAGS_COMUNS.contains(t.getKey())) tags.put(t.getKey(), t.getValue());
            }

            Leitura leitura;
            if (meter instanceof Timer timer) {
                leitura = new Leitura(nome, "TIMER", tags, timer.count(),
                        arredondar(timer.totalTime(TimeUnit.MILLISECONDS)),
                        arredondar(timer.max(TimeUnit.MILLISECONDS)));
            } else if (meter instanceof Counter counter) {
                leitura = new Leitura(nome, "COUNTER", tags, counter.count(), null, null);
            } else {
                continue; // não usamos outros tipos de meter neste fluxo
            }
            foto.put(chave(nome, tags), leitura);
        }
        return foto;
    }

    /** Todas as leituras atuais, ordenadas por métrica e tags. */
    public List<Leitura> listar(String filtroNome) {
        List<Leitura> lista = new ArrayList<>(fotografar(filtroNome).values());
        lista.sort(Comparator.comparing(Leitura::metrica).thenComparing(l -> l.tags().toString()));
        return lista;
    }

    /**
     * Compara duas fotos e devolve só as séries cuja contagem mudou. Séries que não existiam
     * em {@code antes} (criadas durante o cenário) aparecem com {@code antes = 0}.
     */
    public List<Variacao> comparar(Map<String, Leitura> antes, Map<String, Leitura> depois) {
        List<Variacao> variacoes = new ArrayList<>();
        for (Map.Entry<String, Leitura> e : depois.entrySet()) {
            Leitura d = e.getValue();
            Leitura a = antes.get(e.getKey());
            double contagemAntes = a == null ? 0 : a.contagem();
            double delta = d.contagem() - contagemAntes;
            if (delta == 0) continue;

            Double tempo = null, media = null;
            if ("TIMER".equals(d.tipo())) {
                double tempoAntes = a == null ? 0 : a.tempoTotalMs();
                tempo = arredondar(d.tempoTotalMs() - tempoAntes);
                media = arredondar(tempo / delta);
            }
            variacoes.add(new Variacao(d.metrica(), d.tipo(), d.tags(), contagemAntes, d.contagem(), delta, tempo, media));
        }
        variacoes.sort(Comparator.comparing(Variacao::metrica).thenComparing(v -> v.tags().toString()));
        return variacoes;
    }

    private static String chave(String nome, Map<String, String> tags) {
        return nome + tags;
    }

    private static double arredondar(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
