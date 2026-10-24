package com.cyanite.client.account;

import com.cyanite.client.mixin.MinecraftSessionAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.logging.LogUtils;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * The saved accounts, and switching the game between them. Each Microsoft account is kept as its
 * refresh token, which on Windows is encrypted to your Windows user with DPAPI before it touches
 * the disk, so the file is useless if copied to another PC or account. Cracked accounts are just a
 * name: no sign-in, and they only get into servers running in offline mode.
 */
public final class Accounts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DPAPI_MAGIC = "CYANITE-DPAPI1";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public record Account(UUID uuid, String name, String refreshToken, boolean cracked) {
        public Account(UUID uuid, String name, String refreshToken) {
            this(uuid, name, refreshToken, false);
        }
    }

    private static final List<Account> accounts = new ArrayList<>();
    private static User launcher;
    private static boolean loaded;

    private Accounts() {
    }

    /** Remembers the account the launcher started the game with, so you can always switch back. */
    public static void init() {
        launcher = Minecraft.getInstance().getUser();
        load();
    }

    public static User launcher() {
        return launcher;
    }

    public static List<Account> list() {
        if (!loaded) load();
        return List.copyOf(accounts);
    }

    public static UUID current() {
        return Minecraft.getInstance().getUser().getProfileId();
    }

    public static void remove(UUID uuid) {
        accounts.removeIf(a -> a.uuid.equals(uuid));
        save();
    }

    /** Signs in with fresh Microsoft tokens, saves the account and switches to it. */
    public static CompletableFuture<Account> addAndUse(MicrosoftAuth.MsTokens tokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MicrosoftAuth.Session session = MicrosoftAuth.login(tokens.accessToken());
                return new Signed(session, tokens.refreshToken());
            } catch (MicrosoftAuth.AuthException e) {
                throw new RuntimeException(e.getMessage());
            }
        }, Util.nonCriticalIoPool()).thenApplyAsync(signed -> {
            Account account = new Account(signed.session.uuid(), signed.session.name(), signed.refresh);
            accounts.removeIf(a -> a.uuid.equals(account.uuid));
            accounts.add(account);
            save();
            apply(signed.session);
            return account;
        }, Minecraft.getInstance());
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    /** The UUID an offline-mode server gives this name, so you keep the same inventory there each time. */
    public static UUID crackedUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Saves a cracked account under {@code name} and switches to it. Main thread. */
    public static Account addCracked(String name) {
        Account account = new Account(crackedUuid(name), name, null, true);
        accounts.removeIf(a -> a.uuid.equals(account.uuid));
        accounts.add(account);
        save();
        applyCracked(account);
        return account;
    }

    /** Refreshes a saved account's sign-in and switches to it. Cracked accounts switch at once. */
    public static CompletableFuture<Account> use(Account account) {
        if (account.cracked) {
            applyCracked(account);
            return CompletableFuture.completedFuture(account);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                MicrosoftAuth.MsTokens tokens = MicrosoftAuth.refresh(account.refreshToken);
                return new Signed(MicrosoftAuth.login(tokens.accessToken()), tokens.refreshToken());
            } catch (MicrosoftAuth.AuthException e) {
                throw new RuntimeException(e.getMessage());
            }
        }, Util.nonCriticalIoPool()).thenApplyAsync(signed -> {
            // The refresh token rotates on every use, and the name may have changed since.
            Account updated = new Account(signed.session.uuid(), signed.session.name(), signed.refresh);
            accounts.replaceAll(a -> a.uuid.equals(account.uuid) ? updated : a);
            save();
            apply(signed.session);
            return updated;
        }, Minecraft.getInstance());
    }

    public static void useLauncher() {
        apply(launcher);
    }

    private record Signed(MicrosoftAuth.Session session, String refresh) {
    }

    private static void apply(MicrosoftAuth.Session session) {
        apply(new User(session.name(), session.uuid(), session.accessToken(), Optional.empty(), Optional.empty()));
    }

    /** A cracked account has nothing to sign in to: no Mojang services and no chat signing keys. */
    private static void applyCracked(Account account) {
        MinecraftSessionAccessor session = (MinecraftSessionAccessor) Minecraft.getInstance();
        User user = new User(account.name, account.uuid, "0", Optional.empty(), Optional.empty());
        session.cyanite$setUser(user);
        session.cyanite$setUserApiService(UserApiService.OFFLINE);
        session.cyanite$setProfileFuture(CompletableFuture.completedFuture(new ProfileResult(new GameProfile(account.uuid, account.name))));
        session.cyanite$setUserPropertiesFuture(CompletableFuture.completedFuture(UserApiService.OFFLINE_PROPERTIES));
        session.cyanite$setProfileKeyPairManager(ProfileKeyPairManager.EMPTY_KEY_MANAGER);
        session.cyanite$setReportingContext(ReportingContext.create(ReportEnvironment.local(), UserApiService.OFFLINE));
        LOGGER.info("Switched to cracked account {}", account.name);
    }

    /** Swaps every piece of the game that belongs to the signed-in account. Main thread only. */
    private static void apply(User user) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftSessionAccessor session = (MinecraftSessionAccessor) mc;
        UserApiService api;
        try {
            api = new YggdrasilAuthenticationService(mc.getProxy()).createUserApiService(user.getAccessToken());
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't create the user API service for {}", user.getName(), e);
            api = UserApiService.OFFLINE;
        }
        UserApiService service = api;

        session.cyanite$setUser(user);
        session.cyanite$setUserApiService(service);
        session.cyanite$setProfileFuture(CompletableFuture.supplyAsync(
            () -> mc.services().sessionService().fetchProfile(user.getProfileId(), true), Util.nonCriticalIoPool()));
        session.cyanite$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
            try {
                return service.fetchProperties();
            } catch (AuthenticationException e) {
                return UserApiService.OFFLINE_PROPERTIES;
            }
        }, Util.nonCriticalIoPool()));
        // Chat signing keys belong to the account, so the new one needs its own.
        session.cyanite$setProfileKeyPairManager(ProfileKeyPairManager.create(service, user, mc.gameDirectory.toPath()));
        session.cyanite$setReportingContext(ReportingContext.create(ReportEnvironment.local(), service));
        LOGGER.info("Switched account to {}", user.getName());
    }

    // ---- Storage

    private static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("cyanite").resolve("accounts.dat");
    }

    private static void load() {
        loaded = true;
        accounts.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try {
            String json = decrypt(Files.readAllBytes(file));
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (var element : root.getAsJsonArray("accounts")) {
                JsonObject o = element.getAsJsonObject();
                boolean cracked = o.has("cracked") && o.get("cracked").getAsBoolean();
                accounts.add(new Account(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString(),
                    cracked ? null : o.get("refresh").getAsString(), cracked));
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read saved accounts", e);
        }
    }

    private static void save() {
        JsonArray list = new JsonArray();
        for (Account a : accounts) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", a.uuid.toString());
            o.addProperty("name", a.name);
            if (a.cracked) o.addProperty("cracked", true);
            else o.addProperty("refresh", a.refreshToken);
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("accounts", list);
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), encrypt(root.toString()));
        } catch (Exception e) {
            LOGGER.warn("Couldn't save accounts", e);
        }
    }

    private static byte[] encrypt(String json) {
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        if (!Platform.isWindows()) return plain;
        try {
            byte[] sealed = Crypt32Util.cryptProtectData(plain);
            byte[] magic = (DPAPI_MAGIC + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] out = new byte[magic.length + sealed.length];
            System.arraycopy(magic, 0, out, 0, magic.length);
            System.arraycopy(sealed, 0, out, magic.length, sealed.length);
            return out;
        } catch (Throwable t) {
            LOGGER.warn("Windows encryption unavailable, saving accounts unencrypted", t);
            return plain;
        }
    }

    private static String decrypt(byte[] data) {
        byte[] magic = (DPAPI_MAGIC + "\n").getBytes(StandardCharsets.US_ASCII);
        boolean sealed = data.length > magic.length;
        for (int i = 0; sealed && i < magic.length; i++) sealed = data[i] == magic[i];
        if (!sealed) return new String(data, StandardCharsets.UTF_8);
        byte[] body = new byte[data.length - magic.length];
        System.arraycopy(data, magic.length, body, 0, body.length);
        return new String(Crypt32Util.cryptUnprotectData(body), StandardCharsets.UTF_8);
    }

    /** A readable message for a failure from {@link #use} or {@link #addAndUse}. */
    public static String message(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t.getMessage() == null || t instanceof java.util.concurrent.CompletionException)) t = t.getCause();
        String message = t.getMessage();
        return message == null || message.isBlank() ? "Something went wrong" : message;
    }
}
