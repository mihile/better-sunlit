package dev.sunlit.bettersunlit;
import dev.sunlit.bettersunlit.update.*;import dev.sunlit.fishvisuals.SunlitFishVisuals;
import java.nio.file.*;import java.util.*;
import net.minecraftforge.fml.common.Mod;import net.minecraftforge.eventbus.api.SubscribeEvent;import net.minecraftforge.event.server.*;import net.minecraftforge.fml.loading.FMLPaths;
@Mod.EventBusSubscriber(modid=SunlitFishVisuals.ID)
public final class ServerHealth {
 static Path root()throws Exception{return UpdateFiles.directory(FMLPaths.GAMEDIR.get().resolve(".better-sunlit-update"));}
 @SubscribeEvent public static void starting(ServerAboutToStartEvent e){if(!"server".equals(Build.CHANNEL))return;try{var self=ProcessHandle.current();Properties p=new Properties();p.setProperty("pid",Long.toString(self.pid()));p.setProperty("started",self.info().startInstant().orElseThrow().toString());UpdateFiles.writeProperties(root().resolve("server-running.properties"),p);}catch(Exception x){SunlitFishVisuals.LOG.warn("Cannot write server updater identity",x);}}
 @SubscribeEvent public static void ready(ServerStartedEvent e){if(!"server".equals(Build.CHANNEL))return;try{Properties p=new Properties();p.setProperty("build",Long.toString(Build.NUMBER));p.setProperty("status","ready");UpdateFiles.writeProperties(root().resolve("server-health.properties"),p);}catch(Exception x){SunlitFishVisuals.LOG.warn("Cannot write server updater health",x);}}
 @SubscribeEvent public static void stopped(ServerStoppedEvent e){if(!"server".equals(Build.CHANNEL))return;try{Files.deleteIfExists(root().resolve("server-running.properties"));}catch(Exception x){SunlitFishVisuals.LOG.warn("Cannot clear server updater identity",x);}}
}
