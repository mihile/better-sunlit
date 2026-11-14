package dev.sunlit.bettersunlit.client;

/** Mixin bridge exposing WeatherParticle's package-private fields to CustomParticle's mixin. */
public interface WeatherParticleBridge {
    boolean sunlit$doCollisionAnim();
    void sunlit$setDoCollisionAnim(boolean value);
    void sunlit$setOQuadSize(float value);
}
