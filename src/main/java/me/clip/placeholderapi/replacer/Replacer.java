package me.clip.placeholderapi.replacer;

import me.clip.placeholderapi.PlaceholderHook;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface Replacer {
    @NotNull
    String apply(
            @NotNull String text,
            @Nullable OfflinePlayer player,
            @NotNull Function<String, @Nullable PlaceholderHook> lookup
    );

    enum Closure {
        BRACKET('{', '}'),
        PERCENT('%', '%');

        public final char head;
        public final char tail;

        Closure(char head, char tail) {
            this.head = head;
            this.tail = tail;
        }
    }
}
