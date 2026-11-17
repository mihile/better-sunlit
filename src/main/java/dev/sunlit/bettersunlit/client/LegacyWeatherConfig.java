package dev.sunlit.bettersunlit.client;
public final class LegacyWeatherConfig {
  static final java.lang.reflect.Field CONFIG=field("pigcart.particlerain.config.ConfigManager","config");
  static final java.lang.reflect.Field COMPAT=field("pigcart.particlerain.config.ConfigData","compat");
  static final java.lang.reflect.Field WEATHER=field("pigcart.particlerain.config.ConfigData$CompatibilityOptions","renderDefaultWeather");
  static java.lang.reflect.Field field(String c,String n){try{return Class.forName(c).getField(n);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
  public static boolean skip(){try{return !WEATHER.getBoolean(COMPAT.get(CONFIG.get(null)));}catch(ReflectiveOperationException e){return false;}}
 }
