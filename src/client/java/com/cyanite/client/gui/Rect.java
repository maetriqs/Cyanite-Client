package com.cyanite.client.gui;

/** A rectangle in GUI units. */
record Rect(int x, int y, int w, int h) {
    static final Rect EMPTY = new Rect(0, 0, 0, 0);

    boolean isEmpty() {
        return w <= 0 || h <= 0;
    }

    Rect union(Rect other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;
        int left = Math.min(x, other.x);
        int top = Math.min(y, other.y);
        return new Rect(left, top, Math.max(x + w, other.x + other.w) - left, Math.max(y + h, other.y + other.h) - top);
    }

    Rect grow(int amount) {
        return new Rect(x - amount, y - amount, w + amount * 2, h + amount * 2);
    }
}
