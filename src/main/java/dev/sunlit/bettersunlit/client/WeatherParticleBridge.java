package dev.sunlit.bettersunlit.client;

/** Mixin bridge exposing WeatherParticle's package-private collision animation flag to CustomParticle's mixin. */
public interface WeatherParticleBridge {
    boolean sunlit$doCollisionAnim();
}
