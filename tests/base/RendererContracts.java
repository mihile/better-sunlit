import java.util.*;
import java.lang.reflect.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import dev.sunlit.bettersunlit.client.StableVertexRadixSort;
import me.jellysquid.mods.sodium.client.util.sorting.MergeSort;

/** Compare actual original sort output and execute the actual sampler patch with a recording GL backend. */
public class RendererContracts {
    static int checks, calls;
    static int[] bindings;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    public static void bindOne(int unit, int sampler) { bindings[unit] = sampler; calls++; }
    public static void bindMulti(int first, int count, long pointer) {
        check(pointer == 0L, "multibind must use the GL null/zero semantics");
        Arrays.fill(bindings, first, first + count, 0); calls++;
    }
    static void compare(float[] keys) {
        int[] expected = MergeSort.mergeSort(keys), result = StableVertexRadixSort.sort(keys);
        if (result == null) result = MergeSort.mergeSort(keys);
        check(Arrays.equals(expected, result), "radix differs from original at size " + keys.length);
        int[] copy = result.clone();
        StableVertexRadixSort.sort(new float[Math.max(256, keys.length)]);
        check(Arrays.equals(copy, result), "returned indices reused as scratch");
    }
    static void sorts() throws Exception {
        Random random = new Random(2026111802L);
        for (int size : new int[]{0,1,15,16,255,256,257,511,1024,4096,65536,65537}) {
            float[] keys = new float[size];
            for (int i = 0; i < size; i++) keys[i] = random.nextFloat() * 100 - 50;
            compare(keys); Arrays.fill(keys, 1); compare(keys);
            for (int i = 0; i < size; i++) keys[i] = i % 2 == 0 ? 0.0f : -0.0f;
            compare(keys);
        }
        for (int trial = 0; trial < 1200; trial++) {
            float[] keys = new float[256 + random.nextInt(2048)];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = switch (trial % 4) {
                    case 0 -> random.nextInt(17) - 8;
                    case 1 -> Float.intBitsToFloat(random.nextInt());
                    case 2 -> i % 3 == 0 ? Float.POSITIVE_INFINITY : i % 3 == 1 ? Float.NEGATIVE_INFINITY : 0;
                    default -> random.nextFloat() * random.nextFloat();
                };
            }
            compare(keys);
        }
        float[] nan = new float[256]; nan[30] = Float.NaN;
        check(StableVertexRadixSort.sort(nan) == null, "NaN must retain original comparator behavior");
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var threads = new ArrayList<Thread>();
        for (int worker = 0; worker < 4; worker++) {
            final int seed = worker;
            threads.add(new Thread(() -> { try {
                Random r = new Random(seed);
                for (int trial = 0; trial < 200; trial++) {
                    float[] keys = new float[256 + r.nextInt(1024)];
                    for (int i = 0; i < keys.length; i++) keys[i] = r.nextInt(32);
                    if (!Arrays.equals(MergeSort.mergeSort(keys), StableVertexRadixSort.sort(keys))) throw new AssertionError("thread isolation");
                }
            } catch (Throwable t) { failures.add(t); } }));
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();
        check(failures.isEmpty(), "worker scratch isolation: " + failures);
        float[] keys = new float[8192];
        for (int i = 0; i < keys.length; i++) keys[i] = random.nextFloat();
        for (int i = 0; i < 100; i++) { MergeSort.mergeSort(keys); StableVertexRadixSort.sort(keys); }
        long start = System.nanoTime(); for (int i = 0; i < 300; i++) MergeSort.mergeSort(keys);
        long baseline = System.nanoTime() - start;
        start = System.nanoTime(); for (int i = 0; i < 300; i++) StableVertexRadixSort.sort(keys);
        System.out.printf("Sort CPU comparison 8192 x 300: original %.2f ms, radix %.2f ms (not an FPS benchmark)%n", baseline / 1e6, (System.nanoTime()-start)/1e6);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().getId();
            long before=bean.getThreadAllocatedBytes(id);for(int i=0;i<300;i++) MergeSort.mergeSort(keys);
            long originalBytes=bean.getThreadAllocatedBytes(id)-before;
            before=bean.getThreadAllocatedBytes(id);for(int i=0;i<300;i++) StableVertexRadixSort.sort(keys);
            System.out.printf("Sort allocated bytes/call (8192): original %d, radix %d; returned array included%n",originalBytes/300,(bean.getThreadAllocatedBytes(id)-before)/300);
        }
    }
    static Class<?> patchClass(String shortName, boolean gl) throws Exception {
        ClassNode node = BaseOptimizationContracts.node("dev.sunlit.fishvisuals.mixin." + shortName);
        node.access &= ~Opcodes.ACC_ABSTRACT;
        for (MethodNode m : node.methods) for (var insn : m.instructions) if (insn instanceof MethodInsnNode call && gl) {
            if (call.owner.equals("org/lwjgl/opengl/GL33C") && call.name.equals("glBindSampler")) { call.owner="RendererContracts"; call.name="bindOne"; }
            if (call.owner.equals("org/lwjgl/opengl/GL44C") && call.name.equals("nglBindSamplers")) { call.owner="RendererContracts"; call.name="bindMulti"; }
        }
        ClassWriter writer = new ClassWriter(0); node.accept(writer); byte[] data = writer.toByteArray();
        return new ClassLoader(RendererContracts.class.getClassLoader()) { Class<?> define() { return defineClass(null, data, 0, data.length); } }.define();
    }
    static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    static void sampler() throws Exception {
        Class<?> type = patchClass("PerfOculusSamplerUnbindMixin", true);
        Field samplers=field(type,"samplers"), multi=field(type,"hasMultibind"), high=field(type,"sunlit$highestSampler");
        Method unbind=type.getMethod("unbindAllSamplers");
        for (boolean mode : new boolean[]{false,true}) for (int unit : new int[]{0,1,7,31}) {
            bindings=new int[32]; int[] mirror=new int[32]; samplers.set(null,mirror); multi.setBoolean(null,mode);
            high.setInt(null,-1); calls=0; unbind.invoke(null); check(calls==0,"empty range issued GL calls");
            bindings[unit]=mirror[unit]=10; high.setInt(null,unit); unbind.invoke(null);
            check(Arrays.equals(bindings,new int[32]) && Arrays.equals(mirror,bindings),"GL and cached sampler state disagree");
            check(high.getInt(null)==-1,"range did not reset"); int previous=calls; unbind.invoke(null);
            check(calls==previous,"repeated unbind issued GL calls");
            // Explicitly binding the high unit back to zero may overestimate range but must stay correct.
            bindings[0]=mirror[0]=3; high.setInt(null,unit); unbind.invoke(null);
            check(Arrays.equals(bindings,new int[32]),"lower live binding survived a zero high unit");
        }
        Class<?> loops=patchClass("PerfOculusUniformLoopsMixin", false);
        Object instance=loops.getDeclaredConstructor().newInstance();
        var events=new ArrayList<Integer>();
        var listeners=com.google.common.collect.ImmutableList.<net.irisshaders.iris.gl.state.ValueUpdateNotifier>of(
            listener -> { check(listener==null,"listener not cleared"); events.add(1); },
            listener -> { check(listener==null,"listener not cleared"); events.add(2); });
        field(loops,"notifiersToReset").set(instance,listeners);
        loops.getMethod("removeListeners").invoke(instance);
        check(events.equals(List.of(1,2)),"listener reset order changed");
        Method stage=loops.getDeclaredMethod("updateStage",com.google.common.collect.ImmutableList.class);stage.setAccessible(true);
        stage.invoke(instance,com.google.common.collect.ImmutableList.of());
        events.clear();
        var uniforms=com.google.common.collect.ImmutableList.of(
            new net.irisshaders.iris.gl.uniform.RendererTestUniform(() -> events.add(3)),
            new net.irisshaders.iris.gl.uniform.RendererTestUniform(() -> events.add(4)));
        stage.invoke(instance,uniforms); stage.invoke(instance,uniforms);
        check(events.equals(List.of(3,4,3,4)),"uniform update order/frequency changed");
    }
    public static void main(String[] args) throws Exception {
        String[][] paths={{"PerfEmbeddiumVertexSortMixin","me.jellysquid.mods.sodium.client.util.sorting.VertexSorters$AbstractVertexSorter"},
            {"PerfOculusUniformLoopsMixin","net.irisshaders.iris.gl.program.ProgramUniforms"},
            {"PerfOculusSamplerLoopsMixin","net.irisshaders.iris.gl.program.ProgramSamplers"},
            {"PerfOculusSamplerUnbindMixin","net.irisshaders.iris.gl.IrisRenderSystem"}};
        for (String[] path : paths) {
            check(BaseOptimizationContracts.isolatedGate(path[1],path[0],false,false,false),path[0]+" old gate");
            check(!BaseOptimizationContracts.isolatedGate(path[1],path[0],false,false,true),path[0]+" duplicate modern patch");
            check(!BaseOptimizationContracts.isolatedGate(path[1],path[0],false,true,false),path[0]+" mutated binary");
            check(!BaseOptimizationContracts.isolatedGate(path[1],path[0],true,false,false),path[0]+" missing mod");
            ClassNode mix=BaseOptimizationContracts.node("dev.sunlit.fishvisuals.mixin."+path[0]), original=BaseOptimizationContracts.node(path[1]);
            for (FieldNode f : mix.fields) if (!f.name.startsWith("sunlit$")) check(BaseOptimizationContracts.exists(original,f.name,f.desc,true),path[0]+" shadow field "+f.name);
            for (MethodNode m : mix.methods) if (m.name.equals("update")||m.name.equals("removeListeners")||m.name.equals("updateStage")) {
                check(BaseOptimizationContracts.exists(original,m.name,m.desc,false),path[0]+" overwritten ABI");
                for (var insn : m.instructions) if(insn instanceof MethodInsnNode call) check(!call.name.equals("iterator"),"iterator retained");
            }
            for (MethodNode m : mix.methods) {
                var annotations=new ArrayList<AnnotationNode>();
                if(m.visibleAnnotations!=null) annotations.addAll(m.visibleAnnotations);
                if(m.invisibleAnnotations!=null) annotations.addAll(m.invisibleAnnotations);
                for(AnnotationNode annotation:annotations) if(annotation.desc.endsWith("/Inject;")||annotation.desc.endsWith("/Redirect;")) {
                    for(int i=0;i<annotation.values.size();i+=2) if(annotation.values.get(i).equals("method")) {
                        for(Object descriptor:(List<?>)annotation.values.get(i+1)) {
                            String target=descriptor.toString();int split=target.indexOf('(');
                            check(split>0 && BaseOptimizationContracts.exists(original,target.substring(0,split),target.substring(split),false),path[0]+" missing injection target "+target);
                        }
                    }
                }
            }
        }
        sorts(); sampler();
        System.out.println("PASS " + checks + " renderer contracts; original sort equivalence, isolation, GL-state reset and exact-version gates");
    }
}
