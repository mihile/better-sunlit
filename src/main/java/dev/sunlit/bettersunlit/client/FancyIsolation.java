package dev.sunlit.bettersunlit.client;

import java.lang.reflect.Proxy;

/** Optional public FancyMenu API; no configuration files or global customization switch are modified. */
public final class FancyIsolation {
    private FancyIsolation() {}
    private static boolean registered;
    public static boolean isUpdaterScreen(String name){
        return "dev.sunlit.bettersunlit.client.UpdateScreen".equals(name) ||
               "dev.sunlit.bettersunlit.client.UpdateScreen".equals(name);
    }
    public static Object rule(Class<?> ruleType){
        return Proxy.newProxyInstance(ruleType.getClassLoader(),new Class<?>[]{ruleType},(proxy,method,args)->{
            if(method.getName().equals("isScreenBlacklisted") && args!=null && args.length==1)
                return isUpdaterScreen((String)args[0]);
            return switch(method.getName()){
                case "toString" -> "Better Sunlit updater screens only";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args!=null && args.length==1 && proxy==args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
    public static synchronized void register()throws ReflectiveOperationException{
        if(registered)return;
        Class<?> api=Class.forName("de.keksuccino.fancymenu.customization.ScreenCustomization");
        Class<?> type=Class.forName("de.keksuccino.fancymenu.customization.ScreenCustomization$ScreenBlacklistRule");
        api.getMethod("addScreenBlacklistRule",type).invoke(null,rule(type));
        registered=true;
    }
}
