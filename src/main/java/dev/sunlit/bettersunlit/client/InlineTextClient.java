package dev.sunlit.bettersunlit.client;
import dev.sunlit.bettersunlit.inline.InlineText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import java.util.concurrent.ConcurrentHashMap;
public final class InlineTextClient {
 private record Cached(Language language,String source,String translated) {}
 private static final ConcurrentHashMap<String,Cached> CACHE=new ConcurrentHashMap<>();
 public static String localize(String text,String group) {
  Language language=Language.m_128107_();Cached old=CACHE.get(group);
  if(old!=null&&old.language==language&&old.source.equals(text))return old.translated;
  String translated=InlineText.literal(text,group).getString();CACHE.put(group,new Cached(language,text,translated));return translated;
 }
 public static void draw0(GuiGraphics gui,Font font,String text,int x,int y,int color,String group) {gui.m_280137_(font,localize(text,group),x,y,color);}
 public static int draw1(GuiGraphics gui,Font font,String text,int x,int y,int color,String group) {return gui.m_280488_(font,localize(text,group),x,y,color);}
 public static int draw2(GuiGraphics gui,Font font,String text,int x,int y,int color,boolean shadow,String group) {return gui.m_280056_(font,localize(text,group),x,y,color,shadow);}
}
