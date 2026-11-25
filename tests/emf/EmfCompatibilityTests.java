import dev.sunlit.bettersunlit.client.EmfLoadingContext;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

public final class EmfCompatibilityTests {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    public static class Legacy implements EmfLoadingContext.ConcurrentModelManager {
        public String currentSpecifiedModelLoading;
        public Object currentBlockEntityTypeLoading;
    }
    public static class Modern implements EmfLoadingContext.ConcurrentModelManager {
        public final ThreadLocal<String> currentSpecifiedModelLoading = new ThreadLocal<>();
        public Object currentBlockEntityTypeLoading;
    }
    public static class Unknown { public int currentSpecifiedModelLoading; public Object currentBlockEntityTypeLoading; }
    public static class Textures implements EmfLoadingContext.ConcurrentTextureManager {}
    static ClassNode node(byte[] bytes) { var n=new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    static byte[] resource(String name) throws Exception {
        try (var in=EmfCompatibilityTests.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            return Objects.requireNonNull(in,name).readAllBytes();
        }
    }
    static void gates(List<Path> cp, Path emf, Path etf, boolean reviewed, boolean concurrent) throws Exception {
        var paths=new ArrayList<Path>(); if(emf!=null)paths.add(emf); if(etf!=null)paths.add(etf); paths.addAll(cp);
        URL[] urls=paths.stream().map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
        try(var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader())) {
            var type=Class.forName("dev.sunlit.bettersunlit.inline.EntryMixinPlugin",true,loader);
            Object plugin=type.getConstructor().newInstance(); var gate=type.getMethod("canApplyGate",String.class,String.class);
            check((boolean)gate.invoke(plugin,"net.minecraft.client.model.geom.EntityModelSet","PerfEntityModelSetMixin")==reviewed,"model-set dependency gate");
            check((boolean)gate.invoke(plugin,"traben.entity_model_features.EMFManager","PerfEmfManagerInitMixin")==concurrent,"EMF concurrent-cache gate");
        }
    }
    public static void main(String[] args) throws Exception {
        var old=new Legacy(); var modern=new Modern(); var textures=new Textures();
        var legacyContext=EmfLoadingContext.resolve(Legacy.class); var modernContext=EmfLoadingContext.resolve(Modern.class);
        check(!legacyContext.hasSpecialContext(old),"legacy null context");
        old.currentSpecifiedModelLoading=""; check(!legacyContext.hasSpecialContext(old),"legacy empty context");
        old.currentSpecifiedModelLoading="boat"; check(legacyContext.hasSpecialContext(old),"legacy named context");
        check(old.currentSpecifiedModelLoading.equals("boat"),"legacy read mutated context");
        old.currentSpecifiedModelLoading="";old.currentBlockEntityTypeLoading=new Object();check(legacyContext.hasSpecialContext(old),"legacy block entity context");
        check(!legacyContext.canBakeEmfInParallel(old,textures),"shared legacy context allowed on workers");
        check(!modernContext.hasSpecialContext(modern),"modern null context");
        modern.currentSpecifiedModelLoading.set("");check(!modernContext.hasSpecialContext(modern),"modern empty context");
        modern.currentSpecifiedModelLoading.set("armor");check(modernContext.hasSpecialContext(modern),"modern named context");
        check(modernContext.canBakeEmfInParallel(modern,textures),"reviewed modern parallel path disabled");
        check(!modernContext.canBakeEmfInParallel(modern,new Object()),"missing ETF mixin allowed workers");
        check(!modernContext.canBakeEmfInParallel(new Object(),textures),"missing EMF mixin allowed workers");
        var worker=Executors.newSingleThreadExecutor();
        try {
            check(!worker.submit(()->modernContext.hasSpecialContext(modern)).get(),"render-thread name escaped into worker");
            check(worker.submit(()->{modern.currentSpecifiedModelLoading.set("worker");return modernContext.hasSpecialContext(modern);}).get(),"worker context ignored");
            check(modern.currentSpecifiedModelLoading.get().equals("armor"),"worker overwrote render context");
        } finally {worker.shutdownNow();}
        modern.currentSpecifiedModelLoading.remove(); modern.currentBlockEntityTypeLoading=new Object();
        check(modernContext.hasSpecialContext(modern),"modern block entity context");
        var unknown=EmfLoadingContext.resolve(Unknown.class);
        check(unknown.hasSpecialContext(new Unknown())&&!unknown.canBakeEmfInParallel(new Unknown(),textures),"unknown descriptor accepted");
        check(EmfLoadingContext.resolve(Object.class).hasSpecialContext(new Object()),"missing fields accepted");
        check(modernContext.hasSpecialContext(new Object()),"wrong manager receiver accepted");
        check(!legacyContext.hasSpecialContext(null),"null manager changed");

        Path oldEmf=Path.of(args[0]),oldEtf=Path.of(args[1]),newEmf=Path.of(args[2]),newEtf=Path.of(args[3]);
        List<Path> cp=Files.readAllLines(Path.of(args[4])).stream().map(Path::of).toList();
        for(var pair:List.of(Map.entry(oldEmf,"Ljava/lang/String;"),Map.entry(newEmf,"Ljava/lang/ThreadLocal;"))) {
            try(var jar=new JarFile(pair.getKey().toFile())) {
                var n=node(jar.getInputStream(jar.getJarEntry("traben/entity_model_features/EMFManager.class")).readAllBytes());
                check(n.fields.stream().anyMatch(f->f.name.equals("currentSpecifiedModelLoading")&&f.desc.equals(pair.getValue())),"actual EMF descriptor differs");
                check(n.methods.stream().anyMatch(m->m.name.equals("injectIntoModelRootGetter")&&m.desc.equals("(Lnet/minecraft/client/model/geom/ModelLayerLocation;Lnet/minecraft/client/model/geom/ModelPart;)Lnet/minecraft/client/model/geom/ModelPart;")),"actual EMF injection contract differs");
            }
        }
        gates(cp,oldEmf,oldEtf,true,true);gates(cp,newEmf,newEtf,true,true);
        gates(cp,null,newEtf,false,false);gates(cp,newEmf,null,false,true);
        Path changed=Path.of(args[5]);Files.createDirectories(changed.resolve("traben/entity_model_features"));
        try(var jar=new JarFile(oldEmf.toFile())) {
            var n=node(jar.getInputStream(jar.getJarEntry("traben/entity_model_features/EMFManager.class")).readAllBytes());
            n.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,"unknownVersion","I",null,null));
            var writer=new ClassWriter(0);n.accept(writer);Files.write(changed.resolve("traben/entity_model_features/EMFManager.class"),writer.toByteArray());
        }
        gates(cp,changed,oldEtf,false,false);
        for(var method:node(resource("dev.sunlit.bettersunlit.client.EmfModelPrebakePerf")).methods)
            for(var insn:method.instructions)if(insn instanceof FieldInsnNode f)
                check(!f.name.equals("currentSpecifiedModelLoading"),"compiled helper retains version-specific field link");
        for(String[] pair:new String[][]{{"PerfEmfManagerInitMixin","ConcurrentModelManager"},{"PerfEtfManagerInitMixin","ConcurrentTextureManager"}})
            check(node(resource("dev.sunlit.fishvisuals.mixin."+pair[0])).interfaces.contains("dev/sunlit/bettersunlit/client/EmfLoadingContext$"+pair[1]),"actual mixin missing concurrency marker");
        prepareAnimationFixture(newEmf, Path.of(args[6]));
        System.out.println("PASS: "+checks+" EMF compatibility checks; actual 3.2.4/3.3.12 binaries, context isolation and dependency gates; no game launched.");
    }
    private static void prepareAnimationFixture(Path actualEmf, Path fixture) throws Exception {
        // Apply the ModelPart access transformation normally supplied by Forge.
        String modelPart = "net.minecraft.client.model.geom.ModelPart";
        ClassNode part = node(resource(modelPart));
        part.access &= ~Opcodes.ACC_FINAL;
        for (var field : part.fields) {
            if (java.util.Set.of("f_104212_", "f_104213_").contains(field.name)) {
                field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
            }
        }
        writeFixture(fixture, part);
        for (String nested : List.of("Cube", "Polygon", "Vertex")) {
            ClassNode type = node(resource(modelPart + "$" + nested));
            type.access = (type.access & ~Opcodes.ACC_FINAL) | Opcodes.ACC_PUBLIC;
            for (var field : type.fields) {
                if (field.name.equals("f_104341_")) {
                    field.access = (field.access & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PUBLIC;
                }
            }
            writeFixture(fixture, type);
        }

        // Keep EMF's actual shared flag and expression evaluation. The standalone test
        // does not have Forge's active mod list for the two optional-mod constructor probes.
        ClassNode manager;
        try (var jar = new JarFile(actualEmf.toFile());
             var source = jar.getInputStream(jar.getJarEntry("traben/entity_model_features/EMFManager.class"))) {
            manager = node(source.readAllBytes());
        }
        int probes = 0;
        for (MethodNode constructor : manager.methods) {
            if (!constructor.name.equals("<init>")) continue;
            for (AbstractInsnNode instruction : constructor.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("isThisModLoaded")
                        && call.owner.equals("traben/entity_texture_features/ETF")
                        && call.desc.equals("(Ljava/lang/String;)Z")) {
                    constructor.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                    constructor.instructions.set(call, new InsnNode(Opcodes.ICONST_0));
                    probes++;
                }
            }
        }
        check(probes == 2, "unexpected optional-mod probes in actual EMF constructor");
        var initializer = manager.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        for (AbstractInsnNode instruction : initializer.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                    && field.name.equals("EBETypes")) {
                // The block entity registry is irrelevant to animation expression validation.
                for (AbstractInsnNode previous = initializer.instructions.getFirst(); previous != field; ) {
                    AbstractInsnNode next = previous.getNext();
                    if (previous.getOpcode() >= 0) initializer.instructions.remove(previous);
                    previous = next;
                }
                initializer.instructions.insertBefore(field,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true));
                break;
            }
        }
        writeFixture(fixture, manager);
    }

    private static void writeFixture(Path root, ClassNode source) throws Exception {
        Path output = root.resolve(source.name + ".class");
        Files.createDirectories(output.getParent());
        var writer = new ClassWriter(0);
        source.accept(writer);
        Files.write(output, writer.toByteArray());
    }

}
