package dev.sunlit.bettersunlit.client;
import pigcart.particlerain.config.ParticleData;
public final class LegacyParticleSettings {
 private static final java.lang.reflect.Field SCREEN_SIZE=resolve();
 private LegacyParticleSettings(){}
 private static java.lang.reflect.Field resolve(){try{return ParticleData.class.getField("constantScreenSize");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
 public static boolean constantScreenSize(ParticleData data){try{return ((Boolean)SCREEN_SIZE.get(data)).booleanValue();}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
}
