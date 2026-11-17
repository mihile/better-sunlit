package dev.sunlit.bettersunlit.client;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import java.util.ArrayDeque;
/** Per-invocation memoization: no cached world data survives return or an exception. */
public final class LegacyBiomeCache {
 private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
 private static final ThreadLocal<ArrayDeque<Scope>> FREE=ThreadLocal.withInitial(ArrayDeque::new);
 private LegacyBiomeCache(){}
 public static Scope open(){Scope s=FREE.get().pollFirst();if(s==null)s=new Scope();s.previous=ACTIVE.get();ACTIVE.set(s);return s;}
 public static Holder<Biome> get(ClientLevel level,BlockPos pos){Scope s=ACTIVE.get();int x=pos.m_123341_(),y=pos.m_123342_(),z=pos.m_123343_();if(s!=null&&s.level==level&&s.biome!=null&&s.x==x&&s.y==y&&s.z==z)return s.biome;Holder<Biome> b=level.m_204166_(pos);if(s!=null){s.level=level;s.x=x;s.y=y;s.z=z;s.biome=b;}return b;}
 public static final class Scope implements AutoCloseable {
  private Scope previous;private ClientLevel level;private Holder<Biome> biome;private int x,y,z;
  private Scope(){}
  @Override public void close(){if(ACTIVE.get()!=this)throw new IllegalStateException("Biome scope order");Scope p=previous;if(p==null)ACTIVE.remove();else ACTIVE.set(p);previous=null;level=null;biome=null;var pool=FREE.get();if(pool.size()<4)pool.addFirst(this);}
 }
}
