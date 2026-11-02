package br.com.exemplo.pagamentos.dominio;

import java.util.Arrays;
import java.util.Optional;

/**
 * Arranjos de pagamento suportados pelo fluxo.
 *
 * <p>Este enum é a fonte dos valores da tag {@code arranjo}. Por ser um domínio fechado,
 * garante cardinalidade controlada: a tag nunca terá mais valores que os listados aqui
 * (mais {@link #TAG_DESCONHECIDO}).
 */
public enum Arranjo {
    PIX_DINAMICO(true),
    PIX_ESTATICO(true),
    PIX_AUTOMATICO(true),
    TED(false),
    TEF(false),
    BOLETO(false);

    /**
     * Valor publicado na tag quando chega um arranjo que não existe neste enum.
     * A tag nunca é omitida: o Prometheus exige o mesmo conjunto de tags em todas as séries da métrica.
     */
    public static final String TAG_DESCONHECIDO = "desconhecido";

    // Indica se o arranjo passa pela etapa de antifraude (só os PIX, nesta simulação).
    private final boolean passaPorAntifraude;

    Arranjo(boolean passaPorAntifraude) {
        this.passaPorAntifraude = passaPorAntifraude;
    }

    public boolean passaPorAntifraude() {
        return passaPorAntifraude;
    }

    /** Valor da tag: nome em minúsculas (ex.: {@code pix_dinamico}). */
    public String tag() {
        return name().toLowerCase();
    }

    /**
     * Converte o texto recebido no evento. Não lança exceção: valor inválido vira
     * {@code Optional.empty()}, e quem chama decide como tratar (aqui, "não mapeado").
     */
    public static Optional<Arranjo> parse(String valor) {
        if (valor == null) return Optional.empty();
        return Arrays.stream(values()).filter(a -> a.name().equalsIgnoreCase(valor.trim())).findFirst();
    }
}
