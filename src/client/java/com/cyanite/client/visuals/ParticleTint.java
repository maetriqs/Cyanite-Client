package com.cyanite.client.visuals;

/** Added to every particle (ParticleMixin): the colour and opacity Visuals gave it when it was made. */
public interface ParticleTint {
    /** ARGB, or 0 for its own colour. */
    int cyanite$tint();

    void cyanite$setTint(int argb);

    float cyanite$alpha();

    void cyanite$setAlpha(float alpha);
}
