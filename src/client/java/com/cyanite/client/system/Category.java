package com.cyanite.client.system;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    PLAYER("Player"),
    WORLD("World"),
    MISC("Misc"),
    DONUT("Donut SMP");

    public final String title;

    Category(String title) {
        this.title = title;
    }
}
