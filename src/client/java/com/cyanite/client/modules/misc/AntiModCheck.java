package com.cyanite.client.modules.misc;

import com.cyanite.client.system.Category;
import com.cyanite.client.system.ModCheckGuard;
import com.cyanite.client.system.Module;
import com.cyanite.client.system.Modules;
import com.cyanite.client.system.Notify;
import com.cyanite.client.system.setting.BoolSetting;
import com.cyanite.client.system.setting.ModListSetting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Anti Mod Check: the sign and anvil trick. A server puts a translation key or a keybind on a sign
 * (or an item's name) and opens the editor, or an anvil; the game turns that into plain text and
 * sends it back. A plain game only knows its own keys, so for a mod's key it sends the key itself;
 * with the mod it sends the mod's text ("Click GUI", "Insert"), and the server knows.
 *
 * <p>While the sign editor or anvil turns server text into what it sends back, this answers the
 * way a plain game would: Minecraft's own keys only, its own English text, and its default key for
 * each of its binds (so your own binds can't tell you apart either). Mods on the Let through list
 * answer as normal, for servers that should see them. Everything else, your screen included, is
 * untouched, and so is every mod's networking. It tells you when a server tries.
 */
public class AntiModCheck extends Module {
    /** An installed mod with text a server could ask about: its id, its name, and how many keys it has. */
    public record ModInfo(String id, String name, int keys) {
    }

    /** Which mod each key belongs to, and the mods; read from every mod's language file, once. */
    private record Index(Map<String, String> owners, List<ModInfo> mods, Set<String> ids) {
    }

    /** This check: hidden keys, the installed mods they were for, and mods let through. */
    private static final Set<String> hiddenKeys = new LinkedHashSet<>();
    private static final Set<String> hiddenMods = new LinkedHashSet<>();
    private static final Set<String> shownMods = new LinkedHashSet<>();
    /** Every installed mod a server has asked about this session. */
    private static final Set<String> everAsked = new HashSet<>();
    private static Map<String, String> vanilla;
    private static CompletableFuture<Index> index;
    private static VanillaOnly wrapper;
    private static long toldAt;

    private final ModListSetting letThrough = add(new ModListSetting("Let through"));
    private final BoolSetting hideBinds = add(new BoolSetting("Hide my binds", true));
    private final BoolSetting tell = add(new BoolSetting("Tell me", true));

    public AntiModCheck() {
        super(Category.MISC, "Anti Mod Check", "Servers can't spot your mods with the sign and anvil trick: they get back what a plain game would");
        setEnabled(true);
        index = CompletableFuture.supplyAsync(AntiModCheck::buildIndex);
    }

    // ---- The guard round the sign editor and the anvil

    /** Server text is about to be turned into what goes back to the server. */
    public static void enter() {
        AntiModCheck self = Modules.get(AntiModCheck.class);
        if (self == null || !self.isEnabled()) return;
        if (ModCheckGuard.begin()) {
            // A new one each time: the game remembers translated text by the language it used, and
            // what was hidden last time may be let through now.
            wrapper = null;
            hiddenKeys.clear();
            hiddenMods.clear();
            shownMods.clear();
        }
    }

    /** Done; says so if the server was checking. */
    public static void exit(String where) {
        if (!ModCheckGuard.end()) return;
        AntiModCheck self = Modules.get(AntiModCheck.class);
        if (hiddenKeys.isEmpty() && shownMods.isEmpty() || self == null || !self.tell.get()) return;
        long now = System.currentTimeMillis();
        if (now - toldAt < 3000) return;
        toldAt = now;
        List<String> parts = new ArrayList<>();
        if (!hiddenMods.isEmpty()) parts.add("hid " + names(hiddenMods));
        int others = (int) hiddenKeys.stream().filter(k -> owner(k) == null).count();
        if (others > 0) parts.add((parts.isEmpty() ? "" : "and ") + others + (others == 1 ? " key" : " keys") + " for mods you don't have");
        if (!shownMods.isEmpty()) parts.add("let through " + names(shownMods));
        Notify.send("Anti Mod Check", "The server checked for mods with " + where + " text: " + String.join(", ", parts) + ".", false, false, false);
    }

    public static void guard(String where, Runnable action) {
        enter();
        try {
            action.run();
        } finally {
            exit(where);
        }
    }

    public static boolean guarding() {
        return ModCheckGuard.guarding();
    }

    // ---- Answering like a plain game, mod by mod

    /** The language as a plain game has it, while guarding. */
    public static Language language(Language real) {
        if (wrapper == null || wrapper.real != real) wrapper = new VanillaOnly(real, english());
        return wrapper;
    }

    /**
     * A keybind, as a plain game names it: its default key for Minecraft's own binds; a let-through
     * mod's bind as it is; any other bind, just its name.
     */
    public static Component keybind(String name) {
        KeyMapping mapping = null;
        for (KeyMapping k : mc.options.keyMappings) {
            if (k.getName().equals(name)) {
                mapping = k;
                break;
            }
        }
        if (vanilla().containsKey(name)) {
            if (mapping != null) {
                AntiModCheck self = Modules.get(AntiModCheck.class);
                return self != null && !self.hideBinds.get() ? mapping.getTranslatedKeyMessage() : mapping.getDefaultKey().getDisplayName();
            }
        } else if (letThrough(name)) {
            if (mapping != null) return mapping.getTranslatedKeyMessage();
        }
        // A plain game has no bind by that name and shows the key's text, or the name itself.
        return Component.translatable(name);
    }

    /** True, and noted, when the key's mod is let through; otherwise noted as hidden. */
    private static boolean letThrough(String key) {
        String mod = owner(key);
        AntiModCheck self = Modules.get(AntiModCheck.class);
        if (mod != null) everAsked.add(mod);
        if (mod != null && self != null && self.letThrough.contains(mod)) {
            shownMods.add(mod);
            return true;
        }
        hiddenKeys.add(key);
        if (mod != null) hiddenMods.add(mod);
        return false;
    }

    // ---- The mods

    /** Installed mods with text a server could ask about, by name. */
    public static List<ModInfo> mods() {
        return index().mods();
    }

    /** Whether a server has asked about this mod since the game started. */
    public static boolean askedAbout(String modId) {
        return everAsked.contains(modId);
    }

    /** The installed mod a key belongs to, or null. */
    private static String owner(String key) {
        Index i = index();
        String mod = i.owners().get(key);
        if (mod != null) return mod;
        // Binds and keys not in a language file usually carry their mod's id: "key.meteor-client.open-gui".
        for (String part : key.split("\\.")) if (i.ids().contains(part)) return part;
        return null;
    }

    private static Index index() {
        if (index == null) index = CompletableFuture.completedFuture(buildIndex());
        return index.join();
    }

    /** Reads every mod's English language file. Fabric API's many parts count as one mod. */
    private static Index buildIndex() {
        Map<String, String> owners = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> ids = new HashSet<>();
        Map<String, String> plain = vanilla();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            ModContainer top = mod;
            while (top.getContainingMod().isPresent()) top = top.getContainingMod().get();
            String id = top.getMetadata().getId();
            if (id.equals("minecraft") || id.equals("java")) continue;
            ids.add(id);
            names.putIfAbsent(id, top.getMetadata().getName());
            for (Path root : mod.getRootPaths()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> spaces = Files.list(assets)) {
                    for (Path space : (Iterable<Path>) spaces::iterator) {
                        Path lang = space.resolve("lang").resolve("en_us.json");
                        if (!Files.isRegularFile(lang)) continue;
                        try (Reader in = Files.newBufferedReader(lang, StandardCharsets.UTF_8)) {
                            if (!(JsonParser.parseReader(in) instanceof JsonObject keys)) continue;
                            for (String key : keys.keySet()) {
                                if (plain.containsKey(key) || owners.putIfAbsent(key, id) != null) continue;
                                counts.merge(id, 1, Integer::sum);
                            }
                        } catch (Exception ignored) {
                            // One broken language file doesn't stop the rest.
                        }
                    }
                } catch (Exception ignored) {
                    // Nor does a mod whose files can't be listed.
                }
            }
        }
        List<ModInfo> mods = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) mods.add(new ModInfo(e.getKey(), names.get(e.getKey()), e.getValue()));
        mods.sort(Comparator.comparing(ModInfo::name, String.CASE_INSENSITIVE_ORDER));
        return new Index(Map.copyOf(owners), List.copyOf(mods), Set.copyOf(ids));
    }

    private static String names(Set<String> modIds) {
        Map<String, String> byId = new HashMap<>();
        for (ModInfo m : mods()) byId.put(m.id(), m.name());
        List<String> out = new ArrayList<>();
        for (String id : modIds) out.add(byId.getOrDefault(id, id));
        return String.join(", ", out);
    }

    // ---- Minecraft's own text

    private static boolean english() {
        return "en_us".equals(mc.getLanguageManager().getSelected());
    }

    /** Minecraft's own English text by key, with keys it renamed still answering as the new ones do. */
    private static synchronized Map<String, String> vanilla() {
        if (vanilla != null) return vanilla;
        Map<String, String> map = new HashMap<>();
        try (InputStream in = Language.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
            if (in != null) {
                for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception ignored) {
            // Nothing to go on: below, that means every key is answered as unknown.
        }
        try (InputStream in = Language.class.getResourceAsStream("/assets/minecraft/lang/deprecated.json")) {
            if (in != null && JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)) instanceof JsonObject root
                && root.get("renamed") instanceof JsonObject renamed) {
                for (Map.Entry<String, JsonElement> e : renamed.entrySet()) {
                    String now = map.get(e.getValue().getAsString());
                    if (now != null) map.putIfAbsent(e.getKey(), now);
                }
            }
        } catch (Exception ignored) {
            // Renamed keys are a nicety.
        }
        vanilla = map;
        return map;
    }

    /** The real language, cut down to Minecraft's own keys and the let-through mods'. */
    private static final class VanillaOnly extends Language {
        final Language real;
        private final boolean english;

        VanillaOnly(Language real, boolean english) {
            this.real = real;
            this.english = english;
        }

        private String text(String key) {
            String own = vanilla().get(key);
            if (own == null) return letThrough(key) && real.has(key) ? real.getOrDefault(key, key) : null;
            // In English, Minecraft's own words, whatever a mod or pack changed them to; otherwise the chosen language's.
            if (english || !real.has(key)) return own;
            return real.getOrDefault(key, own);
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            String text = text(key);
            return text != null ? text : fallback;
        }

        @Override
        public boolean has(String key) {
            return text(key) != null;
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return real.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return real.getVisualOrder(text);
        }
    }
}
