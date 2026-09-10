package ru.exiledproject.developments.scripting.js;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsSandboxTest {
    @Test
    void exposesOnlyExplicitMethodsAndBlocksJavaClassLookup() {
        try (var engine = JsSandbox.createEngine();
             var context = JsSandbox.context(engine, JsSandbox.expose(method -> method.getName().equals("safe"), Fixture.class)).build()) {
            context.getBindings("js").putMember("fixture", new Fixture());
            assertEquals("ok", context.eval("js", "fixture.safe()").asString());
            assertThrows(PolyglotException.class, () -> context.eval("js", "fixture.secret()"));
            assertThrows(PolyglotException.class, () -> context.eval("js", "Java.type('java.lang.System')"));
        }
    }

    public static final class Fixture {
        public String safe() {
            return "ok";
        }

        public String secret() {
            return "not exposed";
        }
    }
}
