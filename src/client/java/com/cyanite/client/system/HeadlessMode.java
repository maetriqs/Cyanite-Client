package com.cyanite.client.system;

/**
 * Whether Cyanite Miner started this game: no window, nothing drawn, driven from the app
 * ({@link Headless}). It only reads a system property, so the window code can ask before the game
 * is up (see the early class-load rule: nothing here touches Module or Minecraft).
 */
public final class HeadlessMode {
    private static final String PORT = System.getProperty("cyanite.headless");

    private HeadlessMode() {
    }

    public static boolean on() {
        return PORT != null;
    }

    /** The local port the app talks to the game on. */
    static int port() {
        try {
            return Integer.parseInt(PORT.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
