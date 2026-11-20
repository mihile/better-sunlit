package dev.sunlit.bettersunlit.client;
import dev.sunlit.bettersunlit.update.*;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;import net.minecraft.client.gui.GuiGraphics;import net.minecraft.client.gui.screens.TitleScreen;import net.minecraft.client.gui.screens.Screen;import net.minecraft.client.gui.components.Button;import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;import net.minecraftforge.fml.common.Mod;import net.minecraftforge.eventbus.api.SubscribeEvent;import net.minecraftforge.event.TickEvent;import net.minecraftforge.client.event.ScreenEvent;import net.minecraftforge.fml.loading.FMLPaths;
@Mod.EventBusSubscriber(modid=SunlitFishVisuals.ID,value=Dist.CLIENT)
public final class UpdateClient {
 static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Better Sunlit updater");t.setDaemon(true);return t;});
 static final AtomicBoolean BUSY=new AtomicBoolean();static volatile SignedRelease release;static volatile String status="업데이트 확인 중…";static volatile boolean queued,failed;static volatile long downloaded;
 static boolean first,auto=true;static long lastCheck;static Screen renderedTitle;
 static Path game(){return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();}
 static Path root()throws Exception{return UpdateFiles.directory(game().resolve(".better-sunlit-update"));}
 @SubscribeEvent public static void init(ScreenEvent.Init.Post event) {
  if (!(event.getScreen() instanceof TitleScreen title)) return;
  event.addListener(new UpdateButton(title));
  renderedTitle = null;
 }

 private static final class UpdateButton extends Button {
  UpdateButton(TitleScreen title) {
   super(Math.max(4, title.f_96543_ - 108), 6, 102, 20, Component.m_237113_("Better Sunlit"),
         button -> open(title), f_252438_);
  }

  @Override
  protected void m_87963_(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
   super.m_87963_(graphics, mouseX, mouseY, partialTick);
   SignedRelease available = release;
   if (available == null || available.build() <= Build.NUMBER || queued) return;

   // A short hop followed by a pause keeps the notification legible at every GUI scale.
   double phase = Math.floorMod(System.nanoTime(), 2_400_000_000L) / 650_000_000.0;
   int lift = phase < 1.0 ? (int) Math.round(3.0 * Math.sin(Math.PI * phase)) : 0;
   int x = m_252754_() + m_93694_() - 7;
   int y = m_252907_() + 1 - lift;
   int outline = 0xFF391622;
   int red = 0xFFFF4959;
   graphics.m_280509_(x - 1, y - 1, x + 4, y + 6, outline);
   graphics.m_280509_(x - 1, y + 6, x + 4, y + 11, outline);
   graphics.m_280509_(x, y, x + 3, y + 5, red);
   graphics.m_280509_(x, y + 7, x + 3, y + 10, red);
  }
 }
 @SubscribeEvent public static void rendered(ScreenEvent.Render.Post event){if(event.getScreen() instanceof TitleScreen)renderedTitle=event.getScreen();}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){if(event.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.m_91087_();if(!(mc.f_91080_ instanceof TitleScreen)||mc.f_91080_!=renderedTitle||mc.m_91265_()!=null)return;
  if(!first){first=true;try{Path p=game().resolve("config/better-sunlit-updater.properties");if(Files.isRegularFile(p))auto=Boolean.parseBoolean(SignedRelease.properties(Files.readAllBytes(p)).getProperty("enabled","true"));FancyIsolation.register();}catch(Exception e){SunlitFishVisuals.LOG.warn("Better Sunlit updater preference/isolation unavailable",e);}if(auto)check();}
 }
 static void open(Screen parent){try{FancyIsolation.register();}catch(Exception ignored){}Minecraft.m_91087_().m_91152_(new UpdateScreen(parent));if(release==null&&!queued)check();}
 static void check(){if(!BUSY.compareAndSet(false,true))return;status="업데이트 확인 중…";failed=false;IO.execute(()->{try{release=SignedRelease.verify(new SafeHttp().manifest(),SignedRelease.publicKey());status=release.build()>Build.NUMBER?"새 업데이트가 있어요":"최신 버전입니다";lastCheck=System.currentTimeMillis();}catch(Exception e){failed=true;status="업데이트를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.";SunlitFishVisuals.LOG.warn("Better Sunlit update check failed; installed mod retained",e);}finally{BUSY.set(false);}});}
 static void prepare(){SignedRelease next=release;if(next==null||next.build()<=Build.NUMBER||!BUSY.compareAndSet(false,true))return;status="다운로드 중…";failed=false;IO.execute(()->{try{
  Path target=UpdateFiles.target(game()),state=root();String oldHash=UpdateFiles.sha(target);if(UpdateFiles.installedBuild(target)!=Build.NUMBER)throw new java.io.IOException("Installed build changed");
  Path plan=state.resolve("pending.properties"),worker=state.resolve("worker.properties");if(Files.isRegularFile(worker)){Properties p=SignedRelease.properties(Files.readAllBytes(worker));var h=ProcessHandle.of(Long.parseLong(p.getProperty("pid","-1")));if(h.isPresent()&&h.get().isAlive()&&h.get().info().startInstant().map(i->i.toString().equals(p.getProperty("started"))).orElse(false)){queued=true;status="게임을 종료하면 적용됩니다";return;}}
  new SafeHttp().download(next,state.resolve("incoming"),n->downloaded=n);String helperHash=Build.META.getProperty("helperSha256");Path helper=UpdateFiles.directory(state.resolve("helpers")).resolve("apply-"+helperHash+".jar");
  if(!Files.exists(helper))try(var in=UpdateClient.class.getResourceAsStream("/assets/better_sunlit/update-helper.jar")){if(in==null)throw new java.io.IOException("Missing helper");Files.copy(in,helper);}
  if(Files.isSymbolicLink(helper)||!UpdateFiles.sha(helper).equals(helperHash))throw new java.io.IOException("Helper checksum mismatch");UpdateFiles.writeBytes(state.resolve("manifest.properties"),next.envelope());
  var self=ProcessHandle.current();Properties p=new Properties();p.setProperty("game",game().toRealPath().toString());p.setProperty("build",Long.toString(next.build()));p.setProperty("oldSha256",oldHash);p.setProperty("parentPid",Long.toString(self.pid()));p.setProperty("parentStarted",self.info().startInstant().orElseThrow().toString());UpdateFiles.writeProperties(plan,p);
  Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"javaw.exe":"java");Path log=state.resolve("apply.log");if(Files.isSymbolicLink(log))throw new java.io.IOException("Linked updater log");
  Process child=new ProcessBuilder(java.toString(),"-Xms8m","-Xmx64m","-Djava.awt.headless=true","-cp",helper.toString(),ApplyUpdate.class.getName(),plan.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();Properties w=new Properties();w.setProperty("pid",Long.toString(child.pid()));w.setProperty("started",child.toHandle().info().startInstant().orElseThrow().toString());UpdateFiles.writeProperties(worker,w);Thread.sleep(300);if(!child.isAlive())throw new java.io.IOException("Helper stopped early");queued=true;status="게임을 종료하면 적용됩니다";
 }catch(Exception e){failed=true;status="업데이트 준비에 실패했어요. 현재 버전은 유지됩니다.";SunlitFishVisuals.LOG.warn("Better Sunlit update preparation failed",e);}finally{BUSY.set(false);}});}
 static void toggleAuto(){auto=!auto;try{Properties p=new Properties();p.setProperty("enabled",Boolean.toString(auto));UpdateFiles.writeProperties(game().resolve("config/better-sunlit-updater.properties"),p);}catch(Exception e){SunlitFishVisuals.LOG.warn("Cannot save updater preference",e);}}
}
