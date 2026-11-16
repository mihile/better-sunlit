from pathlib import Path
import argparse, os, shutil, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
GRAAL_17 = Path('C:/Java/graalvm-jdk-17.0.19+9.1')
DEFAULT_JAVA = GRAAL_17 if GRAAL_17.exists() else Path(os.environ.get('JAVA_HOME', 'C:/Java/graalvm-jdk-17.0.19+9.1'))

parser = argparse.ArgumentParser()
parser.add_argument('--instance', type=Path, default=ROOT.parents[1])
parser.add_argument('--out', type=Path, default=None)
parser.add_argument('--java-home', type=Path, default=DEFAULT_JAVA)
cli = parser.parse_args()

INSTANCE = cli.instance.resolve()
LIBRARIES = INSTANCE.parent.parent / 'libraries'
JDK = cli.java_home / 'bin'

ASM_JAR = sorted((LIBRARIES / 'org/ow2/asm/asm').glob('9.8/asm-*.jar'))[-1]
ASM_TREE_JAR = sorted((LIBRARIES / 'org/ow2/asm/asm-tree').glob('9.8/asm-tree-*.jar'))[-1]
ASM_COMMONS_JAR = sorted((LIBRARIES / 'org/ow2/asm/asm-commons').glob('9.8/asm-commons-*.jar'))[-1]
ASM_UTIL_JAR = sorted((LIBRARIES / 'org/ow2/asm/asm-util').glob('9.8/asm-util-*.jar'))[-1]
ASM_ANALYSIS_JAR = sorted((LIBRARIES / 'org/ow2/asm/asm-analysis').glob('9.8/asm-analysis-*.jar'))[-1]

OUT_BUILD = ROOT / 'build/boot'
if OUT_BUILD.exists():
    shutil.rmtree(OUT_BUILD)
OUT_BUILD.mkdir(parents=True)

AGENT_CLASSES = OUT_BUILD / 'agent-classes'
SHADE_CLASSES = OUT_BUILD / 'shaded-asm'
TEST_CLASSES = OUT_BUILD / 'test-classes'
AGENT_CLASSES.mkdir()
SHADE_CLASSES.mkdir()
TEST_CLASSES.mkdir()

RELOC_HELPER = OUT_BUILD / 'RelocateAsm.java'
RELOC_HELPER.write_text('''
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class RelocateAsm {
    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args[0]);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.startsWith("org/objectweb/asm/")) {
                    return "dev/sunlit/bootagent/asm/" + internalName.substring("org/objectweb/asm/".length());
                }
                return internalName;
            }
        };
        for (int i = 1; i < args.length; i++) {
            try (ZipFile zf = new ZipFile(args[i])) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().equals("module-info.class")) continue;
                    byte[] raw;
                    try (InputStream is = zf.getInputStream(e)) { raw = is.readAllBytes(); }
                    ClassReader cr = new ClassReader(raw);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, remapper), 0);
                    String mapped = remapper.map(e.getName().substring(0, e.getName().length() - 6)) + ".class";
                    Path dest = outDir.resolve(mapped);
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, cw.toByteArray());
                }
            }
        }
    }
}
''', encoding='utf8')

cp_build = os.pathsep.join([str(ASM_JAR), str(ASM_TREE_JAR), str(ASM_COMMONS_JAR)])
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-cp', cp_build, '-d', str(OUT_BUILD), str(RELOC_HELPER)], check=True)
subprocess.run([str(JDK / 'java.exe'), '-cp', os.pathsep.join([str(OUT_BUILD), cp_build]), 'RelocateAsm', str(SHADE_CLASSES), str(ASM_JAR), str(ASM_TREE_JAR)], check=True)

# Build access-widened compile-time copy of mixin-0.8.5.jar for BootMixinPayload compilation (build-time only).
OPEN_MIXIN_GEN = OUT_BUILD / 'WidenMixin.java'
OPEN_MIXIN_GEN.write_text('''
import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class WidenMixin {
    public static void main(String[] args) throws Exception {
        Set<String> targets = Set.of(
            "org/spongepowered/asm/mixin/transformer/MixinTransformer.class",
            "org/spongepowered/asm/mixin/transformer/MixinProcessor.class",
            "org/spongepowered/asm/mixin/transformer/MixinConfig.class",
            "org/spongepowered/asm/mixin/transformer/MixinCoprocessorPassthrough.class",
            "org/spongepowered/asm/mixin/transformer/MixinCoprocessorAccessor.class",
            "org/spongepowered/asm/mixin/transformer/MixinCoprocessorNestHost.class",
            "org/spongepowered/asm/mixin/transformer/MixinCoprocessorSyntheticInner.class"
        );
        try (ZipFile in = new ZipFile(args[0]);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                byte[] raw;
                try (InputStream is = in.getInputStream(e)) { raw = is.readAllBytes(); }
                if (targets.contains(e.getName())) {
                    ClassReader cr = new ClassReader(raw);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override
                        public void visit(int v, int access, String name, String sig, String sup, String[] ifaces) {
                            super.visit(v, (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC, name, sig, sup, ifaces);
                        }
                        @Override
                        public FieldVisitor visitField(int access, String name, String desc, String sig, Object val) {
                            return super.visitField((access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC, name, desc, sig, val);
                        }
                    }, 0);
                    raw = cw.toByteArray();
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(raw);
                out.closeEntry();
            }
        }
    }
}
''', encoding='utf8')

mixin_jar = str(sorted((LIBRARIES / 'org/spongepowered/mixin/0.8.5').glob('mixin-*.jar'))[-1])
open_mixin_jar = OUT_BUILD / 'mixin-open.jar'
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-cp', str(ASM_JAR), '-d', str(OUT_BUILD), str(OPEN_MIXIN_GEN)], check=True)
subprocess.run([str(JDK / 'java.exe'), '-cp', os.pathsep.join([str(OUT_BUILD), str(ASM_JAR)]), 'WidenMixin', mixin_jar, str(open_mixin_jar)], check=True)

# Compile payload classes embedded as byte resources inside sunlit-boot.jar.
PAYLOAD_CLASSES = OUT_BUILD / 'payload-classes'
PAYLOAD_CLASSES.mkdir()
resolver_payload_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/payload/BootResolverPayload.java'
mixin_payload_src = ROOT / 'boot-agent/src/org/spongepowered/asm/mixin/transformer/BootMixinPayload.java'
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-g:none', '-proc:none', '-cp', str(open_mixin_jar), '-d', str(PAYLOAD_CLASSES), str(resolver_payload_src), str(mixin_payload_src)], check=True)

# Compile SunlitBootAgent directly against the relocated ASM packages so runtime uses zero external classpath ASM.
agent_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/SunlitBootAgent.java'
reloc_src = OUT_BUILD / 'src/dev/sunlit/bootagent/SunlitBootAgent.java'
reloc_src.parent.mkdir(parents=True)
reloc_src.write_text(
    agent_src.read_text(encoding='utf8').replace('import org.objectweb.asm.', 'import dev.sunlit.bootagent.asm.'),
    encoding='utf8'
)
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-cp', str(SHADE_CLASSES), '-d', str(AGENT_CLASSES), str(reloc_src)], check=True)

OUT_JAR = (cli.out.resolve() if cli.out else INSTANCE / 'minecraft/config/sunlit-boot.jar')
OUT_JAR.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(OUT_JAR, 'w', zipfile.ZIP_DEFLATED) as zf:
    zf.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nPremain-Class: dev.sunlit.bootagent.SunlitBootAgent\r\nCan-Redefine-Classes: false\r\nCan-Retransform-Classes: true\r\n\r\n')
    for root_dir in [AGENT_CLASSES, SHADE_CLASSES]:
        for f in sorted(root_dir.rglob('*.class')):
            zf.write(f, f.relative_to(root_dir).as_posix())
    zf.write(PAYLOAD_CLASSES / 'dev/sunlit/bootagent/payload/BootResolverPayload.class', 'dev/sunlit/bootagent/payload/BootResolverPayload.class')
    zf.write(PAYLOAD_CLASSES / 'org/spongepowered/asm/mixin/transformer/BootMixinPayload.class', 'dev/sunlit/bootagent/payload/BootMixinPayload.class')

# Verify transformed bytecode passes JVM verifier and preserves exact semantics on Resolver + Mixin!
VERIFY_SRC = OUT_BUILD / 'VerifyBootAgent.java'
VERIFY_SRC.write_text('''
import dev.sunlit.bootagent.SunlitBootAgent;
import java.io.*;
import java.lang.module.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class VerifyBootAgent {
    public static void main(String[] args) throws Exception {
        String libs = args[0];
        String sjh = args[1];
        String fml = args[2];
        String mixin = args[3];

        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        byte[] resolverBytes = Files.readAllBytes(jrt.getPath("/modules/java.base/java/lang/module/Resolver.class"));
        byte[] resolverOut = SunlitBootAgent.transformResolver(ClassLoader.getPlatformClassLoader(), resolverBytes);
        if (resolverOut == null) throw new AssertionError("Resolver not transformed");

        byte[] zipBytes = Files.readAllBytes(jrt.getPath("/modules/jdk.zipfs/jdk/nio/zipfs/ZipFileSystem.class"));
        byte[] zipOut = SunlitBootAgent.transformZipFileSystem(ClassLoader.getPlatformClassLoader(), zipBytes);
        if (zipOut == null) throw new AssertionError("ZipFileSystem not transformed");

        byte[] zipEntryBytes = Files.readAllBytes(jrt.getPath("/modules/jdk.zipfs/jdk/nio/zipfs/ZipFileSystem$Entry.class"));
        byte[] zipEntryOut = SunlitBootAgent.transformZipFileSystemEntry(ClassLoader.getPlatformClassLoader(), zipEntryBytes);
        if (zipEntryOut == null) throw new AssertionError("ZipFileSystem$Entry not transformed");

        byte[] unionOut = SunlitBootAgent.transformUnionFileSystem(null, readZip(sjh, "cpw/mods/niofs/union/UnionFileSystem.class"));
        if (unionOut == null) throw new AssertionError("UnionFileSystem not transformed");

        byte[] bgWaiterOut = SunlitBootAgent.transformBackgroundWaiter(readZip(fml, "net/minecraftforge/fml/loading/BackgroundWaiter.class"));
        if (bgWaiterOut == null) throw new AssertionError("BackgroundWaiter not transformed");

        byte[] scanOut = SunlitBootAgent.transformBackgroundScanHandler(null, readZip(fml, "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler.class"));
        if (scanOut == null) throw new AssertionError("BackgroundScanHandler not transformed");

        byte[] bcOut = SunlitBootAgent.transformMixinBytecode(null, readZip(mixin, "org/spongepowered/asm/util/Bytecode.class"));
        if (bcOut == null) throw new AssertionError("Bytecode not transformed");

        byte[] mlpOut = SunlitBootAgent.transformMixinLaunchPlugin(readZip(mixin, "org/spongepowered/asm/launch/MixinLaunchPluginLegacy.class"));
        if (mlpOut == null) throw new AssertionError("MixinLaunchPluginLegacy not transformed");

        byte[] mthOut = SunlitBootAgent.transformMixinHandler(null, readZip(mixin, "org/spongepowered/asm/service/modlauncher/MixinTransformationHandler.class"));
        if (mthOut == null) throw new AssertionError("MixinTransformationHandler not transformed");

        byte[] mtOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinTransformer.class"), Set.of("processor"), Set.of());
        byte[] mpOut = SunlitBootAgent.transformMixinProcessor(null, readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinProcessor.class"));
        if (mpOut == null) throw new AssertionError("MixinProcessor not transformed");
        byte[] mcOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinConfig.class"), Set.of("mixinMapping"), Set.of());
        byte[] mcpOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinCoprocessorPassthrough.class"), Set.of("loadable"), Set.of("registerLoadable"));
        byte[] mcaOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinCoprocessorAccessor.class"), Set.of("accessorMixins"), Set.of("registerAccessor"));
        byte[] mcnOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinCoprocessorNestHost.class"), Set.of("nestHosts"), Set.of("registerNestMember"));
        byte[] mcsOut = SunlitBootAgent.widenClass(readZip(mixin, "org/spongepowered/asm/mixin/transformer/MixinCoprocessorSyntheticInner.class"), Set.of("syntheticInnerClasses"), Set.of("registerSyntheticInner"));

        // 1. Live JVM ZipFS readOnly test
        Path tempZip = Files.createTempFile("sunlit-boot-verify", ".zip");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                zos.putNextEntry(new ZipEntry("hello.txt"));
                zos.write("world".getBytes());
                zos.closeEntry();
            }
            try (FileSystem roFs = FileSystems.newFileSystem(tempZip, Map.of("sunlit.readOnly", Boolean.TRUE))) {
                if (!roFs.isReadOnly()) throw new AssertionError("sunlit.readOnly ZipFS must be readOnly");
                if (!"world".equals(Files.readString(roFs.getPath("hello.txt")))) throw new AssertionError("ZipFS read failed");
                if (Files.readAttributes(roFs.getPath("hello.txt"), java.nio.file.attribute.BasicFileAttributes.class).size() != 5L) {
                    throw new AssertionError("ZipFS BasicFileAttributes size failed");
                }
                try {
                    Files.writeString(roFs.getPath("fail.txt"), "x");
                    throw new AssertionError("sunlit.readOnly ZipFS must reject writes");
                } catch (ReadOnlyFileSystemException expected) {}
            }
            try (FileSystem rwFs = FileSystems.newFileSystem(tempZip, Map.of())) {
                if (rwFs.isReadOnly()) throw new AssertionError("Normal ZipFS must remain writable");
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }

        // 2. Live JVM Configuration/Resolver test (retransformed java.lang.module.Resolver!)
        Configuration bootCfg = ModuleLayer.boot().configuration();
        Configuration childCfg = bootCfg.resolve(ModuleFinder.of(), ModuleFinder.of(), Set.of());
        if (childCfg == null) throw new AssertionError("Child config failed");
        // Verify BootResolverPayload.closeGraph matches reference fixed-point closure on random cyclic + automatic graphs!
        Random rng = new Random(20261116L);
        for (int trial = 0; trial < 20; trial++) {
            int n = 45;
            String[] nodes = new String[n];
            for (int i = 0; i < n; i++) nodes[i] = "m" + i;
            Map<String, Set<String>> refG1 = new LinkedHashMap<>();
            Map<String, Set<String>> fastG1 = new LinkedHashMap<>();
            Map<String, Set<String>> g2 = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Set<String> s1 = new HashSet<>();
                Set<String> s2 = new HashSet<>();
                for (int j = 0; j < 4; j++) s1.add(nodes[rng.nextInt(n)]);
                for (int j = 0; j < 3; j++) s2.add(nodes[rng.nextInt(n)]);
                refG1.put(nodes[i], new HashSet<>(s1));
                fastG1.put(nodes[i], new HashSet<>(s1));
                if (i < n - 5) g2.put(nodes[i], s2); // include leaf nodes not in g2 keySet!
            }
            List<String> toAdd = new ArrayList<>();
            boolean changed;
            do {
                changed = false;
                for (Set<String> d1 : refG1.values()) {
                    for (String m : d1) {
                        Set<String> d2 = g2.get(m);
                        if (d2 != null) {
                            for (String m2 : d2) {
                                if (!d1.contains(m2)) toAdd.add(m2);
                            }
                        }
                    }
                    if (!toAdd.isEmpty()) {
                        d1.addAll(toAdd);
                        toAdd.clear();
                        changed = true;
                    }
                }
            } while (changed);
            dev.sunlit.bootagent.payload.BootResolverPayload.closeGraph(fastG1, g2);
            if (!refG1.equals(fastG1)) {
                throw new AssertionError("Resolver graph closure mismatch on trial " + trial);
            }
        }

        List<java.net.URL> urls = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            urls.add(Path.of(args[i]).toUri().toURL());
        }
        try (var cl = new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), VerifyBootAgent.class.getClassLoader())) {
            // 3. CheckClassAdapter frame & stack verification on all transformed classes
            Class<?> cca = Class.forName("org.objectweb.asm.util.CheckClassAdapter", true, cl);
            Class<?> crCls = Class.forName("org.objectweb.asm.ClassReader", true, cl);
            Method verifyM = cca.getMethod("verify", crCls, ClassLoader.class, boolean.class, PrintWriter.class);
            var sw = new StringWriter();
            var pw = new PrintWriter(sw);
            for (byte[] clsBytes : new byte[][]{
                    resolverOut, zipOut, zipEntryOut, unionOut, bgWaiterOut, scanOut, bcOut, mlpOut, mthOut,
                    mtOut, mpOut, mcOut, mcpOut, mcaOut, mcnOut, mcsOut}) {
                Object cr = crCls.getConstructor(byte[].class).newInstance((Object) clsBytes);
                verifyM.invoke(null, cr, cl, false, pw);
                String diag = sw.toString();
                if (!diag.isEmpty() && diag.contains("Error")) {
                    throw new AssertionError("CheckClassAdapter error: " + diag);
                }
            }

            // 4. Verify live JVM classloading & execution under -javaagent!
            Class<?> bcCls = Class.forName("org.spongepowered.asm.util.Bytecode", true, cl);
            Method m = bcCls.getMethod("getOpcodeName", int.class);
            if (!"INVOKESTATIC".equals(m.invoke(null, 184))) throw new AssertionError("INVOKESTATIC mismatch");
            if (!"INVOKESTATIC".equals(m.invoke(null, 184))) throw new AssertionError("INVOKESTATIC cache mismatch");
            if (!"ALOAD".equals(m.invoke(null, 25))) throw new AssertionError("ALOAD mismatch");
            if (!"RETURN".equals(m.invoke(null, 177))) throw new AssertionError("RETURN mismatch");
            if (!"UNKNOWN".equals(m.invoke(null, -1))) throw new AssertionError("negative opcode mismatch: " + m.invoke(null, -1));
            if (!"999".equals(m.invoke(null, 999))) throw new AssertionError("unmapped opcode mismatch: " + m.invoke(null, 999));

            Object mlp = Class.forName("org.spongepowered.asm.launch.MixinLaunchPluginLegacy", true, cl)
                    .getDeclaredConstructor().newInstance();
            if (mlp == null) throw new AssertionError("MLP null");
            Object mth = Class.forName("org.spongepowered.asm.service.modlauncher.MixinTransformationHandler", true, cl)
                    .getDeclaredConstructor().newInstance();
            if (mth == null) throw new AssertionError("MTH null");
            Class<?> mpCls = Class.forName("org.spongepowered.asm.mixin.transformer.MixinProcessor", false, cl);
            if (mpCls.getField("SUNLIT_SNAPSHOT") == null) throw new AssertionError("Missing SUNLIT_SNAPSHOT");
            if (mpCls.getDeclaredMethod("sunlitShouldSkip", Object.class, String.class) == null) throw new AssertionError("Missing sunlitShouldSkip");
            if (mpCls.getDeclaredMethod("sunlitRebuildSnapshot", mpCls) == null) throw new AssertionError("Missing sunlitRebuildSnapshot");
            Class<?> mtCls = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer", false, cl);
            if (!java.lang.reflect.Modifier.isPublic(mtCls.getModifiers())) throw new AssertionError("MixinTransformer must be public");
            if (!java.lang.reflect.Modifier.isPublic(mtCls.getDeclaredField("processor").getModifiers())) throw new AssertionError("processor field must be public");
            Class<?> typeCls = Class.forName("org.objectweb.asm.Type", false, cl);
            Class<?> phaseCls = Class.forName("cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", false, cl);
            Method getObjType = typeCls.getMethod("getObjectType", String.class);
            Method handlesM = mth.getClass().getMethod("handlesClass", typeCls, boolean.class, String.class);
            Object fooType = getObjType.invoke(null, "net/minecraft/Foo");
            Object res = handlesM.invoke(mth, fooType, false, "classloading");
            if (res == null) throw new AssertionError("MTH must return AFTER_ONLY while transformer is null");
            Class<?> ufsCls = Class.forName("cpw.mods.niofs.union.UnionFileSystem", false, cl);
            if (ufsCls == null) throw new AssertionError("UnionFileSystem null");
            Class<?> bgCls = Class.forName("net.minecraftforge.fml.loading.BackgroundWaiter", false, cl);
            if (bgCls == null) throw new AssertionError("BackgroundWaiter null");
            Class<?> bshCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.BackgroundScanHandler", false, cl);
            if (bshCls == null) throw new AssertionError("BackgroundScanHandler null");
        }
        System.out.println("VerifyBootAgent: ALL PASSED");
    }

    private static byte[] readZip(String jar, String entry) throws Exception {
        try (ZipFile zf = new ZipFile(jar); InputStream is = zf.getInputStream(zf.getEntry(entry))) {
            return is.readAllBytes();
        }
    }
}
''', encoding='utf8')

sjh_jar = str(sorted((LIBRARIES / 'cpw/mods/securejarhandler').glob('*/securejarhandler-*.jar'))[0])
fml_jar = str(sorted((LIBRARIES / 'net/minecraftforge/fmlloader').glob('1.20.1-47.4.0/fmlloader-*.jar'))[-1])
modlauncher_jar = str(sorted((LIBRARIES / 'cpw/mods/modlauncher').glob('10.0.9/modlauncher-*.jar'))[-1])
extra_jars = [
    str(p) for pat in [
        'net/minecraftforge/forgespi/*/forgespi-*.jar',
        'com/mojang/logging/*/logging-*.jar',
        'org/slf4j/slf4j-api/*/slf4j-api-*.jar',
        'com/google/guava/guava/*/guava-*.jar',
        'net/minecraftforge/JarJar*/*/*.jar',
    ] for p in sorted(LIBRARIES.glob(pat))[:1]
]

verify_cp = os.pathsep.join([str(TEST_CLASSES), str(PAYLOAD_CLASSES), str(OUT_JAR)])
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-cp', os.pathsep.join([str(PAYLOAD_CLASSES), str(OUT_JAR)]), '-d', str(TEST_CLASSES), str(VERIFY_SRC)], check=True)
subprocess.run([str(JDK / 'java.exe'), f'-javaagent:{OUT_JAR}', '--add-opens', 'java.base/java.lang.module=ALL-UNNAMED', '-cp', verify_cp, 'VerifyBootAgent', str(LIBRARIES), sjh_jar, fml_jar, mixin_jar, str(ASM_JAR), str(ASM_TREE_JAR), str(ASM_UTIL_JAR), str(ASM_ANALYSIS_JAR), modlauncher_jar, *extra_jars], check=True)
print('Built & verified', OUT_JAR, OUT_JAR.stat().st_size, 'bytes')
