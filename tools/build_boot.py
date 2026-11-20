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
jar_payload_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/payload/BootJarPayload.java'
download_payload_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/payload/BootDownloadPayload.java'
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-g:none', '-proc:none', '-cp', str(open_mixin_jar), '-d', str(PAYLOAD_CLASSES), str(resolver_payload_src), str(mixin_payload_src), str(jar_payload_src), str(download_payload_src)], check=True)

# Compile SunlitBootAgent, SunlitBootTransformer & SunlitExtraTransformer against the relocated ASM packages so runtime uses zero external classpath ASM.
agent_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/SunlitBootAgent.java'
transformer_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/SunlitBootTransformer.java'
extra_src = ROOT / 'boot-agent/src/dev/sunlit/bootagent/SunlitExtraTransformer.java'
reloc_agent = OUT_BUILD / 'src/dev/sunlit/bootagent/SunlitBootAgent.java'
reloc_transformer = OUT_BUILD / 'src/dev/sunlit/bootagent/SunlitBootTransformer.java'
reloc_extra = OUT_BUILD / 'src/dev/sunlit/bootagent/SunlitExtraTransformer.java'
reloc_agent.parent.mkdir(parents=True)
reloc_agent.write_text(
    agent_src.read_text(encoding='utf8').replace('import org.objectweb.asm.', 'import dev.sunlit.bootagent.asm.'),
    encoding='utf8'
)
reloc_transformer.write_text(
    transformer_src.read_text(encoding='utf8').replace('import org.objectweb.asm.', 'import dev.sunlit.bootagent.asm.'),
    encoding='utf8'
)
reloc_extra.write_text(
    extra_src.read_text(encoding='utf8').replace('import org.objectweb.asm.', 'import dev.sunlit.bootagent.asm.'),
    encoding='utf8'
)
subprocess.run([str(JDK / 'javac.exe'), '--release', '17', '-cp', os.pathsep.join([str(SHADE_CLASSES), str(PAYLOAD_CLASSES)]), '-d', str(AGENT_CLASSES), str(reloc_agent), str(reloc_transformer), str(reloc_extra)], check=True)

OUT_JAR = (cli.out.resolve() if cli.out else INSTANCE / 'minecraft/config/sunlit-boot.jar')
OUT_JAR.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(OUT_JAR, 'w', zipfile.ZIP_DEFLATED) as zf:
    zf.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nPremain-Class: dev.sunlit.bootagent.SunlitBootAgent\r\nCan-Redefine-Classes: false\r\nCan-Retransform-Classes: true\r\n\r\n')
    for notice in ('LICENSE', 'THIRD_PARTY_NOTICES.txt'):
        zf.write(ROOT/notice, 'META-INF/' + notice)
    for root_dir in [AGENT_CLASSES, SHADE_CLASSES]:
        for f in sorted(root_dir.rglob('*.class')):
            zf.write(f, f.relative_to(root_dir).as_posix())
    zf.write(PAYLOAD_CLASSES / 'dev/sunlit/bootagent/payload/BootResolverPayload.class', 'dev/sunlit/bootagent/payload/BootResolverPayload.class')
    zf.write(PAYLOAD_CLASSES / 'dev/sunlit/bootagent/payload/BootDownloadPayload.class', 'dev/sunlit/bootagent/payload/BootDownloadPayload.class')
    zf.write(PAYLOAD_CLASSES / 'org/spongepowered/asm/mixin/transformer/BootMixinPayload.class', 'dev/sunlit/bootagent/payload/BootMixinPayload.class')
    for pf in sorted((PAYLOAD_CLASSES / 'dev/sunlit/bootagent/payload').glob('BootJarPayload*.class')):
        zf.write(pf, pf.relative_to(PAYLOAD_CLASSES).as_posix())

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
        // The real JVM already invoked premain. ServerStarterJar invokes it a second time with
        // a different instrumentation instance, which must not be consulted or registered.
        var secondInstrumentation = (java.lang.instrument.Instrumentation) java.lang.reflect.Proxy.newProxyInstance(
            VerifyBootAgent.class.getClassLoader(), new Class<?>[]{java.lang.instrument.Instrumentation.class},
            (proxy, method, arguments) -> { throw new AssertionError("Duplicate premain used instrumentation: " + method); });
        SunlitBootAgent.premain(null, secondInstrumentation);
        String libs = args[0];
        String sjh = args[1];
        String fml = args[2];
        String mixin = args[3];

        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        byte[] resolverBytes = Files.readAllBytes(jrt.getPath("/modules/java.base/java/lang/module/Resolver.class"));
        byte[] resolverOut = SunlitBootAgent.transformResolver(ClassLoader.getPlatformClassLoader(), resolverBytes);
        if (resolverOut == null) throw new AssertionError("Resolver not transformed");

        byte[] cfgBytes = Files.readAllBytes(jrt.getPath("/modules/java.base/java/lang/module/Configuration.class"));
        byte[] cfgOut = dev.sunlit.bootagent.SunlitExtraTransformer.transformConfiguration(ClassLoader.getPlatformClassLoader(), cfgBytes);
        if (cfgOut == null) throw new AssertionError("Configuration not transformed");

        byte[] zipPathBytes = Files.readAllBytes(jrt.getPath("/modules/jdk.zipfs/jdk/nio/zipfs/ZipPath.class"));
        byte[] zipPathOut = dev.sunlit.bootagent.SunlitExtraTransformer.transformZipPath(ClassLoader.getPlatformClassLoader(), zipPathBytes);
        if (zipPathOut == null) throw new AssertionError("ZipPath not transformed");

        byte[] zipBytes = Files.readAllBytes(jrt.getPath("/modules/jdk.zipfs/jdk/nio/zipfs/ZipFileSystem.class"));
        byte[] zipOut = SunlitBootAgent.transformZipFileSystem(ClassLoader.getPlatformClassLoader(), zipBytes);
        if (zipOut == null) throw new AssertionError("ZipFileSystem not transformed");

        byte[] zipEntryBytes = Files.readAllBytes(jrt.getPath("/modules/jdk.zipfs/jdk/nio/zipfs/ZipFileSystem$Entry.class"));
        byte[] zipEntryOut = SunlitBootAgent.transformZipFileSystemEntry(ClassLoader.getPlatformClassLoader(), zipEntryBytes);
        if (zipEntryOut == null) throw new AssertionError("ZipFileSystem$Entry not transformed");

        byte[] unionOut = SunlitBootAgent.transformUnionFileSystem(null, readZip(sjh, "cpw/mods/niofs/union/UnionFileSystem.class"));
        if (unionOut == null) throw new AssertionError("UnionFileSystem not transformed");
        byte[] ufspOut = SunlitBootAgent.transformUnionFileSystemProvider(readZip(sjh, "cpw/mods/niofs/union/UnionFileSystemProvider.class"));
        if (ufspOut == null) throw new AssertionError("UnionFileSystemProvider not transformed");
        byte[] jarOut = SunlitBootAgent.transformJar(null, readZip(sjh, "cpw/mods/jarhandling/impl/Jar.class"));
        if (jarOut == null) throw new AssertionError("Jar not transformed");
        byte[] mddOut = SunlitBootAgent.transformModDirDiscoverer(null, readZip(fml, "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer.class"));
        if (mddOut == null) throw new AssertionError("ModDirTransformerDiscoverer not transformed");
        byte[] ajlOut = SunlitBootAgent.transformAbstractJarLocator(readZip(fml, "net/minecraftforge/fml/loading/moddiscovery/AbstractJarFileModLocator.class"));
        if (ajlOut == null) throw new AssertionError("AbstractJarFileModLocator not transformed");
        byte[] ampOut = SunlitBootAgent.transformAbstractModProvider(readZip(fml, "net/minecraftforge/fml/loading/moddiscovery/AbstractModProvider.class"));
        if (ampOut == null) throw new AssertionError("AbstractModProvider not transformed");

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
                zos.putNextEntry(new ZipEntry("caf\\u00e9.txt"));
                zos.write("bonjour".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            try (FileSystem roFs = FileSystems.newFileSystem(tempZip, Map.of("sunlit.readOnly", Boolean.TRUE))) {
                if (!roFs.isReadOnly()) throw new AssertionError("sunlit.readOnly ZipFS must be readOnly");
                if (!"world".equals(Files.readString(roFs.getPath("hello.txt")))) throw new AssertionError("ZipFS read failed");
                if (Files.readAttributes(roFs.getPath("hello.txt"), java.nio.file.attribute.BasicFileAttributes.class).size() != 5L) {
                    throw new AssertionError("ZipFS BasicFileAttributes size failed");
                }
                @SuppressWarnings("unchecked")
                java.util.function.Predicate<String> exists = (java.util.function.Predicate<String>) roFs;
                if (!exists.test("hello.txt") || !exists.test("./hello.txt")
                        || !exists.test("/caf\\u00e9.txt") || exists.test("/absent-\\u00e9.txt")) {
                    throw new AssertionError("ZipFS predicate fallback changed path lookup");
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
                    resolverOut, cfgOut, zipPathOut, zipOut, zipEntryOut, unionOut, ufspOut, jarOut, mddOut, ajlOut, ampOut,
                    bgWaiterOut, scanOut, bcOut, mlpOut, mthOut,
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
            ufsCls.getMethod("sunlitNewInputStream", Class.forName("cpw.mods.niofs.union.UnionPath", false, cl));
            ufsCls.getMethod("sunlitOpenOptional", Path.class);
            ufsCls.getMethod("sunlitExists", Class.forName("cpw.mods.niofs.union.UnionPath", false, cl));
            Class<?> ufspCls = Class.forName("cpw.mods.niofs.union.UnionFileSystemProvider", false, cl);
            ufspCls.getDeclaredMethod("newInputStream", Path.class, java.nio.file.OpenOption[].class);
            if (!java.util.function.Predicate.class.isAssignableFrom(ufspCls)) {
                throw new AssertionError("UnionFileSystemProvider must implement Predicate");
            }
            java.nio.file.spi.FileSystemProvider ufspInst = (java.nio.file.spi.FileSystemProvider) ufspCls.getConstructor().newInstance();
            Path tmpDir = Files.createTempDirectory("sunlit-ufs-test");
            try {
                Path hitFile = tmpDir.resolve("hello.txt");
                Files.writeString(hitFile, "ok");
                Method newFsM = ufspCls.getMethod("newFileSystem", java.util.function.BiPredicate.class, Path[].class);
                try (java.nio.file.FileSystem ufs = (java.nio.file.FileSystem) newFsM.invoke(ufspInst, null, new Path[]{tmpDir})) {
                    Path uHit = ufs.getPath("hello.txt");
                    Path uMiss = ufs.getPath("missing/nope.png");
                    if (!Files.exists(uHit) || Files.notExists(uHit)) {
                        throw new AssertionError("UnionFS Files.exists/notExists hit failed");
                    }
                    if (Files.exists(uMiss) || !Files.notExists(uMiss)) {
                        throw new AssertionError("UnionFS Files.exists/notExists miss failed");
                    }
                    if (!Files.exists(uHit, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.exists(uMiss, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new AssertionError("UnionFS Files.exists NOFOLLOW_LINKS fallback failed");
                    }
                }
                Path testJar = tmpDir.resolve("embedded.jar");
                byte[] storedBytes = "stored-ok-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(testJar))) {
                    zos.putNextEntry(new java.util.zip.ZipEntry("assets/sunlit/hello.txt"));
                    zos.write("zip-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                    zos.putNextEntry(new java.util.zip.ZipEntry("assets/sunlit/caf\\u00e9.txt"));
                    zos.write("bonjour".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                    java.util.zip.ZipEntry zeStored = new java.util.zip.ZipEntry("assets/sunlit/stored.bin");
                    zeStored.setMethod(java.util.zip.ZipEntry.STORED);
                    zeStored.setSize(storedBytes.length);
                    zeStored.setCompressedSize(storedBytes.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(storedBytes);
                    zeStored.setCrc(crc.getValue());
                    zos.putNextEntry(zeStored);
                    zos.write(storedBytes);
                    zos.closeEntry();
                }
                try (java.nio.file.FileSystem jarUfs = (java.nio.file.FileSystem) newFsM.invoke(ufspInst, null, new Path[]{testJar})) {
                    Path jHit = jarUfs.getPath("assets", "sunlit", "hello.txt");
                    Path jStored = jarUfs.getPath("assets", "sunlit", "stored.bin");
                    Path jMiss = jarUfs.getPath("assets", "sunlit", "missing.png");
                    if (!Files.exists(jHit) || Files.notExists(jHit) || !Files.exists(jStored)) {
                        throw new AssertionError("Embedded Jar UnionFS hit failed");
                    }
                    if (Files.exists(jMiss) || !Files.notExists(jMiss)) {
                        throw new AssertionError("Embedded Jar UnionFS miss failed");
                    }
                    Path unicodeHit = jarUfs.getPath("assets", "sunlit", "caf\\u00e9.txt");
                    Path unicodeMiss = jarUfs.getPath("assets", "sunlit", "absent-\\u00e9.txt");
                    if (!Files.exists(unicodeHit) || !Files.readString(unicodeHit).equals("bonjour")
                            || Files.exists(unicodeMiss) || !Files.notExists(unicodeMiss)) {
                        throw new AssertionError("Embedded Jar UnionFS Unicode lookup failed");
                    }
                    byte[] exact = dev.sunlit.bootagent.payload.BootJarPayload.readAllBytesExact(jHit);
                    if (!"zip-ok".equals(new String(exact, java.nio.charset.StandardCharsets.UTF_8))) {
                        throw new AssertionError("readAllBytesExact mismatch");
                    }
                    int[] scratchLen = new int[1];
                    byte[] scratch = dev.sunlit.bootagent.payload.BootJarPayload.readScanClassBytes(jStored, scratchLen);
                    if (scratchLen[0] != storedBytes.length || !Arrays.equals(Arrays.copyOf(scratch, scratchLen[0]), storedBytes)) {
                        throw new AssertionError("readScanClassBytes stored mismatch");
                    }
                    byte[] scratchDefl = dev.sunlit.bootagent.payload.BootJarPayload.readScanClassBytes(jHit, scratchLen);
                    if (scratchLen[0] != 6 || !"zip-ok".equals(new String(scratchDefl, 0, scratchLen[0], java.nio.charset.StandardCharsets.UTF_8))) {
                        throw new AssertionError("readScanClassBytes deflated mismatch");
                    }
                    if (jHit.normalize() != jHit) {
                        throw new AssertionError("UnionPath.normalize should return self on clean segments");
                    }
                    Object optObj = ufsCls.getMethod("sunlitOpenOptional", Path.class).invoke(jarUfs, jHit);
                    @SuppressWarnings("unchecked")
                    Optional<InputStream> optIn = (Optional<InputStream>) optObj;
                    try (InputStream is = optIn.orElseThrow()) {
                        byte[] read = is.readAllBytes();
                        if (!"zip-ok".equals(new String(read, java.nio.charset.StandardCharsets.UTF_8))) {
                            throw new AssertionError("sunlitOpenOptional ExactByteArrayInputStream mismatch");
                        }
                    }
                } finally {
                    Files.deleteIfExists(testJar);
                }
                if (dev.sunlit.bootagent.payload.BootJarPayload.fastCompilePattern("([^,]*(?=/))")
                        != dev.sunlit.bootagent.payload.BootJarPayload.fastCompilePattern("([^,]*(?=/))")) {
                    throw new AssertionError("fastCompilePattern cache mismatch");
                }
            } finally {
                Files.deleteIfExists(tmpDir.resolve("hello.txt"));
                Files.deleteIfExists(tmpDir);
            }
            Class<?> scanCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.Scanner", false, cl);
            if (scanCls == null) throw new AssertionError("Scanner null");
            Class<?> jarCls = Class.forName("cpw.mods.jarhandling.impl.Jar", false, cl);
            if (jarCls.getMethod("verifyPath", Path.class) == null) throw new AssertionError("Jar.verifyPath null");
            if (jarCls.getMethod("sunlitOpen", String.class) == null) throw new AssertionError("Jar.sunlitOpen null");
            Class<?> jmdpCls = Class.forName("cpw.mods.jarhandling.impl.Jar$JarModuleDataProvider", false, cl);
            if (jmdpCls.getMethod("open", String.class) == null) throw new AssertionError("JarModuleDataProvider.open null");
            Class<?> bgCls = Class.forName("net.minecraftforge.fml.loading.BackgroundWaiter", false, cl);
            if (bgCls == null) throw new AssertionError("BackgroundWaiter null");
            Class<?> bshCls = Class.forName("net.minecraftforge.fml.loading.moddiscovery.BackgroundScanHandler", false, cl);
            if (bshCls == null) throw new AssertionError("BackgroundScanHandler null");

            // 5. Verify SunlitFlatStateMap equivalence via ImmutableMap.sunlitBuildStateMap on live Guava JAR
            Class<?> imCls = Class.forName("com.google.common.collect.ImmutableMap", true, cl);
            Method buildStateMapM = imCls.getMethod("sunlitBuildStateMap", Object[].class, Object[].class, int[].class);
            Object[] ks = new Object[]{"k1", "k2", "k3"};
            Object[] vs = new Object[]{"v1", "v2", "v3"};
            @SuppressWarnings("unchecked")
            Map<Object, Object> flat1 = (Map<Object, Object>) buildStateMapM.invoke(null, ks, vs, null);
            vs[0] = "MUTATED";
            if (!"v1".equals(flat1.get("k1")) || !"v2".equals(flat1.get("k2")) || !"v3".equals(flat1.get("k3"))) {
                throw new AssertionError("SunlitFlatStateMap failed vals defensive copy or get()");
            }
            if (flat1.get(null) != null || flat1.get("missing") != null || flat1.size() != 3 || !flat1.containsKey("k2") || flat1.containsKey("x") || !flat1.containsValue("v3") || flat1.containsValue("MUTATED")) {
                throw new AssertionError("SunlitFlatStateMap lookup mismatch");
            }
            Map<Object, Object> refMap = new LinkedHashMap<>();
            refMap.put("k1", "v1");
            refMap.put("k2", "v2");
            refMap.put("k3", "v3");
            if (!flat1.equals(refMap) || !refMap.equals(flat1) || flat1.hashCode() != refMap.hashCode()) {
                throw new AssertionError("SunlitFlatStateMap Map equals/hashCode mismatch");
            }
            if (!flat1.keySet().equals(refMap.keySet()) || !flat1.entrySet().equals(refMap.entrySet())) {
                throw new AssertionError("SunlitFlatStateMap keySet/entrySet mismatch");
            }
            Object[] vs2 = new Object[]{"v1", "v2", "v3"};
            @SuppressWarnings("unchecked")
            Map<Object, Object> flat2 = (Map<Object, Object>) buildStateMapM.invoke(null, ks, vs2, null);
            if (!flat1.equals(flat2) || !flat1.keySet().equals(flat2.keySet())) {
                throw new AssertionError("SunlitFlatStateMap equality or cached keySet mismatch");
            }
        }
        // Verify DownloadUtils transforms cleanly inside PlatformClassLoader-parented URLClassLoader
        Path forgeInst = Path.of(args[0], "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar");
        if (Files.isRegularFile(forgeInst)) {
            try (java.net.URLClassLoader instCl = new java.net.URLClassLoader(
                    new java.net.URL[]{forgeInst.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> duCls = Class.forName("net.minecraftforge.installer.DownloadUtils", true, instCl);
                Method getSha1 = duCls.getMethod("getSha1", File.class);
                Path tmpSha = Files.createTempFile("sunlit-sha-test", ".bin");
                try {
                    Files.writeString(tmpSha, "hello sunlit");
                    String h1 = (String) getSha1.invoke(null, tmpSha.toFile());
                    String h2 = (String) getSha1.invoke(null, tmpSha.toFile());
                    if (h1 == null || !h1.equals(h2)) throw new AssertionError("DownloadUtils cached SHA mismatch: " + h1 + " vs " + h2);
                    Files.writeString(tmpSha, "hello sunlit updated");
                    tmpSha.toFile().setLastModified(System.currentTimeMillis() + 5000L);
                    String h3 = (String) getSha1.invoke(null, tmpSha.toFile());
                    if (h3 == null || h3.equals(h1)) throw new AssertionError("DownloadUtils failed to recompute SHA on changed file");
                } finally {
                    Files.deleteIfExists(tmpSha);
                }
                Class<?> ppCls = Class.forName("net.minecraftforge.installer.actions.PostProcessors", false, instCl);
                if (ppCls.getDeclaredField("sunlitShaProps") == null) throw new AssertionError("Missing PostProcessors.sunlitShaProps");
                if (ppCls.getDeclaredMethod("sunlitRawProcess", File.class, File.class, File.class, File.class) == null) throw new AssertionError("Missing sunlitRawProcess");
                if (ppCls.getMethod("process", File.class, File.class, File.class, File.class) == null) throw new AssertionError("Missing PostProcessors.process");
            }
        }
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("").length != 0) throw new AssertionError("empty zip norm");
        if (!Arrays.equals(dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("/"), new byte[]{'/'})) throw new AssertionError("root zip norm");
        if (!Arrays.equals(dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("META-INF/mods.toml/"), "META-INF/mods.toml".getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new AssertionError("trail slash zip norm");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("a//b") != null) throw new AssertionError("double slash should fallback");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("a//") != null) throw new AssertionError("double trailing slash should fallback");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("a\\\\b") != null) throw new AssertionError("backslash should fallback");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipNormalizeAscii("\\uD55C\\uAE00/a") != null) throw new AssertionError("non-ascii should fallback");
        if (!Arrays.equals(dev.sunlit.bootagent.payload.BootJarPayload.fastZipAbsUtf8Path(""), new byte[]{'/'})) throw new AssertionError("empty abs zip");
        if (!Arrays.equals(dev.sunlit.bootagent.payload.BootJarPayload.fastZipAbsUtf8Path("assets/a.png"), "/assets/a.png".getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new AssertionError("rel abs zip");
        if (!Arrays.equals(dev.sunlit.bootagent.payload.BootJarPayload.fastZipAbsUtf8Path("/assets/a.png/"), "/assets/a.png".getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new AssertionError("slash abs zip");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipAbsUtf8Path("a/../b") != null) throw new AssertionError("dotdot should fallback");
        if (dev.sunlit.bootagent.payload.BootJarPayload.fastZipAbsUtf8Path("./a") != null) throw new AssertionError("dot should fallback");
        // Verify Resolver makeGraph + closeGraph + fastCheckExportSuppliers with >= 2 automatic modules + parent layer
        java.lang.module.ModuleDescriptor dAuto1 = java.lang.module.ModuleDescriptor.newAutomaticModule("sunlit.auto.one").packages(Set.of("sunlit.auto.p1")).build();
        java.lang.module.ModuleDescriptor dAuto2 = java.lang.module.ModuleDescriptor.newAutomaticModule("sunlit.auto.two").packages(Set.of("sunlit.auto.p2")).build();
        java.lang.module.ModuleDescriptor dAuto3 = java.lang.module.ModuleDescriptor.newAutomaticModule("sunlit.auto.three").packages(Set.of("sunlit.auto.p3")).build();
        java.lang.module.ModuleDescriptor dNamed = java.lang.module.ModuleDescriptor.newModule("sunlit.named.one")
                .requires("java.base").requires("sunlit.auto.one").packages(Set.of("sunlit.named.p1")).build();
        Map<String, java.lang.module.ModuleReference> synthMods = new LinkedHashMap<>();
        for (java.lang.module.ModuleDescriptor md : new java.lang.module.ModuleDescriptor[]{dAuto1, dAuto2, dAuto3, dNamed}) {
            synthMods.put(md.name(), new java.lang.module.ModuleReference(md, null) {
                @Override public java.lang.module.ModuleReader open() { return null; }
            });
        }
        java.lang.module.ModuleFinder synthFinder = new java.lang.module.ModuleFinder() {
            @Override public Optional<java.lang.module.ModuleReference> find(String name) { return Optional.ofNullable(synthMods.get(name)); }
            @Override public Set<java.lang.module.ModuleReference> findAll() { return new LinkedHashSet<>(synthMods.values()); }
        };
        java.lang.module.Configuration resolvedCfg = ModuleLayer.boot().configuration()
                .resolve(synthFinder, java.lang.module.ModuleFinder.of(), Set.of("sunlit.auto.one", "sunlit.auto.two", "sunlit.auto.three", "sunlit.named.one"));
        java.lang.module.ResolvedModule rmAuto1 = resolvedCfg.findModule("sunlit.auto.one").orElseThrow();
        java.lang.module.ResolvedModule rmAuto2 = resolvedCfg.findModule("sunlit.auto.two").orElseThrow();
        java.lang.module.ResolvedModule rmNamed = resolvedCfg.findModule("sunlit.named.one").orElseThrow();
        java.lang.module.ResolvedModule rmBase = resolvedCfg.findModule("java.base").orElseThrow();
        if (!rmAuto1.reads().contains(rmAuto2) || !rmAuto1.reads().contains(rmNamed) || !rmAuto1.reads().contains(rmBase) || !rmNamed.reads().contains(rmAuto2)) {
            throw new AssertionError("Resolver shared auto reads mismatch");
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
subprocess.run([str(JDK / 'java.exe'), f'-javaagent:{OUT_JAR}', '--add-opens', 'java.base/java.lang.module=ALL-UNNAMED', '--add-opens', 'java.base/java.lang.invoke=ALL-UNNAMED', '-cp', verify_cp, 'VerifyBootAgent', str(LIBRARIES), sjh_jar, fml_jar, mixin_jar, str(ASM_JAR), str(ASM_TREE_JAR), str(ASM_UTIL_JAR), str(ASM_ANALYSIS_JAR), modlauncher_jar, *extra_jars], check=True)
print('Built & verified', OUT_JAR, OUT_JAR.stat().st_size, 'bytes')
