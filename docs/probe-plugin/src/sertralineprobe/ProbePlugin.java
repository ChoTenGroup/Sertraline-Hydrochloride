package sertralineprobe;

import io.github.zzzyyylllty.sertraline.api.SertralineItemAPI;
import io.github.zzzyyylllty.sertraline.data.AbstractSItem;
import io.github.zzzyyylllty.sertraline.data.MinecraftSItem;
import io.github.zzzyyylllty.sertraline.data.SItemFactory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;

/**
 * Throwaway probe plugin: exercises SertralineItemAPI the way a third-party plugin would.
 * Every assertion is printed to the server log with a PASS/FAIL marker.
 */
public class ProbePlugin extends JavaPlugin {

    private int failures = 0;

    private void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        getLogger().info((ok ? "[PASS] " : "[FAIL] ") + label + " :: " + detail);
    }

    @Override
    public void onEnable() {
        try {
            run();
        } catch (Throwable t) {
            getLogger().severe("[FAIL] probe threw: " + t);
            t.printStackTrace();
            failures++;
        }
        getLogger().info("=== probe finished, failures=" + failures + " ===");
    }

    private void run() {
        var api = SertralineItemAPI.INSTANCE;
        getLogger().info("=== SertralineItemAPI probe: registry size=" + api.getItemCount() + " ===");

        // 1. bare id must resolve to a Sertraline item
        AbstractSItem bare = api.parse("doe_card");
        check("parse(bare) -> ModernSItem",
                bare != null && "ModernSItem".equals(bare.getClass().getSimpleName()),
                String.valueOf(bare == null ? "null" : bare.getClass().getSimpleName() + " storageKey=" + bare.getStorageKey()));

        // 2. explicit minecraft namespace
        AbstractSItem mc = api.parse("minecraft::stone");
        check("parse(minecraft::stone) -> MinecraftSItem",
                mc != null && "MinecraftSItem".equals(mc.getClass().getSimpleName()),
                String.valueOf(mc == null ? "null" : mc.getClass().getSimpleName() + " storageKey=" + mc.getStorageKey()
                        + " shouldAdapt=" + mc.getShouldAdapt() + " material=" + mc.getMaterial()));

        // 3. external provider
        AbstractSItem ext = api.parse("someplugin::gun");
        check("parse(someplugin::gun) -> ExternalPluginSItem",
                ext != null && "ExternalPluginSItem".equals(ext.getClass().getSimpleName()),
                String.valueOf(ext == null ? "null" : ext.getClass().getSimpleName() + " storageKey=" + ext.getStorageKey()
                        + " shouldAdapt=" + ext.getShouldAdapt()));

        // 4. register a non-Sertraline item under its storageKey
        MinecraftSItem probe = new MinecraftSItem("probe_diamond", "DIAMOND", "minecraft",
                new LinkedHashMap<>(), new LinkedHashMap<>());
        api.register(probe, probe.getStorageKey());
        check("register(MinecraftSItem) keyed by storageKey",
                api.isRegistered("minecraft::probe_diamond") && api.getItem("minecraft::probe_diamond") != null,
                "registry=" + api.getItemCount() + " minecraftItems=" + api.getMinecraftItems().keySet());

        // 5. namespaced lookup must NOT fall back to a bare key of the same name
        check("getItem(nonexistent::x) == null (no bare fallback)",
                api.getItem("nonexistent::x") == null,
                String.valueOf(api.getItem("nonexistent::x")));

        // 6. build
        ItemStack built = probe.buildItem(null);
        check("MinecraftSItem.buildItem(null)", built != null && built.getType() == Material.DIAMOND,
                String.valueOf(built == null ? "null" : built.getType()));

        // 7. serialization round-trip must preserve the concrete type and re-derive runtime state
        String json = probe.serialize();
        AbstractSItem restored = SItemFactory.INSTANCE.deserialize(json);
        check("serialize -> deserialize keeps MinecraftSItem",
                restored != null && "MinecraftSItem".equals(restored.getClass().getSimpleName())
                        && "minecraft::probe_diamond".equals(restored.getStorageKey()),
                json + " -> " + (restored == null ? "null" : restored.getClass().getSimpleName()
                        + " shouldAdapt=" + restored.getShouldAdapt()));

        // 8. a Sertraline item round-trips back to ModernSItem
        if (bare != null) {
            String sJson = bare.serialize();
            AbstractSItem sRestored = SItemFactory.INSTANCE.deserialize(sJson);
            check("serialize -> deserialize keeps ModernSItem",
                    sRestored != null && "ModernSItem".equals(sRestored.getClass().getSimpleName())
                            && "doe_card".equals(sRestored.getStorageKey()),
                    sJson.length() + " bytes -> " + (sRestored == null ? "null" : sRestored.getClass().getSimpleName()
                            + " shouldAdapt=" + sRestored.getShouldAdapt()));
        }

        // 9. garbage input must not throw
        AbstractSItem bad = SItemFactory.INSTANCE.deserialize("{not valid json}");
        check("deserialize(garbage) returns null", bad == null, String.valueOf(bad));

        // 10. empty namespace must be rejected
        check("parse(::stone) == null", api.parse("::stone") == null, String.valueOf(api.parse("::stone")));

        // 11. Sertraline item can actually be built (full adapter pipeline)
        if (bare != null) {
            ItemStack sItem = bare.buildItem(null);
            check("ModernSItem.buildItem(null) is not AIR",
                    sItem != null && sItem.getType() != Material.AIR,
                    String.valueOf(sItem == null ? "null" : sItem.getType()));
        }
    }
}
