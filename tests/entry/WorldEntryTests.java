import dev.sunlit.bettersunlit.client.ScopedCandidateCache;
import dev.sunlit.bettersunlit.client.RecipeGrouping;
import dev.sunlit.bettersunlit.client.PropertyNameCache;
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
        testRecipeGrouping();
        testKeyedGrouping();
        Utf8DigestContracts.run();
        for (int value=-256; value<65536; value++)
            check(PropertyNameCache.integerName(value).equals(Integer.toString(value)), "integer property name changed");
        for (int value : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE})
            check(PropertyNameCache.integerName(value).equals(Integer.toString(value)), "extreme property name changed");
        for (int value=0; value<256; value++)
            check(PropertyNameCache.integerName(value)==PropertyNameCache.integerName(value), "small names allocated again");
        check(new EntryMixinPlugin().canApplyGate("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping", "PerfIrisBlockMappingMixin"), "integer-name target hash rejected");
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

    record Input(int ingredient, int output) {}
    static final class Group {
        final int ingredient;
        final List<Input> outputs = new ArrayList<>();
        Group(int ingredient) { this.ingredient = ingredient; }
    }
    static void testKeyedGrouping() {
        Random random = new Random(20261118);
        for(int trial=0;trial<400;trial++) {
            List<Input> input=new ArrayList<>();
            for(int i=0;i<300;i++) input.add(new Input(random.nextInt(250),i));
            java.util.function.BiPredicate<Integer,Integer> match=(a,b)->a%7==b%7 && a>=b && a-b<=21;
            List<String> baselineCalls=new ArrayList<>(), fastCalls=new ArrayList<>();
            var expected=RecipeGrouping.group(input,Input::ingredient,(a,b)->{baselineCalls.add(a+":"+b);return match.test(a,b);},Group::new,(g,r)->g.outputs.add(r));
            boolean mixed=trial%2==0;
            var actual=RecipeGrouping.groupKeyed(input,Input::ingredient,a->mixed && a%13==0?null:a%7,(a,b)->{fastCalls.add(a+":"+b);return match.test(a,b);},Group::new,(g,r)->g.outputs.add(r));
            var necessaryCalls=baselineCalls.stream().filter(pair->{var parts=pair.split(":");int a=Integer.parseInt(parts[0]),b=Integer.parseInt(parts[1]);return mixed&&(a%13==0||b%13==0)||a%7==b%7;}).toList();
            check(fastCalls.equals(necessaryCalls),"keyed matcher changed directional/first-match ordering");
            check(actual.size()==expected.size(),"keyed group count");
            for(int i=0;i<expected.size();i++) {
                check(actual.get(i).ingredient==expected.get(i).ingredient,"keyed input identity/order");
                check(actual.get(i).outputs.equals(expected.get(i).outputs),"keyed output identity/order");
            }
        }
        List<Input> input=new ArrayList<>();for(int i=0;i<8000;i++)input.add(new Input(i%2000,i));
        AtomicInteger baselineCount=new AtomicInteger(),fastCount=new AtomicInteger();
        RecipeGrouping.group(input,Input::ingredient,(a,b)->{baselineCount.incrementAndGet();return a.equals(b);},Group::new,(g,r)->g.outputs.add(r));
        RecipeGrouping.groupKeyed(input,Input::ingredient,a->a%31,(a,b)->{fastCount.incrementAndGet();return a.equals(b);},Group::new,(g,r)->g.outputs.add(r));
        check(fastCount.get()<baselineCount.get()/20,"coarse index did not reduce comparisons");
        System.out.println("Keyed grouping matcher calls: "+baselineCount+" -> "+fastCount);
    }

    static void testRecipeGrouping() throws Exception {
        Random random = new Random(0x51A17);
        for (int trial = 0; trial < 300; trial++) {
            List<Input> inputs = new ArrayList<>();
            for (int i = 0, n = random.nextInt(160); i < n; i++) inputs.add(new Input(random.nextInt(25), i));
            // Include directional/non-transitive matches: bucketing or sorting would change results.
            java.util.function.BiPredicate<Integer,Integer> match = switch (trial % 3) {
                case 0 -> (a,b) -> a.equals(b);
                case 1 -> (a,b) -> Math.abs(a-b) <= 1;
                default -> (a,b) -> a >= b && a-b <= 2;
            };
            List<Group> expected = new ArrayList<>();
            List<String> expectedCalls = new ArrayList<>(), actualCalls = new ArrayList<>();
            for (Input input : inputs) {
                Group selected = null;
                for (Group group : expected) {
                    expectedCalls.add(input.ingredient + ":" + group.ingredient);
                    if (match.test(input.ingredient, group.ingredient)) { selected = group; break; }
                }
                if (selected == null) { selected = new Group(input.ingredient); expected.add(selected); }
                selected.outputs.add(input);
            }
            AtomicInteger reads = new AtomicInteger();
            List<Group> actual = RecipeGrouping.group(inputs, input -> { reads.incrementAndGet(); return input.ingredient; },
                    (a,b) -> { actualCalls.add(a + ":" + b); return match.test(a,b); }, Group::new,
                    (group,input) -> group.outputs.add(input));
            check(reads.get() == inputs.size(), "ingredient read more than once per source recipe");
            check(expectedCalls.equals(actualCalls), "first-match comparator ordering changed");
            check(expected.size() == actual.size(), "recipe group count changed");
            for (int i=0;i<actual.size();i++) {
                check(expected.get(i).ingredient == actual.get(i).ingredient, "group ingredient/order changed");
                var want = expected.get(i).outputs; var got = actual.get(i).outputs;
                check(want.size() == got.size(), "output count changed");
                for (int j=0;j<got.size();j++) check(want.get(j) == got.get(j), "output identity/order changed");
            }
        }
        try {
            RecipeGrouping.group(List.of(new Input(1,1)), Input::ingredient, Integer::equals, Group::new,
                    (group,input) -> { throw new IllegalStateException("expected"); });
            throw new AssertionError("append exception swallowed");
        } catch (IllegalStateException expected) {}
        check(RecipeGrouping.group(List.of(new Input(2,2)), Input::ingredient, Integer::equals, Group::new,
                (group,input) -> group.outputs.add(input)).get(0).ingredient == 2, "group state survived exception");

        var plugin = new EntryMixinPlugin();
        for (String[] pair : new String[][] {
                {"com.simibubi.create.compat.jei.category.BlockCuttingCategory", "PerfCreateBlockCuttingMixin"},
                {"net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping", "PerfIrisBlockMappingMixin"}}) {
            check(plugin.canApplyGate(pair[0], pair[1]), "join patch rejected installed binary " + pair[0]);
            check(!plugin.canApplyGate("missing.changed.Binary", pair[1]), "join patch accepted changed target");
        }
        var factory = Class.forName("dev.sunlit.fishvisuals.mixin.PerfIrisBlockMappingMixin")
                .getDeclaredMethod("sunlit$sizeMaterialMap"); factory.setAccessible(true);
        @SuppressWarnings("unchecked") var map = (it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<Object>) factory.invoke(null);
        Object key = new Object();
        check(map.defaultReturnValue() == 0, "Iris missing-state sentinel changed");
        check(map.putIfAbsent(key, 7) == 0 && map.putIfAbsent(key, 9) == 7 && map.getInt(key) == 7,
                "Iris first matching material ID changed");
        var iris = node("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping"); int news=0;
        for (var method : iris.methods) if (method.name.equals("createBlockStateIdMap"))
            for (var insn : method.instructions) if (insn instanceof TypeInsnNode t && t.getOpcode()==Opcodes.NEW
                    && t.desc.equals("it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap")) news++;
        check(news==1, "Iris constructor redirect missing/ambiguous");
    }
}
