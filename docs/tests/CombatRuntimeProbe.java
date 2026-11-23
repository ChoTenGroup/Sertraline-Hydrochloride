// Host interoperability smoke test; run via default_combat_test.py --runtime.
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.bukkit.NamespacedKey;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.persistence.PersistentDataType;

public class CombatRuntimeProbe {
    public static class Event {
        public Object getEntity() { return null; }
        public boolean isCancelled() { return false; }
        public double getDamage() { return 10; }
        public double getDamage(DamageModifier modifier) { return -10; }
    }

    public static class Capture {
        public int writes;
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            if (!type.getComplexType().isInstance(value)) {
                throw new AssertionError("PDC type mismatch: " + value.getClass());
            }
            writes++;
        }
    }

    public static void main(String[] args) throws Exception {
        try (Context engine = Context.newBuilder("js")
                .allowExperimentalOptions(true).option("js.nashorn-compat", "true")
                .allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true)
                    .allowAllImplementations(true).allowAllClassImplementations(true)
                    .allowArrayAccess(true).allowListAccess(true).allowBufferAccess(false)
                    .allowIterableAccess(false).allowIteratorAccess(true).allowMapAccess(true)
                    .allowAccessInheritance(false).build())
                .allowHostClassLookup(name -> true).allowHostClassLoading(false).build()) {
        engine.getBindings("js").putMember("event", new Event());
        for (String file : args) engine.eval("js", Files.readString(Path.of(file)));
        Capture capture = new Capture();
        engine.getBindings("js").putMember("capture", capture);
        engine.eval("js", "var Key = Java.type('org.bukkit.NamespacedKey');"
            + "var Type = Java.type('org.bukkit.persistence.PersistentDataType');"
            + "[0, 8, 0.25].forEach(function(n) {"
            + "var value = Number(n); capture.set(new Key('sertraline', 'combat/test'), Type.DOUBLE, value); });"
            + "var Mod = Java.type('org.bukkit.event.entity.EntityDamageEvent.DamageModifier');"
            + "if (Number(event.getDamage()) !== 10 || Number(event.getDamage(Mod.BLOCKING)) !== -10) throw 'overload';");
        if (capture.writes != 3) throw new AssertionError("Missing PDC writes");
        System.out.println("PASS GraalJS: actual Bukkit class lookup, both scripts, overloaded damage and DOUBLE PDC values");
        }
    }
}
