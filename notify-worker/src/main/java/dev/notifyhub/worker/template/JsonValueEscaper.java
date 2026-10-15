package dev.notifyhub.worker.template;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.samskivert.mustache.Mustache;

/**
 * Escaper do JMustache que escapa cada valor como conteúdo de uma string JSON (TPL-03, D-16).
 *
 * <p>Usa o {@link JsonStringEncoder} do jackson-core, que já trata aspas, barra invertida, os
 * caracteres de controle U+0000 a U+001F e pares substitutos. Não há escape feito à mão.
 *
 * <p>O escaper não acrescenta aspas: quem fornece as aspas em volta do valor é o próprio template
 * (ex.: {@code "name":"{{name}}"}). Por isso valores que não são string, como {@code 42} ou
 * {@code true}, viram texto e saem entre as aspas do template ({@code "42"}), nunca como número ou
 * booleano JSON.
 *
 * <p>Triple mustache ({@code {{{x}}}}) e {@code {{& x}}} pulam qualquer escaper; os seeds não podem
 * usá-los nas variantes de WEBHOOK e EMAIL ({@code SeedTemplatesTest}).
 */
public final class JsonValueEscaper implements Mustache.Escaper {

    @Override
    public String escape(String raw) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(raw));
    }
}
