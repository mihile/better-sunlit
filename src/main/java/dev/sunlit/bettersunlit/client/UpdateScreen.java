package dev.sunlit.bettersunlit.client;
import dev.sunlit.bettersunlit.update.*;import java.util.*;
import net.minecraft.client.gui.screens.Screen;import net.minecraft.client.gui.GuiGraphics;import net.minecraft.client.gui.components.Button;import net.minecraft.network.chat.Component;
public final class UpdateScreen extends Screen {
 final Screen parent;final Set<Integer> expanded=new HashSet<>();int scroll,maxScroll,left,width,top,bottom;Button apply,automatic;List<String> displayed=List.of();
 public UpdateScreen(Screen parent){super(Component.m_237113_("Better Sunlit"));this.parent=parent;}
 static List<String> installedNotes(){List<String> list=new ArrayList<>();for(int i=0;i<40;i++){String v=Build.META.getProperty("notes."+i);if(v==null)break;list.add(v);}return list.isEmpty()?List.of("자연스러운 낚시 연출 :: 낚은 물고기가 땅에서 펄떡이다가 점점 힘이 빠지고 멈춥니다. 약해지기 시작하면 가까이 가서 원래 별 등급과 수량 그대로 회수할 수 있어요. /realfishing으로 활발한 시간과 약해지는 시간을 설정할 수 있습니다."):List.copyOf(list);}
 List<String> notes(){return UpdateClient.release!=null&&UpdateClient.release.build()>Build.NUMBER?UpdateClient.release.notes():installedNotes();}
 @Override protected void m_7856_(){width=Math.min(540,f_96543_-24);left=(f_96543_-width)/2;top=82;bottom=f_96544_-80;int y=top-scroll;List<String> notes=notes();displayed=notes;
  for(int i=0;i<notes.size();i++){final int id=i;String[] parts=notes.get(i).split(" :: ",2);if(y>=top&&y+20<=bottom)m_142416_(Button.m_253074_(Component.m_237113_((expanded.contains(i)?"− ":"+ ")+parts[0]),b->{if(!expanded.add(id))expanded.remove(id);m_232761_();}).m_252987_(left+8,y,width-16,20).m_253136_());y+=24;if(expanded.contains(i)&&parts.length>1)y+=f_96547_.m_92923_(Component.m_237113_(parts[1]),width-36).size()*12+10;}
  maxScroll=Math.max(0,y+scroll-top-(bottom-top));scroll=Math.min(scroll,maxScroll);
  apply=m_142416_(Button.m_253074_(Component.m_237113_("종료 후 적용"),b->{if(UpdateClient.queued)f_96541_.m_91395_();else UpdateClient.prepare();}).m_252987_(left,bottom+12,width/2-4,20).m_253136_());
  m_142416_(Button.m_253074_(Component.m_237113_("나중에"),b->m_7379_()).m_252987_(left+width/2+4,bottom+12,width/2-4,20).m_253136_());
  m_142416_(Button.m_253074_(Component.m_237113_("다시 확인"),b->UpdateClient.check()).m_252987_(left,bottom+36,width/2-4,20).m_253136_());automatic=m_142416_(Button.m_253074_(Component.m_237113_("자동 확인"),b->UpdateClient.toggleAuto()).m_252987_(left+width/2+4,bottom+36,width/2-4,20).m_253136_());
 }
 @Override public void m_88315_(GuiGraphics g,int mx,int my,float dt){if(!notes().equals(displayed))m_232761_();g.m_280509_(0,0,f_96543_,f_96544_,0xF2152026);g.m_280137_(f_96547_,"Better Sunlit",f_96543_/2,16,0xFFE8F0D0);g.m_280137_(f_96547_,"설치 버전 "+Build.LABEL+" · "+Build.CHANNEL,f_96543_/2,34,0xFFADC7C7);g.m_280137_(f_96547_,UpdateClient.status,f_96543_/2,52,UpdateClient.failed?0xFFFFB39F:0xFFE1E9E9);
  List<String> notes=notes();int y=top-scroll;for(int i=0;i<notes.size();i++){String[]parts=notes.get(i).split(" :: ",2);y+=24;if(expanded.contains(i)&&parts.length>1){for(var line:f_96547_.m_92923_(Component.m_237113_(parts[1]),width-36)){if(y>=top&&y+10<bottom)g.m_280648_(f_96547_,line,left+18,y,0xFFD0DDDD);y+=12;}y+=10;}}
  apply.f_93623_=UpdateClient.queued||(!UpdateClient.BUSY.get()&&UpdateClient.release!=null&&UpdateClient.release.build()>Build.NUMBER);apply.m_93666_(Component.m_237113_(UpdateClient.queued?"게임 종료 후 적용":"종료 후 적용"));automatic.m_93666_(Component.m_237113_("자동 확인: "+(UpdateClient.auto?"켜짐":"꺼짐")));super.m_88315_(g,mx,my,dt);
 }
 @Override public boolean m_6050_(double x,double y,double amount){if(y>=top&&y<=bottom){scroll=Math.max(0,Math.min(maxScroll,scroll-(int)(amount*24)));m_232761_();return true;}return super.m_6050_(x,y,amount);}
 @Override public void m_7379_(){f_96541_.m_91152_(parent);}
}
