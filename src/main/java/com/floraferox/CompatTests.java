package com.floraferox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/** The optional Boss Checklist data (tools/compat/boss_checklist.py): it parses, and every id in it is ours and real. */
final class CompatTests {
    private CompatTests() { }

    private static JsonElement json(GameTestHelper h, String path) {
        InputStream in = CompatTests.class.getResourceAsStream(path);
        h.assertTrue(in != null, path + " is in the jar");
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void bossChecklistEntriesResolve(GameTestHelper h) {
        JsonObject lang = json(h, "/assets/floraferox/lang/en_us.json").getAsJsonObject();
        h.assertTrue(lang.has("boss_checklist.mod.floraferox"), "the checklist names the mod");
        int n = 0;
        for (JsonElement e : json(h, "/assets/boss_checklist/bosses.json").getAsJsonArray()) {
            JsonObject b = e.getAsJsonObject();
            Identifier id = Identifier.parse(b.get("id").getAsString());
            h.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(id), id + " is a registered mob");
            String key = id.getNamespace() + "_" + id.getPath();
            for (String part : new String[] { "boss", "summon", "info" })
                h.assertTrue(lang.has("boss_checklist." + part + "." + key), id + " has its " + part + " text");
            for (JsonElement d : b.getAsJsonArray("drops")) {
                String item = d.getAsString().split("#")[0];
                h.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse(item)), id + " drop " + item + " is a real item");
            }
            n++;
        }
        h.assertTrue(n == 4, "four bosses listed, not " + n);
        int ids = json(h, "/data/boss_checklist/server_bosses_ids.json").getAsJsonArray().size();
        h.assertTrue(ids == 4, "four server ids, not " + ids);
        // With Boss Checklist in the run (-PextraModsDir=...), its server side must have read our four ids.
        if (net.neoforged.fml.ModList.get().isLoaded("boss_checklist")) {
            try {
                java.lang.reflect.Method isBoss = Class.forName("com.yori3o.boss_checklist.common.server.data.ServerBossIdsLoader")
                        .getMethod("isBoss", String.class);
                for (String b : new String[] { "thorn_matriarch", "briar_king", "carrion_bloom", "crimson_behemoth" })
                    h.assertTrue((Boolean) isBoss.invoke(null, "floraferox:" + b), "Boss Checklist counts floraferox:" + b + " as a boss");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Boss Checklist's id loader changed shape", e);
            }
            com.mojang.logging.LogUtils.getLogger().info("Boss Checklist present: it counts all four Flora Ferox bosses");
        }
        h.succeed();
    }
}
