package me.clip.placeholderapi.replacer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Function;

public final class CharsReplacer implements Replacer {
    private final Closure closure;

    public CharsReplacer(@NotNull Closure closure) {
        this.closure = closure;
    }

    @Override
    public @NotNull String apply(
            @NotNull String text,
            @Nullable OfflinePlayer player,
            @NotNull Function<String, @Nullable PlaceholderExpansion> lookup
    ) {
        char head = closure.head;
        int startPlaceholder = text.indexOf(head);
        if (startPlaceholder == -1) {
            return text;
        }

        int length = text.length();
        StringBuilder builder = new StringBuilder(length + (length >> 3));
        int cursor = 0;
        char tail = closure.tail;

        loop:
        do {
            if (startPlaceholder > cursor) {
                builder.append(text, cursor, startPlaceholder);
            }

            int endPlaceholder = text.indexOf(tail, startPlaceholder + 1);
            if (endPlaceholder == -1) {
                builder.append(text, startPlaceholder, length);
                return builder.toString();
            }

            int underscoreIndex = -1;
            for (int i = startPlaceholder + 1; i < endPlaceholder; i++) {
                char current = text.charAt(i);
                if (current == ' ' && underscoreIndex == -1) {
                    builder.append(head);
                    cursor = startPlaceholder + 1;
                    startPlaceholder = text.indexOf(head, cursor);
                    if (startPlaceholder == -1) {
                        break loop;
                    }
                    continue loop;
                }
                if (current == '_' && underscoreIndex == -1) {
                    underscoreIndex = i;
                }
            }

            if (underscoreIndex == -1) {
                builder.append(text, startPlaceholder, endPlaceholder + 1);
                cursor = endPlaceholder + 1;
                startPlaceholder = text.indexOf(head, cursor);
                continue;
            }

            String identifier = text.substring(startPlaceholder + 1, underscoreIndex);
            String parameters = "";
            if (underscoreIndex + 1 < endPlaceholder) {
                parameters = text.substring(underscoreIndex + 1, endPlaceholder);
            }

            PlaceholderExpansion expansion = lookup.apply(identifier.toLowerCase(Locale.ROOT));
            String replacement = null;
            if (expansion != null) {
                replacement = expansion.onRequest(player, parameters);
            }

            if (replacement != null) {
                builder.append(replacement);
            } else {
                builder.append(head).append(identifier).append('_').append(parameters).append(tail);
            }

            cursor = endPlaceholder + 1;
            startPlaceholder = text.indexOf(head, cursor);
        } while (startPlaceholder != -1);

        if (cursor < length) {
            builder.append(text, cursor, length);
        }

        return builder.toString();
    }
}
