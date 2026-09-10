package ru.exiledproject.developments.scripting.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.function.Predicate;

/** GraalJS construction policy kept outside the server/plugin implementation. */
public final class JsSandbox {
    private JsSandbox() {
    }

    public static Engine createEngine() {
        return Engine.newBuilder("js")
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    public static Context.Builder context(Engine engine, HostAccess hostAccess) {
        return Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(hostAccess)
                .allowHostClassLookup(className -> false)
                .allowHostClassLoading(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowIO(IOAccess.NONE);
    }

    public static HostAccess expose(Predicate<Method> allowed, Class<?>... types) {
        HostAccess.Builder builder = HostAccess.newBuilder(HostAccess.EXPLICIT)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .allowMapAccess(true);
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && allowed.test(method)) {
                    builder.allowAccess(method);
                }
            }
        }
        try {
            builder.allowAccess(UUID.class.getMethod("toString"));
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
        return builder.build();
    }
}
