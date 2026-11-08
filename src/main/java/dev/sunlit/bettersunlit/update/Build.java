package dev.sunlit.bettersunlit.update;
import java.util.*;
public final class Build {
 public static final Properties META=load();
 public static final String CHANNEL=META.getProperty("channel");
 public static final long NUMBER=Long.parseLong(META.getProperty("build"));
 public static final String LABEL=META.getProperty("label");
 private static Properties load(){try(var in=Build.class.getResourceAsStream("/assets/better_sunlit/update-build.properties")){if(in==null)throw new IllegalStateException("Missing build identity");return SignedRelease.properties(in.readAllBytes());}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
