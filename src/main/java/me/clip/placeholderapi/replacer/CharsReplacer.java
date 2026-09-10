package me.clip.placeholderapi.replacer;

import me.clip.placeholderapi.PlaceholderHook;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Parses placeholder strings once and reuses their immutable token representation. */
public final class CharsReplacer implements Replacer {
    private static final int MAX_TEMPLATE_CACHE_SIZE = 4096;
    private final Closure closure;
    private final Map<String, Template> templates = new ConcurrentHashMap<>();

    public CharsReplacer(@NotNull Closure closure) {
        this.closure = closure;
    }

    @Override
    public @NotNull String apply(@NotNull String text, @Nullable OfflinePlayer player,
                                 @NotNull Function<String, @Nullable PlaceholderHook> lookup) {
        if (text.indexOf(closure.head) < 0 && text.indexOf('\\') < 0) {
            return text;
        }
        if (templates.size() >= MAX_TEMPLATE_CACHE_SIZE && !templates.containsKey(text)) {
            templates.clear();
        }
        Template template = templates.computeIfAbsent(text, this::parse);
        if (!template.hasTokens()) {
            return template.literalValue();
        }
        StringBuilder output = new StringBuilder(text.length() + (text.length() >> 3));
        Map<Request, String> resolved = new HashMap<>();
        for (Part part : template.parts()) {
            if (part instanceof Literal literal) {
                output.append(literal.value());
                continue;
            }
            Token token = (Token) part;
            Request request = new Request(token.identifier(), token.parameters());
            String replacement;
            if (resolved.containsKey(request)) {
                replacement = resolved.get(request);
            } else {
                PlaceholderHook hook = lookup.apply(token.identifier());
                replacement = hook == null ? null : hook.onRequest(player, token.parameters());
                resolved.put(request, replacement);
            }
            output.append(replacement == null ? token.original() : replacement);
        }
        return output.toString();
    }

    private Template parse(String text) {
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\' && index + 1 < text.length()
                    && (text.charAt(index + 1) == closure.head || text.charAt(index + 1) == closure.tail)) {
                literal.append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            if (current != closure.head) {
                literal.append(current);
                index++;
                continue;
            }
            int end = findUnescapedTail(text, index + 1);
            if (end < 0) {
                literal.append(text, index, text.length());
                break;
            }
            String body = text.substring(index + 1, end);
            int separator = body.indexOf('_');
            if (separator <= 0 || body.indexOf(' ') >= 0) {
                literal.append(text, index, end + 1);
                index = end + 1;
                continue;
            }
            flushLiteral(parts, literal);
            parts.add(new Token(body.substring(0, separator).toLowerCase(Locale.ROOT),
                    body.substring(separator + 1), text.substring(index, end + 1)));
            index = end + 1;
        }
        flushLiteral(parts, literal);
        return new Template(List.copyOf(parts));
    }

    private int findUnescapedTail(String text, int from) {
        for (int index = from; index < text.length(); index++) {
            if (text.charAt(index) == closure.tail && (index == 0 || text.charAt(index - 1) != '\\')) {
                return index;
            }
        }
        return -1;
    }

    private void flushLiteral(List<Part> parts, StringBuilder literal) {
        if (!literal.isEmpty()) {
            parts.add(new Literal(literal.toString()));
            literal.setLength(0);
        }
    }

    private sealed interface Part permits Literal, Token { }
    private record Literal(String value) implements Part { }
    private record Token(String identifier, String parameters, String original) implements Part { }
    private record Request(String identifier, String parameters) { }

    private record Template(List<Part> parts) {
        private boolean hasTokens() {
            return parts.stream().anyMatch(Token.class::isInstance);
        }

        private String literalValue() {
            StringBuilder value = new StringBuilder();
            for (Part part : parts) {
                value.append(((Literal) part).value());
            }
            return value.toString();
        }
    }
}
