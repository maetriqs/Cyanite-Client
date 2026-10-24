package com.cyanite.client.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signing in to Minecraft with a Microsoft account, the way consoles and many launchers do: you
 * get a short code, type it in at microsoft.com/link in your own browser and sign in there. Your
 * password never passes through the game. The Microsoft token is then traded for Xbox Live, then
 * XSTS, then Minecraft tokens, and finally the Minecraft profile (name and UUID).
 */
public final class MicrosoftAuth {
    /** The public Microsoft app ID that allows the device-code flow for Minecraft. */
    static final String CLIENT_ID = "00000000441cc96b";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private MicrosoftAuth() {
    }

    public record DeviceCode(String userCode, String deviceCode, String verificationUri, int interval, long expiresAt) {
        /** The sign-in page with the code already filled in. */
        public String link() {
            return verificationUri + "?otc=" + userCode;
        }
    }

    public record MsTokens(String accessToken, String refreshToken) {
    }

    public record Session(String name, UUID uuid, String accessToken) {
    }

    /** Something went wrong in a way worth telling the player about, in words they'll understand. */
    public static final class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    public static DeviceCode requestDeviceCode() throws AuthException {
        JsonObject json = form("https://login.live.com/oauth20_connect.srf",
            Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"));
        if (!json.has("user_code")) throw new AuthException("Microsoft didn't give a sign-in code");
        int interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
        long expiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000;
        return new DeviceCode(json.get("user_code").getAsString(), json.get("device_code").getAsString(),
            json.get("verification_uri").getAsString(), interval, expiresAt);
    }

    /** @return the tokens once you've signed in, or null while Microsoft is still waiting for you. */
    public static MsTokens pollDeviceCode(DeviceCode code) throws AuthException {
        JsonObject json = form("https://login.live.com/oauth20_token.srf", Map.of(
            "client_id", CLIENT_ID,
            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
            "device_code", code.deviceCode()));
        if (json.has("access_token")) return tokens(json);

        String error = json.has("error") ? json.get("error").getAsString() : "";
        return switch (error) {
            case "authorization_pending", "slow_down" -> null;
            case "expired_token" -> throw new AuthException("The code expired, try again");
            case "authorization_declined", "access_denied" -> throw new AuthException("Sign-in was cancelled");
            default -> throw new AuthException("Microsoft sign-in failed" + (error.isEmpty() ? "" : " (" + error + ")"));
        };
    }

    public static MsTokens refresh(String refreshToken) throws AuthException {
        JsonObject json = form("https://login.live.com/oauth20_token.srf", Map.of(
            "client_id", CLIENT_ID,
            "grant_type", "refresh_token",
            "refresh_token", refreshToken,
            "scope", SCOPE));
        if (!json.has("access_token")) throw new AuthException("This account needs signing in again");
        return tokens(json);
    }

    /** Microsoft token to a Minecraft session: Xbox Live, then XSTS, then Minecraft, then the profile. */
    public static Session login(String msAccessToken) throws AuthException {
        JsonObject xblBody = new JsonObject();
        JsonObject xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RPS");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProps.addProperty("RpsTicket", msAccessToken);
        xblBody.add("Properties", xblProps);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        Response xbl = json("https://user.auth.xboxlive.com/user/authenticate", xblBody, null);
        if (xbl.status != 200) throw new AuthException("Xbox Live sign-in failed (" + xbl.status + ")");
        String xblToken = xbl.body.get("Token").getAsString();

        JsonObject xstsBody = new JsonObject();
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        xstsProps.add("UserTokens", userTokens);
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        Response xsts = json("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody, null);
        if (xsts.status == 401) throw new AuthException(xstsError(xsts.body));
        if (xsts.status != 200) throw new AuthException("Xbox sign-in failed (" + xsts.status + ")");
        String xstsToken = xsts.body.get("Token").getAsString();
        String uhs = xsts.body.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        Response mc = json("https://api.minecraftservices.com/authentication/login_with_xbox", mcBody, null);
        if (mc.status == 429) throw new AuthException("Too many sign-ins, wait a minute and try again");
        if (mc.status != 200) throw new AuthException("Minecraft sign-in failed (" + mc.status + ")");
        String mcToken = mc.body.get("access_token").getAsString();

        Response profile = get("https://api.minecraftservices.com/minecraft/profile", mcToken);
        if (profile.status == 404) throw new AuthException("This account doesn't own Minecraft: Java Edition");
        if (profile.status != 200) throw new AuthException("Couldn't load the Minecraft profile (" + profile.status + ")");
        String id = profile.body.get("id").getAsString();
        UUID uuid = UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        return new Session(profile.body.get("name").getAsString(), uuid, mcToken);
    }

    private static String xstsError(JsonObject body) {
        long code = body != null && body.has("XErr") ? body.get("XErr").getAsLong() : 0;
        if (code == 2148916233L) return "This Microsoft account has no Xbox profile yet. Sign in at xbox.com once, then try again";
        if (code == 2148916235L) return "Xbox Live isn't available in this account's country";
        if (code == 2148916236L || code == 2148916237L) return "This account needs adult verification on xbox.com first";
        if (code == 2148916238L) return "This is a child account; an adult needs to add it to a Microsoft family first";
        return "Xbox sign-in was refused" + (code != 0 ? " (" + code + ")" : "");
    }

    private static MsTokens tokens(JsonObject json) {
        return new MsTokens(json.get("access_token").getAsString(), json.get("refresh_token").getAsString());
    }

    private record Response(int status, JsonObject body) {
    }

    private static JsonObject form(String url, Map<String, String> fields) throws AuthException {
        String body = fields.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        Response response = send(request);
        return response.body == null ? new JsonObject() : response.body;
    }

    private static Response json(String url, JsonObject body, String bearer) throws AuthException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-xbl-contract-version", "1")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return send(request.build());
    }

    private static Response get(String url, String bearer) throws AuthException {
        return send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build());
    }

    private static Response send(HttpRequest request) throws AuthException {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject body = null;
            String text = response.body();
            if (text != null && !text.isBlank()) {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) body = parsed.getAsJsonObject();
            }
            return new Response(response.statusCode(), body);
        } catch (IOException e) {
            throw new AuthException("Couldn't reach " + request.uri().getHost() + ", check your connection");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Cancelled");
        } catch (RuntimeException e) {
            throw new AuthException("Got an odd answer from " + request.uri().getHost());
        }
    }
}
