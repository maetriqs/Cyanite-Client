package com.cyanite.client.system.setting;

import com.google.gson.JsonElement;

import java.util.Objects;

public abstract class Setting<T> {
    public final String name;
    private final T defaultValue;
    protected T value;
    private Runnable listener;
    private boolean risky;
    private boolean secret;

    protected Setting(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        T old = this.value;
        this.value = sanitize(value);
        if (!Objects.equals(old, this.value) && listener != null) listener.run();
    }

    public void reset() {
        set(defaultValue);
    }

    /** Marks it as something servers catch or that won't work on them: its name shows in red. */
    public void markRisky() {
        risky = true;
    }

    public boolean isRisky() {
        return risky;
    }

    /** Something private, like a webhook URL: hidden in the menu and left out of profiles, so sharing one never shares it. */
    protected void markSecret() {
        secret = true;
    }

    public boolean isSecret() {
        return secret;
    }

    public void onChange(Runnable listener) {
        this.listener = listener;
    }

    protected T sanitize(T value) {
        return value;
    }

    public abstract JsonElement toJson();

    /** Applies a saved value. Anything malformed is ignored and the current value kept. */
    public abstract void fromJson(JsonElement json);
}
