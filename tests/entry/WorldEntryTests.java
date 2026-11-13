import dev.sunlit.bettersunlit.client.ScopedCandidateCache;
import dev.sunlit.bettersunlit.inline.EntryMixinPlugin;
import com.google.common.base.Ticker;
import com.google.common.cache.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.*;

/** Pure JVM checks: no Forge bootstrap, game process, world, or graphics. */
public final class WorldEntryTests {
    static int checks;static long sink;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    record Entry(int id,boolean core){}
    static byte[] bytes(String name)throws Exception {
        try(var in=WorldEntryTests.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            if(in==null)throw new AssertionError("Missing class "+name);return in.readAllBytes();
        }
    }
    static ClassNode node(String name)throws Exception {var n=new ClassNode();new ClassReader(bytes(name)).accept(n,0);return n;}
    static long exercise(List<Entry> all,boolean cached,int tabs) {
        long total=0;Object registry=new Object();
        try(var scope=ScopedCandidateCache.open()) {
            for(int tab=0;tab<tabs;tab++) {
                int selected=tab;
                Collection<Entry> candidates=cached?ScopedCandidateCache.candidates(registry,()->all,Entry::core):all;
                // The original tab-specific test runs after the base-type test.
                for(Entry e:candidates)if(e.core&&e.id%7==selected%7)total+=e.id;
            }
        }
        return total;
    }
    public static void main(String[] args)throws Exception {
        Object registry=new Object();List<Entry> entries=new ArrayList<>(List.of(new Entry(1,false),new Entry(2,true),new Entry(3,true),new Entry(4,false)));
        var calls=new AtomicInteger();
        check(ScopedCandidateCache.candidates(registry,()->entries,e->{calls.incrementAndGet();return e.core;})==entries,"out-of-scope collection changed");
        check(calls.get()==0,"out-of-scope predicate ran");
        try(var scope=ScopedCandidateCache.open()) {
            var first=ScopedCandidateCache.candidates(registry,()->entries,e->{calls.incrementAndGet();return e.core;});
            check(new ArrayList<>(first).equals(entries.stream().filter(Entry::core).toList()),"candidate contents/order changed");
            for(int i=0;i<40;i++)check(first==ScopedCandidateCache.candidates(registry,()->{throw new AssertionError("registry traversed twice");},Entry::core),"cached reference changed");
            check(calls.get()==entries.size(),"more than one full traversal");
            try(var nested=ScopedCandidateCache.open()) {
                check(ScopedCandidateCache.candidates(registry,()->entries,e->e.id==1).size()==1,"nested build did not isolate cache");
            }
            check(first==ScopedCandidateCache.candidates(registry,()->entries,Entry::core),"nested scope did not restore previous cache");
        }
        check(ScopedCandidateCache.candidates(registry,()->entries,Entry::core)==entries,"cache leaked outside scope");
        entries.add(new Entry(5,true));
        try(var scope=ScopedCandidateCache.open()){check(ScopedCandidateCache.candidates(registry,()->entries,Entry::core).size()==3,"next build reused stale registry");}
        try{try(var scope=ScopedCandidateCache.open()){ScopedCandidateCache.candidates(registry,()->entries,Entry::core);throw new IllegalStateException();}}catch(IllegalStateException expected){}
        check(ScopedCandidateCache.candidates(registry,()->entries,Entry::core)==entries,"exception left a thread-local cache");
        try(var scope=ScopedCandidateCache.open()) {
            ScopedCandidateCache.candidates(registry,()->entries,Entry::core);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{check(worker.submit(()->ScopedCandidateCache.candidates(registry,()->entries,Entry::core)==entries).get(),"cache escaped into another thread");}finally{worker.shutdownNow();}
        }

        // Execute the compiled cache-capacity redirect with Guava's real expiry.
        var mix=Class.forName("dev.sunlit.fishvisuals.mixin.EntryJeiTypedCacheMixin");
        var capacity=mix.getDeclaredMethod("sunlit$avoidRepeatedCacheGrowth",CacheBuilder.class,int.class);capacity.setAccessible(true);
        class Clock extends Ticker {long now;public long read(){return now;}}
        Clock clock=new Clock();AtomicInteger loads=new AtomicInteger();
        CacheBuilder<Object,Object> builder=CacheBuilder.newBuilder().ticker(clock).expireAfterAccess(java.time.Duration.ofSeconds(1));
        check(capacity.invoke(null,builder,1)==builder,"redirect replaced builder");
        var cache=builder.build(new CacheLoader<Object,Object>(){public Object load(Object key){loads.incrementAndGet();return new Object();}});
        Object key=new Object(),one=cache.getUnchecked(key);clock.now=500_000_000L;check(cache.getUnchecked(key)==one&&loads.get()==1,"loader/cache identity changed");
        clock.now=1_500_000_001L;check(cache.getUnchecked(key)!=one&&loads.get()==2,"one-second access expiry changed");

        // Verify every redirect against the exact real binary and helper descriptor.
        for(String[] test:new String[][]{
            {"mezz.jei.library.plugins.vanilla.VanillaPlugin","registerIngredients","mezz/jei/library/plugins/vanilla/ingredients/ItemStackListFactory","create","(Lmezz/jei/common/util/StackHelper;)Ljava/util/List;"},
            {"com.supermartijn642.core.CoreLib","lambda$new$3","com/supermartijn642/core/registry/Registries$Registry","getValues","()Ljava/util/Collection;"},
            {"mezz.jei.library.ingredients.itemStacks.TypedItemStack","<clinit>","com/google/common/cache/CacheBuilder","concurrencyLevel","(I)Lcom/google/common/cache/CacheBuilder;"}}) {
            var original=node(test[0]);int count=0;
            for(var method:original.methods)if(method.name.equals(test[1]))for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(test[2])&&call.name.equals(test[3])&&call.desc.equals(test[4]))count++;
            check(count==1,"redirect is missing or ambiguous "+test[0]);
        }
        var plugin=new EntryMixinPlugin();var matches=EntryMixinPlugin.class.getDeclaredMethod("matches",String.class);matches.setAccessible(true);
        for(String name:List.of("mezz.jei.library.plugins.vanilla.VanillaPlugin","mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory","mezz.jei.library.ingredients.itemStacks.TypedItemStack","com.supermartijn642.core.CoreLib"))check((boolean)matches.invoke(plugin,name),"reviewed class hash differs "+name);
        check(!(boolean)matches.invoke(plugin,"missing.mod.NotInstalled"),"absent mod accepted");

        // A missing optional target must never be advertised to Mixin.
        int targetCount=0;
        for(String name:List.of("InlineCommonMixin","InlineClientMixin","InlineCommonInterfaceMixin","InlineClientInterfaceMixin")) {
            String resource="dev/sunlit/fishvisuals/mixin/"+name+".class";var in=WorldEntryTests.class.getClassLoader().getResourceAsStream(resource);if(in==null)continue;
            ClassNode generated=new ClassNode();new ClassReader(in.readAllBytes()).accept(generated,0);in.close();
            var annotations=new ArrayList<AnnotationNode>();if(generated.visibleAnnotations!=null)annotations.addAll(generated.visibleAnnotations);if(generated.invisibleAnnotations!=null)annotations.addAll(generated.invisibleAnnotations);
            for(var a:annotations)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals("targets"))for(Object target:(List<?>)a.values.get(i+1)){
                check(WorldEntryTests.class.getClassLoader().getResource(target.toString().replace('.','/')+".class")!=null,"missing target still advertised "+target);targetCount++;
            }
        }
        List<Entry> large=new ArrayList<>();for(int i=0;i<40_000;i++)large.add(new Entry(i,i%100==0));
        check(exercise(large,false,32)==exercise(large,true,32),"tab list/sum changed");
        for(int i=0;i<5;i++){sink^=exercise(large,false,32);sink^=exercise(large,true,32);}
        long a=System.nanoTime();for(int i=0;i<20;i++)sink^=exercise(large,false,32);long b=System.nanoTime();for(int i=0;i<20;i++)sink^=exercise(large,true,32);long c=System.nanoTime();
        System.out.printf("PASS: %d world-entry checks, %d installed optional targets. Synthetic 40,000 entries x 32 tabs: original %.2f ms, scoped %.2f ms / 20 builds. Not an in-game benchmark.%n",checks,targetCount,(b-a)/1e6,(c-b)/1e6);
    }
}
