package com.cyanite.client.system;

/**
 * Whether Anti Mod Check is guarding right now (the sign editor or anvil turning server text into
 * what it sends back). Its own class, with nothing of the game in it, because the language hook
 * asks on every text lookup, from game start: asking must never load the modules early.
 */
public final class ModCheckGuard {
    private static int depth;
    private static Thread thread;

    private ModCheckGuard() {
    }

    public static boolean guarding() {
        return depth > 0 && Thread.currentThread() == thread;
    }

    /** Starts guarding on this thread; true for the outermost start. */
    public static boolean begin() {
        if (depth > 0 && Thread.currentThread() != thread) return false;
        thread = Thread.currentThread();
        return depth++ == 0;
    }

    /** Stops; true when that was the outermost. */
    public static boolean end() {
        if (depth == 0 || Thread.currentThread() != thread) return false;
        if (--depth > 0) return false;
        thread = null;
        return true;
    }

    /** A guard left open (something threw mid-way) never outlives the tick. */
    public static void reset() {
        depth = 0;
        thread = null;
    }
}
