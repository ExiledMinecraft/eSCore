package me.clip.placeholderapi.replacer;

import me.clip.placeholderapi.PlaceholderHook;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharsReplacerTest {
    @Test
    void resolvesDuplicateTokensOnlyOncePerPassAndSupportsEscaping() {
        AtomicInteger calls = new AtomicInteger();
        PlaceholderHook hook = new PlaceholderHook() {
            @Override
            public String onRequest(org.bukkit.OfflinePlayer player, String params) {
                calls.incrementAndGet();
                return params.toUpperCase();
            }
        };
        CharsReplacer replacer = new CharsReplacer(Replacer.Closure.PERCENT);
        assertEquals("ABC ABC %demo_abc%", replacer.apply("%demo_abc% %demo_abc% \\%demo_abc%", null,
                identifier -> "demo".equals(identifier) ? hook : null));
        assertEquals(1, calls.get());
    }
}
