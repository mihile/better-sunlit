package dev.sunlit.bootagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Early bootstrap {@link ClassFileTransformer} eliminating filesystem stat bottlenecks,
 * O(N^3) JPMS module graph resolution, single-threaded FML candidate scanning,
 * and Mixin lock/reflection hotspots during cold launch.
 */
public final class SunlitBootTransformer implements ClassFileTransformer {
    private static final String RESOLVER_CLS = "java/lang/module/Resolver";
    private static final String JAVA_FILES_CLS = "java/nio/file/Files";
    private static final String ZIPFS_CLS = "jdk/nio/zipfs/ZipFileSystem";
    private static final String ZIPFS_ENTRY_CLS = "jdk/nio/zipfs/ZipFileSystem$Entry";
    private static final String UNION_CLS = "cpw/mods/niofs/union/UnionFileSystem";
    private static final String UNION_PROVIDER_CLS = "cpw/mods/niofs/union/UnionFileSystemProvider";
    private static final String JAR_CLS = "cpw/mods/jarhandling/impl/Jar";
    private static final String JAR_PROVIDER_CLS = "cpw/mods/jarhandling/impl/Jar$JarModuleDataProvider";
    private static final String ATTR_TEMPLATES_1_CLS = "org/embeddedt/modernfix/entity/AttributeInstanceTemplates$1";
    private static final String OUTLINE_FONT_CLS = "icyllis/modernui/graphics/text/OutlineFont";
    private static final String IMMUTABLE_MAP_CLS = "com/google/common/collect/ImmutableMap";
    private static final String REG_IMMUTABLE_MAP_CLS = "com/google/common/collect/RegularImmutableMap";
    private static final String MOD_DISCOVERER_CLS = "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer";
    private static final String ABSTRACT_JAR_LOCATOR_CLS = "net/minecraftforge/fml/loading/moddiscovery/AbstractJarFileModLocator";
    private static final String ABSTRACT_JAR_DEP_LOCATOR_CLS = "net/minecraftforge/fml/loading/moddiscovery/AbstractJarFileDependencyLocator";
    private static final String ABSTRACT_MOD_PROVIDER_CLS = "net/minecraftforge/fml/loading/moddiscovery/AbstractModProvider";
    private static final String BG_WAITER_CLS = "net/minecraftforge/fml/loading/BackgroundWaiter";
    private static final String SCAN_CLS = "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler";
    private static final String MIXIN_BYTECODE_CLS = "org/spongepowered/asm/util/Bytecode";
    private static final String MIXIN_PLUGIN_CLS = "org/spongepowered/asm/launch/MixinLaunchPluginLegacy";
    private static final String MIXIN_TRACKER_CLS = "org/spongepowered/asm/service/modlauncher/ModLauncherClassTracker";
    private static final String MIXIN_CLASS_INFO_CLS = "org/spongepowered/asm/mixin/transformer/ClassInfo";
    private static final String MIXIN_CLASS_INFO_METHOD_CLS = "org/spongepowered/asm/mixin/transformer/ClassInfo$Method";
    private static final String MIXIN_CLASS_INFO_MEMBER_CLS = "org/spongepowered/asm/mixin/transformer/ClassInfo$Member";
    private static final String MIXIN_ACTIVITY_STACK_CLS = "org/spongepowered/asm/mixin/transformer/ActivityStack";
    private static final String MIXIN_ACTIVITY_CLS = "org/spongepowered/asm/mixin/transformer/ActivityStack$Activity";
    private static final String MIXIN_METHOD_NODE_EX_CLS = "org/spongepowered/asm/util/asm/MethodNodeEx";
    private static final String MIXIN_METHOD_MAPPER_CLS = "org/spongepowered/asm/mixin/transformer/MethodMapper";
    private static final String MIXIN_HANDLER_CLS = "org/spongepowered/asm/service/modlauncher/MixinTransformationHandler";
    private static final String MIXIN_TRANSFORMER_CLS = "org/spongepowered/asm/mixin/transformer/MixinTransformer";
    private static final String MIXIN_PROCESSOR_CLS = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    private static final String MIXIN_CONFIG_CLS = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String MIXIN_CP_PASS_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorPassthrough";
    private static final String MIXIN_CP_ACC_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorAccessor";
    private static final String MIXIN_CP_NEST_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorNestHost";
    private static final String MIXIN_CP_SYN_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorSyntheticInner";
    private static final String PACK_CACHE_CLS = "org/embeddedt/modernfix/resources/PackResourcesCacheEngine";
    private static final String PACK_CACHE_NODE_CLS = "org/embeddedt/modernfix/resources/PackResourcesCacheEngine$Node";
    private static final String FAST_MAP_CLS = "malte0811/ferritecore/fastmap/FastMap";
    private static final String FAST_MAP_KEY_CLS = "malte0811/ferritecore/fastmap/FastMapKey";
    private static final String STAR_SHAPE_CLS = "fuzs/diagonalblocks/api/v2/impl/StarShapeProvider";
    private static final String CLASS_TRANSFORMER_CLS = "cpw/mods/modlauncher/ClassTransformer";
    private static final String LISTENER_LIST_INST_CLS = "net/minecraftforge/eventbus/ListenerList$ListenerListInst";
    private static final String UNION_PATH_CLS = "cpw/mods/niofs/union/UnionPath";
    private static final String MIXIN_SQ_CANCEL_CLS = "com/bawnorton/mixinsquared/canceller/ExtensionCancelApplication";
    private static final String MIXIN_SQ_ADJUST_CLS = "com/bawnorton/mixinsquared/adjuster/ExtensionAnnotationAdjust";
    private static final String AT_LIST_CLS = "net/minecraftforge/accesstransformer/parser/AccessTransformerList";
    private static final String DOWNLOAD_UTILS_CLS = "net/minecraftforge/installer/DownloadUtils";
    private static final String POST_PROCESSORS_CLS = "net/minecraftforge/installer/actions/PostProcessors";
    private static final String MIXIN_MAIN_ATTRS_CLS = "org/spongepowered/asm/launch/platform/MainAttributes";
    private static final String CUSTOM_POTION_CLS = "net/ribs/vintagedelight/mobEffects/CustomPotionBrewing";
    private static final String FML_SCANNER_CLS = "net/minecraftforge/fml/loading/moddiscovery/Scanner";
    private static final String MC_REMAPPER_CLS = "dev/latvian/mods/rhino/mod/util/MinecraftRemapper";
    private static final String FERRITE_DEDUP_CLS = "malte0811/ferritecore/impl/Deduplicator";
    private static final String MIXIN_PAYLOAD_CLS = "org/spongepowered/asm/mixin/transformer/BootMixinPayload";
    private static final String JAR_PAYLOAD_CLS = "dev/sunlit/bootagent/payload/BootJarPayload";
    private static final String DOWNLOAD_PAYLOAD_CLS = "dev/sunlit/bootagent/payload/BootDownloadPayload";
    private static final String RESOLVER_PAYLOAD_RES = "/dev/sunlit/bootagent/payload/BootResolverPayload.class";
    private static final String MIXIN_PAYLOAD_RES = "/dev/sunlit/bootagent/payload/BootMixinPayload.class";
    private static final String DOWNLOAD_PAYLOAD_RES = "/dev/sunlit/bootagent/payload/BootDownloadPayload.class";
    private static Instrumentation instrumentation;
    private static Module bootPayloadModule;
    private static final Set<Module> READ_EDGE_ADDED = ConcurrentHashMap.newKeySet();

    public static void install(Instrumentation inst, Module payloadModule) {
        instrumentation = inst;
        bootPayloadModule = payloadModule;
        inst.addTransformer(new SunlitBootTransformer(), true);
        retransformIfLoaded(inst, "java.lang.module.Resolver", null);
        retransformIfLoaded(inst, "java.nio.file.Files", null);
        retransformIfLoaded(inst, "jdk.nio.zipfs.ZipFileSystem$Entry", ClassLoader.getPlatformClassLoader());
        retransformIfLoaded(inst, "org.objectweb.asm.Type", null);
        for (Class<?> cls : inst.getAllLoadedClasses()) {
            String n = cls.getName();
            if (("cpw.mods.niofs.union.UnionPath".equals(n) || "jdk.nio.zipfs.ZipFileSystem".equals(n))
                    && inst.isModifiableClass(cls)) {
                try {
                    inst.retransformClasses(cls);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void retransformIfLoaded(Instrumentation inst, String className, ClassLoader loader) {
        try {
            Class<?> cls = Class.forName(className, false, loader);
            if (inst.isModifiableClass(cls)) {
                inst.retransformClasses(cls);
            }
        } catch (Throwable t) {
            System.err.println("[SunlitBootAgent] Retransform skipped for " + className + ": " + t);
        }
    }

    private static void ensureReadsBootPayload(Module module) {
        if (module == null || !module.isNamed() || bootPayloadModule == null || instrumentation == null) {
            return;
        }
        if (READ_EDGE_ADDED.add(module)) {
            try {
                instrumentation.redefineModule(
                        module,
                        Set.of(bootPayloadModule),
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Map.of());
            } catch (Throwable t) {
                System.err.println("[SunlitBootAgent] redefineModule failed for " + module.getName() + ": " + t);
            }
        }
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        try {
            return switch (className) {
                case RESOLVER_CLS -> transformResolver(loader, classfileBuffer);
                case JAVA_FILES_CLS -> transformJavaFiles(classfileBuffer);
                case ZIPFS_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformZipFileSystem(loader, classfileBuffer);
                }
                case ZIPFS_ENTRY_CLS -> transformZipFileSystemEntry(loader, classfileBuffer);
                case UNION_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformUnionFileSystem(loader, classfileBuffer);
                }
                case UNION_PATH_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformUnionPath(loader, classfileBuffer, classBeingRedefined == null);
                }
                case UNION_PROVIDER_CLS -> transformUnionFileSystemProvider(classfileBuffer);
                case JAR_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformJar(loader, classfileBuffer);
                }
                case JAR_PROVIDER_CLS -> transformJarModuleDataProvider(loader, classfileBuffer);
                case "cpw/mods/cl/JarModuleFinder$JarModuleReader" ->
                        transformJarModuleReader(loader, classfileBuffer);
                case ATTR_TEMPLATES_1_CLS -> transformAttributeInstanceTemplates1(classfileBuffer);
                case OUTLINE_FONT_CLS -> transformModernUiOutlineFont(classfileBuffer);
                case MOD_DISCOVERER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformModDirDiscoverer(loader, classfileBuffer);
                }
                case ABSTRACT_JAR_LOCATOR_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformAbstractJarLocator(classfileBuffer);
                }
                case ABSTRACT_JAR_DEP_LOCATOR_CLS -> transformAbstractJarDepLocator(loader, classfileBuffer);
                case ABSTRACT_MOD_PROVIDER_CLS -> transformAbstractModProvider(classfileBuffer);
                case BG_WAITER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformBackgroundWaiter(classfileBuffer);
                }
                case SCAN_CLS -> transformBackgroundScanHandler(loader, classfileBuffer);
                case MIXIN_BYTECODE_CLS -> transformMixinBytecode(loader, classfileBuffer);
                case MIXIN_PLUGIN_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformMixinLaunchPlugin(loader, classfileBuffer);
                }
                case MIXIN_TRACKER_CLS -> transformMixinClassTracker(loader, classfileBuffer);
                case MIXIN_CLASS_INFO_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformClassInfo(loader, classfileBuffer);
                }
                case MIXIN_CLASS_INFO_METHOD_CLS -> transformClassInfoMethod(loader, classfileBuffer);
                case MIXIN_CLASS_INFO_MEMBER_CLS, MIXIN_ACTIVITY_STACK_CLS, MIXIN_ACTIVITY_CLS, MIXIN_METHOD_NODE_EX_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformMixinStringFormat(classfileBuffer);
                }
                case MIXIN_METHOD_MAPPER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformMethodMapper(classfileBuffer);
                }
                case MIXIN_HANDLER_CLS -> transformMixinHandler(loader, classfileBuffer);
                case MIXIN_TRANSFORMER_CLS -> widenClass(classfileBuffer, Set.of("processor"), Set.of());
                case MIXIN_PROCESSOR_CLS -> transformMixinProcessor(loader, classfileBuffer);
                case MIXIN_CONFIG_CLS -> widenClass(classfileBuffer, Set.of("mixinMapping"), Set.of());
                case MIXIN_CP_PASS_CLS -> widenClass(classfileBuffer, Set.of("loadable"), Set.of("registerLoadable"));
                case MIXIN_CP_ACC_CLS -> widenClass(classfileBuffer, Set.of("accessorMixins"), Set.of("registerAccessor"));
                case MIXIN_CP_NEST_CLS -> widenClass(classfileBuffer, Set.of("nestHosts"), Set.of("registerNestMember"));
                case MIXIN_CP_SYN_CLS -> widenClass(classfileBuffer, Set.of("syntheticInnerClasses"), Set.of("registerSyntheticInner"));
                case PACK_CACHE_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformPackCacheEngine(loader, classfileBuffer);
                }
                case PACK_CACHE_NODE_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformPackCacheNode(classfileBuffer);
                }
                case FAST_MAP_CLS -> transformFastMap(loader, classfileBuffer);
                case IMMUTABLE_MAP_CLS -> transformImmutableMap(loader, classfileBuffer);
                case REG_IMMUTABLE_MAP_CLS -> transformRegularImmutableMap(classfileBuffer);
                case STAR_SHAPE_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformStarShapeProvider(loader, classfileBuffer);
                }
                case CLASS_TRANSFORMER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformModLauncherClassTransformer(loader, classfileBuffer);
                }
                case AT_LIST_CLS -> transformAccessTransformerList(loader, classfileBuffer);
                case DOWNLOAD_UTILS_CLS -> transformDownloadUtils(loader, classfileBuffer);
                case POST_PROCESSORS_CLS -> transformPostProcessors(loader, classfileBuffer);
                case LISTENER_LIST_INST_CLS -> transformListenerListInst(loader, classfileBuffer);
                case MIXIN_SQ_CANCEL_CLS, MIXIN_SQ_ADJUST_CLS -> transformMixinSquaredSlashReplace(classfileBuffer);
                case MIXIN_MAIN_ATTRS_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformMainAttributes(loader, classfileBuffer);
                }
                case CUSTOM_POTION_CLS -> transformCustomPotionBrewing(classfileBuffer);
                case MC_REMAPPER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformMinecraftRemapper(classfileBuffer);
                }
                case FERRITE_DEDUP_CLS -> transformDeduplicator(loader, classfileBuffer);
                case "net/minecraftforge/resource/DelegatingPackResources" ->
                        transformDelegatingPackResources(loader, classfileBuffer);
                case "net/minecraftforge/resource/PathPackResources" -> {
                    ensureReadsBootPayload(module);
                    yield transformPathPackResources(loader, classfileBuffer);
                }
                case "net/minecraftforge/fml/ModWorkManager" ->
                        transformModWorkManager(loader, classfileBuffer);
                case "org/apache/logging/log4j/core/appender/AbstractOutputStreamAppender" ->
                        transformAbstractOutputStreamAppender(loader, classfileBuffer);
                case "net/minecraftforge/client/model/obj/ObjModel" -> {
                    ensureReadsBootPayload(module);
                    yield transformObjModel(loader, classfileBuffer);
                }
                case "org/objectweb/asm/Type" -> {
                    ensureReadsBootPayload(module);
                    yield transformAsmType(classfileBuffer);
                }
                case "net/minecraftforge/registries/NamespacedWrapper" ->
                        transformNamespacedWrapper(loader, classfileBuffer, classBeingRedefined == null);
                case "net/mehvahdjukaar/moonlight/api/resources/pack/DynamicTexturePack" -> {
                    ensureReadsBootPayload(module);
                    yield transformDynamicTexturePack(classfileBuffer);
                }
                case "cpw/mods/cl/ModuleClassLoader" -> {
                    ensureReadsBootPayload(module);
                    yield transformModuleClassLoader(classfileBuffer);
                }
                case "net/minecraftforge/fml/loading/moddiscovery/ModFile" -> {
                    ensureReadsBootPayload(module);
                    yield transformModFile(classfileBuffer);
                }
                case "com/cupboard/compat/ClientConfigCompat" -> {
                    ensureReadsBootPayload(module);
                    yield transformCupboardClientConfigCompat(loader, classfileBuffer);
                }
                case "de/keksuccino/fancymenu/util/properties/PropertiesParser" -> {
                    ensureReadsBootPayload(module);
                    yield transformFancyPropertiesParser(loader, classfileBuffer);
                }
                case "com/github/alexthe668/domesticationinnovation/client/render/LayerManager" -> {
                    ensureReadsBootPayload(module);
                    yield transformDomesticationLayerManager(loader, classfileBuffer);
                }
                case "net/fabricmc/fabric/impl/renderer/SpriteFinderImpl" ->
                        transformSpriteFinderImpl(loader, classfileBuffer);
                case "net/minecraft/world/level/block/Block" ->
                        transformBlockIdMapper(classfileBuffer);
                case "net/minecraftforge/eventbus/ListenerList" ->
                        transformListenerList(loader, classfileBuffer);
                case "org/spongepowered/asm/util/perf/Profiler" ->
                        transformMixinProfiler(loader, classfileBuffer);
                case "net/minecraft/world/level/block/state/StateDefinition$Builder" -> {
                    ensureReadsBootPayload(module);
                    yield transformStateDefinitionBuilder(classfileBuffer);
                }
                case "net/minecraft/core/IdMapper" -> {
                    ensureReadsBootPayload(module);
                    yield transformIdMapper(loader, classfileBuffer);
                }
                case FML_SCANNER_CLS -> {
                    ensureReadsBootPayload(module);
                    yield transformFmlScanner(loader, classfileBuffer);
                }
                default -> null;
            };
        } catch (Throwable t) {
            System.err.println("[SunlitBootAgent] Transform failed for " + className + ": " + t);
            return null;
        }
    }

    public static byte[] transformZipFileSystem(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        boolean hasPredicateTest = false;
        for (MethodNode mn : cn.methods) {
            if ("test".equals(mn.name) && "(Ljava/lang/Object;)Z".equals(mn.desc)) {
                hasPredicateTest = true;
            }
            if ("getInputStream".equals(mn.name)
                    && "(Ljdk/nio/zipfs/ZipFileSystem$Entry;)Ljava/io/InputStream;".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof LdcInsnNode lin && Long.valueOf(65536L).equals(lin.cst)) {
                        lin.cst = 8192L;
                        patched = true;
                    }
                }
                continue;
            }
            if (!"<init>".equals(mn.name)
                    || !"(Ljdk/nio/zipfs/ZipFileSystemProvider;Ljava/nio/file/Path;Ljava/util/Map;)V".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode notExistsStart = null;
            AbstractInsnNode afterReadOnlyPut = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (notExistsStart == null
                        && insn instanceof MethodInsnNode min
                        && "java/nio/file/Files".equals(min.owner)
                        && "notExists".equals(min.name)) {
                    // Walk back to ALOAD 2 (zfpath) starting the Files.notExists(zfpath, ...) call.
                    AbstractInsnNode back = insn.getPrevious();
                    for (int k = 0; k < 6 && back != null; k++, back = back.getPrevious()) {
                        if (back instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD && vin.var == 2) {
                            notExistsStart = back;
                            break;
                        }
                    }
                }
                if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.PUTFIELD
                        && ZIPFS_CLS.equals(fin.owner)
                        && "readOnly".equals(fin.name)
                        && "Z".equals(fin.desc)) {
                    afterReadOnlyPut = insn.getNext();
                }
            }
            if (notExistsStart != null && afterReadOnlyPut != null) {
                LabelNode continueLabel = new LabelNode();
                LabelNode skipLabel = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 3));
                guard.add(new JumpInsnNode(Opcodes.IFNULL, continueLabel));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 3));
                guard.add(new LdcInsnNode("sunlit.readOnly"));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new InsnNode(Opcodes.ICONST_1));
                guard.add(new FieldInsnNode(Opcodes.PUTFIELD, ZIPFS_CLS, "readOnly", "Z"));
                guard.add(new JumpInsnNode(Opcodes.GOTO, skipLabel));
                guard.add(continueLabel);
                mn.instructions.insertBefore(notExistsStart, guard);
                mn.instructions.insertBefore(afterReadOnlyPut, skipLabel);
                patched = true;
            }
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>();
        }
        if (!cn.interfaces.contains("java/util/function/Predicate")) {
            cn.interfaces.add("java/util/function/Predicate");
            patched = true;
        }
        if (!hasPredicateTest) {
            MethodNode testM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "test",
                    "(Ljava/lang/Object;)Z",
                    null,
                    null);
            LabelNode notString = new LabelNode();
            LabelNode slowFallback = new LabelNode();
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            testM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/String"));
            testM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notString));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            testM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            testM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "fastZipAbsUtf8Path", "(Ljava/lang/String;)[B", false));
            testM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            testM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, slowFallback));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "exists", "([B)Z", false));
            testM.instructions.add(new InsnNode(Opcodes.IRETURN));
            testM.instructions.add(slowFallback);
            // ZipPath.exists() is private on some Java 17 builds. Use its underlying
            // lookup on this filesystem after the JDK resolves the path.
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            testM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            testM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljdk/nio/zipfs/ZipPath;", false));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "jdk/nio/zipfs/ZipPath", "getResolvedPath", "()[B", false));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "exists", "([B)Z", false));
            testM.instructions.add(new InsnNode(Opcodes.IRETURN));
            testM.instructions.add(notString);
            testM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            testM.instructions.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(testM);
            patched = true;
        }
        if (!cn.interfaces.contains("java/util/function/BiFunction")) {
            cn.interfaces.add("java/util/function/BiFunction");
            patched = true;
        }
        boolean hasBiApply = false;
        for (MethodNode mn : cn.methods) {
            if ("apply".equals(mn.name) && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(mn.desc)) {
                hasBiApply = true;
                break;
            }
        }
        if (!hasBiApply) {
            MethodNode applyM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    null,
                    null);
            LabelNode retNull = new LabelNode();
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode catchEx = new LabelNode();
            LabelNode methodOk = new LabelNode();
            applyM.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchEx, "java/io/IOException"));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            applyM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "jdk/nio/zipfs/ZipPath"));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retNull));
            applyM.instructions.add(tryStart);
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "ensureOpen", "()V", false));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "jdk/nio/zipfs/ZipPath"));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "jdk/nio/zipfs/ZipPath", "getResolvedPath", "()[B", false));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "getEntry", "([B)Ljdk/nio/zipfs/ZipFileSystem$Entry;", false));
            applyM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, ZIPFS_ENTRY_CLS, "isDir", "()Z", false));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFNE, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "type", "I"));
            applyM.instructions.add(new InsnNode(Opcodes.ICONST_1));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            applyM.instructions.add(new InsnNode(Opcodes.LCONST_0));
            applyM.instructions.add(new InsnNode(Opcodes.LCMP));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFLT, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            applyM.instructions.add(new LdcInsnNode(524288L));
            applyM.instructions.add(new InsnNode(Opcodes.LCMP));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFGT, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, methodOk));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            applyM.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 8));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, retNull));

            applyM.instructions.add(methodOk);
            applyM.instructions.add(new TypeInsnNode(Opcodes.NEW, "jdk/nio/zipfs/ZipFileSystem$EntryInputStream"));
            applyM.instructions.add(new InsnNode(Opcodes.DUP));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "jdk/nio/zipfs/ZipFileSystem$EntryInputStream",
                    "<init>",
                    "(Ljdk/nio/zipfs/ZipFileSystem;Ljdk/nio/zipfs/ZipFileSystem$Entry;)V",
                    false));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            applyM.instructions.add(new InsnNode(Opcodes.L2I));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAR_PAYLOAD_CLS,
                    "inflateEntryStream",
                    "(Ljava/io/InputStream;II[I)[B",
                    false));
            applyM.instructions.add(tryEnd);
            applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
            applyM.instructions.add(catchEx);
            applyM.instructions.add(new InsnNode(Opcodes.POP));
            applyM.instructions.add(retNull);
            applyM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(applyM);
            patched = true;
        }
        for (MethodNode mn : cn.methods) {
            if ("newByteChannel".equals(mn.name) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/io/InputStream".equals(min.owner)
                            && "readAllBytes".equals(min.name)
                            && "()[B".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = JAR_PAYLOAD_CLS;
                        min.name = "fastReadStreamExact";
                        min.desc = "(Ljava/io/InputStream;)[B";
                        patched = true;
                        break;
                    }
                }
            } else if ("newInputStream".equals(mn.name) && "([B)Ljava/io/InputStream;".equals(mn.desc) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && ZIPFS_CLS.equals(min.owner)
                            && "getInputStream".equals(min.name)
                            && "(Ljdk/nio/zipfs/ZipFileSystem$Entry;)Ljava/io/InputStream;".equals(min.desc)) {
                        min.name = "sunlitFastInputStream";
                        patched = true;
                        break;
                    }
                }
            }
        }
        boolean hasFastIn = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitFastInputStream".equals(mn.name)) {
                hasFastIn = true;
                break;
            }
        }
        if (!hasFastIn) {
            MethodNode fastInM = new MethodNode(
                    Opcodes.ACC_PRIVATE,
                    "sunlitFastInputStream",
                    "(Ljdk/nio/zipfs/ZipFileSystem$Entry;)Ljava/io/InputStream;",
                    null,
                    new String[] {"java/io/IOException"});
            LabelNode fallback = new LabelNode();
            LabelNode methodOk = new LabelNode();
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode catchEx = new LabelNode();
            fastInM.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchEx, "java/io/IOException"));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "type", "I"));
            fastInM.instructions.add(new InsnNode(Opcodes.ICONST_1));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fallback));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            fastInM.instructions.add(new InsnNode(Opcodes.LCONST_0));
            fastInM.instructions.add(new InsnNode(Opcodes.LCMP));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IFLT, fallback));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            fastInM.instructions.add(new LdcInsnNode(524288L));
            fastInM.instructions.add(new InsnNode(Opcodes.LCMP));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IFGT, fallback));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            fastInM.instructions.add(new InsnNode(Opcodes.LCONST_0));
            fastInM.instructions.add(new InsnNode(Opcodes.LCMP));
            LabelNode nonZero = new LabelNode();
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
            fastInM.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
            fastInM.instructions.add(new InsnNode(Opcodes.DUP));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, JAR_PAYLOAD_CLS, "EMPTY_ZIP_BYTES", "[B"));
            fastInM.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayInputStream", "<init>", "([B)V", false));
            fastInM.instructions.add(new InsnNode(Opcodes.ARETURN));
            fastInM.instructions.add(nonZero);
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, methodOk));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            fastInM.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 8));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fallback));
            fastInM.instructions.add(methodOk);
            fastInM.instructions.add(tryStart);
            fastInM.instructions.add(new TypeInsnNode(Opcodes.NEW, "jdk/nio/zipfs/ZipFileSystem$EntryInputStream"));
            fastInM.instructions.add(new InsnNode(Opcodes.DUP));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "jdk/nio/zipfs/ZipFileSystem$EntryInputStream",
                    "<init>",
                    "(Ljdk/nio/zipfs/ZipFileSystem;Ljdk/nio/zipfs/ZipFileSystem$Entry;)V",
                    false));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "method", "I"));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, ZIPFS_ENTRY_CLS, "size", "J"));
            fastInM.instructions.add(new InsnNode(Opcodes.L2I));
            fastInM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            fastInM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAR_PAYLOAD_CLS,
                    "inflateEntryStream",
                    "(Ljava/io/InputStream;II[I)[B",
                    false));
            fastInM.instructions.add(tryEnd);
            fastInM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fastInM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
            fastInM.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
            fastInM.instructions.add(new InsnNode(Opcodes.DUP));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fastInM.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayInputStream", "<init>", "([B)V", false));
            fastInM.instructions.add(new InsnNode(Opcodes.ARETURN));
            fastInM.instructions.add(catchEx);
            fastInM.instructions.add(new InsnNode(Opcodes.POP));
            fastInM.instructions.add(fallback);
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastInM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastInM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    ZIPFS_CLS,
                    "getInputStream",
                    "(Ljdk/nio/zipfs/ZipFileSystem$Entry;)Ljava/io/InputStream;",
                    false));
            fastInM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(fastInM);
            patched = true;
        }
        return patched ? writeNodeWithFrames(cn, loader != null ? loader : ClassLoader.getPlatformClassLoader()) : null;
    }

    public static byte[] transformZipFileSystemEntry(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"readLocEXTT".equals(mn.name) || !"(Ljdk/nio/zipfs/ZipFileSystem;)V".equals(mn.desc)) {
                continue;
            }
            LabelNode origHead = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ZIPFS_CLS, "isReadOnly", "()Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, origHead));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(origHead);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformBackgroundWaiter(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"runAndTick".equals(mn.name)) {
                continue;
            }
            if ("(Ljava/lang/Runnable;Ljava/lang/Runnable;)V".equals(mn.desc)) {
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                pre.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "prewarmDuringBackgroundWait",
                        "(Ljava/lang/Runnable;)V",
                        false));
                mn.instructions.insert(pre);
                patched = true;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof LdcInsnNode lin && Long.valueOf(50L).equals(lin.cst)) {
                    lin.cst = 5L;
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformUnionFileSystem(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("openFileSystem".equals(mn.name)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/nio/file/FileSystems".equals(min.owner)
                            && "newFileSystem".equals(min.name)
                            && "(Ljava/nio/file/Path;)Ljava/nio/file/FileSystem;".equals(min.desc)) {
                        InsnList envMap = new InsnList();
                        envMap.add(new LdcInsnNode("sunlit.readOnly"));
                        envMap.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"));
                        envMap.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "java/util/Collections",
                                "singletonMap",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;",
                                false));
                        mn.instructions.insertBefore(min, envMap);
                        min.desc = "(Ljava/nio/file/Path;Ljava/util/Map;)Ljava/nio/file/FileSystem;";
                        patched = true;
                    }
                }
            } else if ("findFirstFiltered".equals(mn.name)
                    && "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;".equals(mn.desc)) {
                patched |= deferUnionFilterUntilExists(mn);
            } else if ("buildInputStream".equals(mn.name)
                    && "(Lcpw/mods/niofs/union/UnionPath;)Ljava/io/InputStream;".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/nio/file/Files".equals(min.owner)
                            && "readAllBytes".equals(min.name)
                            && "(Ljava/nio/file/Path;)[B".equals(min.desc)) {
                        min.owner = JAR_PAYLOAD_CLS;
                        min.name = "readAllBytesExact";
                        patched = true;
                        break;
                    }
                }
            } else if ("byteChannel".equals(mn.name)
                    && "(Ljava/nio/file/Path;)Ljava/nio/channels/SeekableByteChannel;".equals(mn.desc)) {
                InsnList hook = new InsnList();
                LabelNode missLabel = new LabelNode();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                hook.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "openCachedReadChannel",
                        "(Ljava/nio/file/Path;)Ljava/nio/channels/SeekableByteChannel;",
                        false));
                hook.add(new InsnNode(Opcodes.DUP));
                hook.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
                hook.add(new InsnNode(Opcodes.ARETURN));
                hook.add(missLabel);
                hook.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(hook);
                patched = true;
            }
        }
        boolean hasNewInputStream = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitNewInputStream".equals(mn.name)) {
                hasNewInputStream = true;
                break;
            }
        }
        if (!hasNewInputStream) {
            MethodNode m = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "sunlitNewInputStream",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/io/InputStream;",
                    null,
                    new String[] { "java/io/IOException" });
            LabelNode foundLabel = new LabelNode();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "findFirstFiltered",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;",
                    false));
            m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            m.instructions.add(new JumpInsnNode(Opcodes.IFNE, foundLabel));
            m.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/nio/file/NoSuchFileException"));
            m.instructions.add(new InsnNode(Opcodes.DUP));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath", "toString", "()Ljava/lang/String;", false));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, "java/nio/file/NoSuchFileException", "<init>", "(Ljava/lang/String;)V", false));
            m.instructions.add(new InsnNode(Opcodes.ATHROW));
            m.instructions.add(foundLabel);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
            m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/nio/file/Path"));
            m.instructions.add(new InsnNode(Opcodes.ICONST_0));
            m.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/OpenOption"));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/nio/file/Files",
                    "newInputStream",
                    "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;",
                    false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(m);

            MethodNode optM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "sunlitOpenOptional",
                    "(Ljava/nio/file/Path;)Ljava/util/Optional;",
                    null,
                    null);
            LabelNode optTryStart = new LabelNode();
            LabelNode optTryEnd = new LabelNode();
            LabelNode optCatch = new LabelNode();
            LabelNode optNotFound = new LabelNode();
            optM.tryCatchBlocks.add(new TryCatchBlockNode(optTryStart, optTryEnd, optCatch, "java/io/IOException"));
            optM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            optM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            optM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "cpw/mods/niofs/union/UnionPath"));
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "findFirstFiltered",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;",
                    false));
            optM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            optM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            optM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, optNotFound));
            optM.instructions.add(optTryStart);
            optM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
            optM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/nio/file/Path"));
            optM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            optM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/OpenOption"));
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/nio/file/Files",
                    "newInputStream",
                    "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;",
                    false));
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
            optM.instructions.add(optTryEnd);
            optM.instructions.add(new InsnNode(Opcodes.ARETURN));
            optM.instructions.add(optCatch);
            optM.instructions.add(new InsnNode(Opcodes.POP));
            optM.instructions.add(optNotFound);
            optM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
            optM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(optM);
            patched = true;
        }
        boolean hasSunlitExists = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitExists".equals(mn.name)) {
                hasSunlitExists = true;
                break;
            }
        }
        if (!hasSunlitExists) {
            MethodNode existsM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "sunlitExists",
                    "(Lcpw/mods/niofs/union/UnionPath;)Z",
                    null,
                    null);
            existsM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            existsM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            existsM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "findFirstFiltered",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;",
                    false));
            existsM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            existsM.instructions.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(existsM);
            patched = true;
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>();
        }
        if (!cn.interfaces.contains("java/util/function/BiFunction")) {
            cn.interfaces.add("java/util/function/BiFunction");
            patched = true;
        }
        boolean hasBiApply = false;
        for (MethodNode mn : cn.methods) {
            if ("apply".equals(mn.name) && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(mn.desc)) {
                hasBiApply = true;
                break;
            }
        }
        if (!hasBiApply) {
            MethodNode applyM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    null,
                    null);
            LabelNode retNull = new LabelNode();
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            applyM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "cpw/mods/niofs/union/UnionPath"));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "cpw/mods/niofs/union/UnionPath"));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, UNION_CLS, "findFirstFiltered",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/util/Optional;", false));
            applyM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/nio/file/Path"));
            applyM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "getFileSystem",
                    "()Ljava/nio/file/FileSystem;", true));
            applyM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
            applyM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/util/function/BiFunction"));
            applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retNull));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/BiFunction"));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
            applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
            applyM.instructions.add(retNull);
            applyM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(applyM);
            patched = true;
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static boolean deferUnionFilterUntilExists(MethodNode mn) {
        // Locate `if (!this.testFilter(real, basepath)) goto loopNext;` (`ALOAD 0, ALOAD 4, ALOAD 3, INVOKEVIRTUAL testFilter, IFEQ`)
        AbstractInsnNode[] match = null;
        LabelNode loopNextLabel = null;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && UNION_CLS.equals(min.owner)
                    && "testFilter".equals(min.name)
                    && "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Z".equals(min.desc)) {
                AbstractInsnNode p3 = prevReal(min);
                AbstractInsnNode p2 = prevReal(p3);
                AbstractInsnNode p1 = prevReal(p2);
                AbstractInsnNode n1 = nextReal(min);
                if (p1 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 0
                        && p2 instanceof VarInsnNode v2 && v2.getOpcode() == Opcodes.ALOAD && v2.var == 4
                        && p3 instanceof VarInsnNode v3 && v3.getOpcode() == Opcodes.ALOAD && v3.var == 3
                        && n1 instanceof JumpInsnNode jin && jin.getOpcode() == Opcodes.IFEQ) {
                    match = new AbstractInsnNode[] { p1, p2, p3, min, jin };
                    loopNextLabel = jin.label;
                    break;
                }
            }
        }
        if (match == null || loopNextLabel == null) {
            return false;
        }
        java.util.List<AbstractInsnNode> optOfStarts = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKESTATIC
                    && "java/util/Optional".equals(min.owner)
                    && "of".equals(min.name)) {
                AbstractInsnNode prev = prevReal(min);
                AbstractInsnNode next = nextReal(min);
                if (prev instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD && vin.var == 4
                        && next != null && next.getOpcode() == Opcodes.ARETURN) {
                    optOfStarts.add(prev);
                }
            }
        }
        if (optOfStarts.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode node : match) {
            mn.instructions.remove(node);
        }
        for (AbstractInsnNode anchor : optOfStarts) {
            InsnList check = new InsnList();
            check.add(new VarInsnNode(Opcodes.ALOAD, 0));
            check.add(new VarInsnNode(Opcodes.ALOAD, 4));
            check.add(new VarInsnNode(Opcodes.ALOAD, 3));
            check.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, UNION_CLS, "testFilter", "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Z", false));
            check.add(new JumpInsnNode(Opcodes.IFEQ, loopNextLabel));
            mn.instructions.insertBefore(anchor, check);
        }

        // Fast embedded-ZipFS existence check in findFirstFiltered without constructing ZipPath or calling toAbsolutePath on misses.
        AbstractInsnNode toRealStart = null;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && UNION_CLS.equals(min.owner)
                    && "toRealPath".equals(min.name)) {
                AbstractInsnNode p3 = prevReal(min);
                AbstractInsnNode p2 = prevReal(p3);
                AbstractInsnNode p1 = prevReal(p2);
                if (p1 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 0
                        && p2 instanceof VarInsnNode v2 && v2.getOpcode() == Opcodes.ALOAD && v2.var == 3
                        && p3 instanceof VarInsnNode v3 && v3.getOpcode() == Opcodes.ALOAD && v3.var == 1) {
                    toRealStart = p1;
                    break;
                }
            }
        }
        if (toRealStart != null) {
            int maxVar = 5;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                    maxVar = v.var + 2;
                } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                    maxVar = i.var + 2;
                }
            }
            int relStrSlot = maxVar;
            int metaSlot = maxVar + 1;
            int chSlot = maxVar + 2;
            int fsSlot = maxVar + 3;

            InsnList headStr = new InsnList();
            LabelNode notAbsLabel = new LabelNode();
            LabelNode normLabel = new LabelNode();
            headStr.add(new InsnNode(Opcodes.ACONST_NULL));
            headStr.add(new VarInsnNode(Opcodes.ASTORE, 4));
            headStr.add(new VarInsnNode(Opcodes.ALOAD, 1));
            headStr.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath", "isAbsolute", "()Z", false));
            headStr.add(new JumpInsnNode(Opcodes.IFEQ, notAbsLabel));
            headStr.add(new VarInsnNode(Opcodes.ALOAD, 0));
            headStr.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_CLS, "root", "Lcpw/mods/niofs/union/UnionPath;"));
            headStr.add(new VarInsnNode(Opcodes.ALOAD, 1));
            headStr.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath", "relativize",
                    "(Ljava/nio/file/Path;)Ljava/nio/file/Path;", false));
            headStr.add(new JumpInsnNode(Opcodes.GOTO, normLabel));
            headStr.add(notAbsLabel);
            headStr.add(new VarInsnNode(Opcodes.ALOAD, 1));
            headStr.add(normLabel);
            headStr.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "normalize", "()Ljava/nio/file/Path;", true));
            headStr.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/nio/file/Path", "toString", "()Ljava/lang/String;", true));
            headStr.add(new VarInsnNode(Opcodes.ASTORE, relStrSlot));
            mn.instructions.insert(headStr);

            String metaInternal = UNION_CLS + "$EmbeddedFileSystemMetadata";
            String metaDesc = "L" + metaInternal + ";";
            InsnList fastMeta = new InsnList();
            LabelNode slowToReal = new LabelNode();
            LabelNode chOkLabel = new LabelNode();
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastMeta.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_CLS, "embeddedFileSystems", "Ljava/util/Map;"));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 3));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
            fastMeta.add(new TypeInsnNode(Opcodes.CHECKCAST, metaInternal));
            fastMeta.add(new VarInsnNode(Opcodes.ASTORE, metaSlot));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, metaSlot));
            fastMeta.add(new JumpInsnNode(Opcodes.IFNULL, slowToReal));

            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, metaSlot));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, metaInternal, "fsCh", "()Ljava/nio/channels/SeekableByteChannel;", false));
            fastMeta.add(new VarInsnNode(Opcodes.ASTORE, chSlot));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, chSlot));
            fastMeta.add(new JumpInsnNode(Opcodes.IFNULL, chOkLabel));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, chSlot));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/nio/channels/SeekableByteChannel", "isOpen", "()Z", true));
            fastMeta.add(new JumpInsnNode(Opcodes.IFNE, chOkLabel));
            fastMeta.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
            fastMeta.add(new InsnNode(Opcodes.DUP));
            fastMeta.add(new LdcInsnNode("The zip file has closed!"));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
            fastMeta.add(new InsnNode(Opcodes.ATHROW));

            fastMeta.add(chOkLabel);
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, metaSlot));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, metaInternal, "fs", "()Ljava/nio/file/FileSystem;", false));
            fastMeta.add(new VarInsnNode(Opcodes.ASTORE, fsSlot));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, fsSlot));
            fastMeta.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/util/function/Predicate"));
            fastMeta.add(new JumpInsnNode(Opcodes.IFEQ, slowToReal));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, fsSlot));
            fastMeta.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Predicate"));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, relStrSlot));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true));
            fastMeta.add(new JumpInsnNode(Opcodes.IFEQ, loopNextLabel));

            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, fsSlot));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, relStrSlot));
            fastMeta.add(new InsnNode(Opcodes.ICONST_0));
            fastMeta.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/nio/file/FileSystem", "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;", false));
            fastMeta.add(new VarInsnNode(Opcodes.ASTORE, 4));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 4));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastMeta.add(new FieldInsnNode(
                    Opcodes.GETFIELD, UNION_CLS, "notExistingPath", "Lcpw/mods/niofs/union/UnionPath;"));
            fastMeta.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, loopNextLabel));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 4));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 3));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, UNION_CLS, "testFilter", "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Z", false));
            fastMeta.add(new JumpInsnNode(Opcodes.IFEQ, loopNextLabel));
            fastMeta.add(new VarInsnNode(Opcodes.ALOAD, 4));
            fastMeta.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
            fastMeta.add(new InsnNode(Opcodes.ARETURN));
            fastMeta.add(slowToReal);
            mn.instructions.insertBefore(toRealStart, fastMeta);
        }
        return true;
    }

    public static byte[] transformFmlScanner(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"fileVisitor".equals(mn.name)
                    || !"(Ljava/nio/file/Path;Lnet/minecraftforge/forgespi/language/ModFileScanData;)V".equals(mn.desc)) {
                continue;
            }
            MethodInsnNode newStreamCall = null;
            MethodInsnNode readerInit = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (newStreamCall == null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(min.owner)
                        && "newInputStream".equals(min.name)) {
                    newStreamCall = min;
                } else if (readerInit == null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "org/objectweb/asm/ClassReader".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "(Ljava/io/InputStream;)V".equals(min.desc)) {
                    readerInit = min;
                }
            }
            if (newStreamCall != null && readerInit != null) {
                AbstractInsnNode s3 = prevReal(newStreamCall); // ANEWARRAY OpenOption
                AbstractInsnNode s2 = prevReal(s3);            // ICONST_0
                AbstractInsnNode s1 = prevReal(s2);            // ALOAD 1
                AbstractInsnNode loadIs = prevReal(readerInit); // ALOAD 3
                if (s1 instanceof VarInsnNode v1 && v1.var == 1
                        && s2 != null && s2.getOpcode() == Opcodes.ICONST_0
                        && s3 instanceof TypeInsnNode
                        && loadIs instanceof VarInsnNode v3 && v3.getOpcode() == Opcodes.ALOAD && v3.var == 3) {
                    int lenSlot = 6;
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof VarInsnNode v && v.var + 2 > lenSlot) {
                            lenSlot = v.var + 2;
                        }
                    }
                    InsnList allocLen = new InsnList();
                    allocLen.add(new InsnNode(Opcodes.ICONST_1));
                    allocLen.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
                    allocLen.add(new VarInsnNode(Opcodes.ASTORE, lenSlot));
                    mn.instructions.insert(allocLen);

                    mn.instructions.insertBefore(s1, new InsnNode(Opcodes.ACONST_NULL));
                    mn.instructions.remove(s1);
                    mn.instructions.remove(s2);
                    mn.instructions.remove(s3);
                    mn.instructions.remove(newStreamCall);

                    InsnList loadBytes = new InsnList();
                    loadBytes.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    loadBytes.add(new VarInsnNode(Opcodes.ALOAD, lenSlot));
                    loadBytes.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "readScanClassBytes",
                            "(Ljava/nio/file/Path;[I)[B", false));
                    loadBytes.add(new InsnNode(Opcodes.ICONST_0));
                    loadBytes.add(new VarInsnNode(Opcodes.ALOAD, lenSlot));
                    loadBytes.add(new InsnNode(Opcodes.ICONST_0));
                    loadBytes.add(new InsnNode(Opcodes.IALOAD));
                    mn.instructions.insertBefore(loadIs, loadBytes);
                    mn.instructions.remove(loadIs);
                    readerInit.desc = "([BII)V";
                    patched = true;
                }
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformBackgroundScanHandler(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (FieldNode fn : cn.fields) {
            if ("status".equals(fn.name)) {
                fn.access |= Opcodes.ACC_VOLATILE;
                patched = true;
            }
        }
        for (MethodNode mn : cn.methods) {
            if ("addCompletedFile".equals(mn.name)) {
                mn.access |= Opcodes.ACC_SYNCHRONIZED;
                patched = true;
            }
            if (!"<init>".equals(mn.name)) {
                continue;
            }
            for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                AbstractInsnNode insn = it.next();
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/util/concurrent/Executors".equals(min.owner)
                        && "newSingleThreadExecutor".equals(min.name)
                        && "(Ljava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;".equals(min.desc)) {
                    InsnList pool = new InsnList();
                    pool.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false));
                    pool.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "availableProcessors", "()I", false));
                    pool.add(new InsnNode(Opcodes.ICONST_2));
                    pool.add(new InsnNode(Opcodes.IDIV));
                    pool.add(new IntInsnNode(Opcodes.BIPUSH, 8));
                    pool.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false));
                    pool.add(new InsnNode(Opcodes.ICONST_4));
                    pool.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
                    pool.add(new InsnNode(Opcodes.SWAP));
                    mn.instructions.insertBefore(min, pool);
                    min.name = "newFixedThreadPool";
                    min.desc = "(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;";
                    patched = true;
                } else if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.PUTFIELD
                        && SCAN_CLS.equals(fin.owner)
                        && ("scannedFiles".equals(fin.name)
                                || "pendingFiles".equals(fin.name)
                                || "allFiles".equals(fin.name))) {
                    mn.instructions.insertBefore(fin, new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/util/Collections",
                            "synchronizedList",
                            "(Ljava/util/List;)Ljava/util/List;",
                            false));
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformMixinBytecode(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        MethodNode target = null;
        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if ("getOpcodeName".equals(mn.name) && "(ILjava/lang/String;I)Ljava/lang/String;".equals(mn.desc)) {
                target = mn;
            } else if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
            }
        }
        if (target == null) {
            return null;
        }

        final String cacheField = "SUNLIT_OPCODE_CACHE";
        final String cacheDesc = "Ljava/util/concurrent/atomic/AtomicReferenceArray;";
        final String cacheOwner = "java/util/concurrent/atomic/AtomicReferenceArray";
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                cacheField,
                cacheDesc,
                null,
                null));

        InsnList initCache = new InsnList();
        initCache.add(new TypeInsnNode(Opcodes.NEW, cacheOwner));
        initCache.add(new InsnNode(Opcodes.DUP));
        initCache.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        initCache.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cacheOwner, "<init>", "(I)V", false));
        initCache.add(new FieldInsnNode(Opcodes.PUTSTATIC, MIXIN_BYTECODE_CLS, cacheField, cacheDesc));
        if (clinit != null) {
            clinit.instructions.insert(initCache);
        } else {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(initCache);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(clinit);
        }

        // Fast lookup at method entry when opcode in [0, 256), startName == "UNINITIALIZED_THIS", startOrdinal == 1.
        LabelNode missLabel = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new VarInsnNode(Opcodes.ILOAD, 0));
        entry.add(new JumpInsnNode(Opcodes.IFLT, missLabel));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 0));
        entry.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        entry.add(new JumpInsnNode(Opcodes.IF_ICMPGE, missLabel));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 2));
        entry.add(new InsnNode(Opcodes.ICONST_1));
        entry.add(new JumpInsnNode(Opcodes.IF_ICMPNE, missLabel));
        entry.add(new LdcInsnNode("UNINITIALIZED_THIS"));
        entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        entry.add(new JumpInsnNode(Opcodes.IFEQ, missLabel));
        entry.add(new FieldInsnNode(Opcodes.GETSTATIC, MIXIN_BYTECODE_CLS, cacheField, cacheDesc));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 0));
        entry.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cacheOwner, "get", "(I)Ljava/lang/Object;", false));
        entry.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        entry.add(new InsnNode(Opcodes.DUP));
        entry.add(new JumpInsnNode(Opcodes.IFNONNULL, createReturnHit(target)));
        entry.add(new InsnNode(Opcodes.POP));
        entry.add(missLabel);

        // Cache populate right before every ARETURN in the slow path.
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                LabelNode skipStore = new LabelNode();
                InsnList store = new InsnList();
                store.add(new VarInsnNode(Opcodes.ILOAD, 0));
                store.add(new JumpInsnNode(Opcodes.IFLT, skipStore));
                store.add(new VarInsnNode(Opcodes.ILOAD, 0));
                store.add(new IntInsnNode(Opcodes.SIPUSH, 256));
                store.add(new JumpInsnNode(Opcodes.IF_ICMPGE, skipStore));
                store.add(new VarInsnNode(Opcodes.ILOAD, 2));
                store.add(new InsnNode(Opcodes.ICONST_1));
                store.add(new JumpInsnNode(Opcodes.IF_ICMPNE, skipStore));
                store.add(new LdcInsnNode("UNINITIALIZED_THIS"));
                store.add(new VarInsnNode(Opcodes.ALOAD, 1));
                store.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                store.add(new JumpInsnNode(Opcodes.IFEQ, skipStore));
                store.add(new InsnNode(Opcodes.DUP));
                store.add(new FieldInsnNode(Opcodes.GETSTATIC, MIXIN_BYTECODE_CLS, cacheField, cacheDesc));
                store.add(new InsnNode(Opcodes.SWAP));
                store.add(new VarInsnNode(Opcodes.ILOAD, 0));
                store.add(new InsnNode(Opcodes.SWAP));
                store.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, cacheOwner, "set", "(ILjava/lang/Object;)V", false));
                store.add(skipStore);
                target.instructions.insertBefore(insn, store);
            }
        }
        target.instructions.insert(entry);
        return writeNodeWithFrames(cn, loader);
    }

    private static LabelNode createReturnHit(MethodNode mn) {
        LabelNode hitLabel = new LabelNode();
        mn.instructions.add(hitLabel);
        mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        return hitLabel;
    }

    public static byte[] transformMixinLaunchPlugin(byte[] bytes) {
        return transformMixinLaunchPlugin(null, bytes);
    }

    public static byte[] transformMixinLaunchPlugin(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        boolean patchedGetNode = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof TypeInsnNode tin
                            && tin.getOpcode() == Opcodes.NEW
                            && "java/util/ArrayList".equals(tin.desc)) {
                        tin.desc = "java/util/concurrent/CopyOnWriteArrayList";
                        patched = true;
                    } else if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/util/ArrayList".equals(min.owner)
                            && "<init>".equals(min.name)) {
                        min.owner = "java/util/concurrent/CopyOnWriteArrayList";
                        patched = true;
                    }
                }
            } else if ("handlesClass".equals(mn.name) || "processClass".equals(mn.name)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.MONITORENTER || insn.getOpcode() == Opcodes.MONITOREXIT) {
                        it.set(new InsnNode(Opcodes.POP));
                        patched = true;
                    }
                }
            } else if ("getClassNode".equals(mn.name)
                    && "(Ljava/lang/String;Z)Lorg/objectweb/asm/tree/ClassNode;".equals(mn.desc)) {
                IntInsnNode bipush8 = null;
                int maxVar = 12;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                        maxVar = v.var + 2;
                    } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                        maxVar = i.var + 2;
                    }
                    if (bipush8 == null && insn instanceof IntInsnNode iin
                            && iin.getOpcode() == Opcodes.BIPUSH && iin.operand == 8) {
                        AbstractInsnNode next = nextReal(iin);
                        if (next instanceof MethodInsnNode min
                                && "accept".equals(min.name)
                                && "(Lorg/objectweb/asm/ClassVisitor;I)V".equals(min.desc)) {
                            bipush8 = iin;
                        }
                    }
                }
                if (bipush8 != null) {
                    int fastSlot = maxVar;
                    InsnList head = new InsnList();
                    head.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "consumeFastClassInfo", "()Z", false));
                    head.add(new VarInsnNode(Opcodes.ISTORE, fastSlot));
                    mn.instructions.insert(head);

                    LabelNode normalFlags = new LabelNode();
                    LabelNode afterFlags = new LabelNode();
                    InsnList chooseFlags = new InsnList();
                    chooseFlags.add(new VarInsnNode(Opcodes.ILOAD, fastSlot));
                    chooseFlags.add(new JumpInsnNode(Opcodes.IFEQ, normalFlags));
                    chooseFlags.add(new IntInsnNode(Opcodes.BIPUSH, 7)); // SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES
                    chooseFlags.add(new JumpInsnNode(Opcodes.GOTO, afterFlags));
                    chooseFlags.add(normalFlags);
                    chooseFlags.add(new IntInsnNode(Opcodes.BIPUSH, 8)); // EXPAND_FRAMES
                    chooseFlags.add(afterFlags);
                    mn.instructions.insertBefore(bipush8, chooseFlags);
                    mn.instructions.remove(bipush8);
                    patched = true;
                    patchedGetNode = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        return patchedGetNode ? writeNodeWithFrames(cn, loader) : writeNodeMaxs(cn);
    }

    public static byte[] transformMixinClassTracker(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patchedInit = false;
        boolean patchedHandles = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "()V".equals(mn.desc)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn instanceof TypeInsnNode tin
                            && tin.getOpcode() == Opcodes.NEW
                            && "java/util/HashSet".equals(tin.desc)) {
                        AbstractInsnNode n1 = nextReal(tin);
                        AbstractInsnNode n2 = nextReal(n1);
                        if (n1 != null && n1.getOpcode() == Opcodes.DUP
                                && n2 instanceof MethodInsnNode min
                                && min.getOpcode() == Opcodes.INVOKESPECIAL
                                && "java/util/HashSet".equals(min.owner)
                                && "<init>".equals(min.name)) {
                            mn.instructions.insertBefore(tin, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    "java/util/concurrent/ConcurrentHashMap",
                                    "newKeySet",
                                    "()Ljava/util/concurrent/ConcurrentHashMap$KeySetView;",
                                    false));
                            mn.instructions.remove(tin);
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            patchedInit = true;
                            it = mn.instructions.iterator();
                        }
                    }
                }
            } else if ("registerInvalidClass".equals(mn.name)
                    || "isClassLoaded".equals(mn.name)
                    || "processClass".equals(mn.name)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.MONITORENTER || insn.getOpcode() == Opcodes.MONITOREXIT) {
                        it.set(new InsnNode(Opcodes.POP));
                    }
                }
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
            } else if ("handlesClass".equals(mn.name)
                    && "(Lorg/objectweb/asm/Type;ZLjava/lang/String;)Ljava/util/EnumSet;".equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode notInvalid = new LabelNode();
                LabelNode notLoading = new LabelNode();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/Type", "getClassName", "()Ljava/lang/String;", false));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 4));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_TRACKER_CLS, "invalidClasses", "Ljava/util/Set;"));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", true));
                LabelNode popNotInvalid = new LabelNode();
                fast.add(new JumpInsnNode(Opcodes.IFNE, popNotInvalid));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, notInvalid));
                fast.add(new TypeInsnNode(Opcodes.NEW, "java/lang/NoClassDefFoundError"));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/NoClassDefFoundError", "<init>", "(Ljava/lang/String;)V", false));
                fast.add(new InsnNode(Opcodes.ATHROW));
                fast.add(popNotInvalid);
                fast.add(new InsnNode(Opcodes.POP));
                fast.add(notInvalid);
                fast.add(new LdcInsnNode("classloading"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, notLoading));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_TRACKER_CLS, "loadedClasses", "Ljava/util/Set;"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
                fast.add(new InsnNode(Opcodes.POP));
                fast.add(notLoading);
                fast.add(new InsnNode(Opcodes.ACONST_NULL));
                fast.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = fast;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patchedHandles = true;
            }
        }
        return (patchedInit && patchedHandles) ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformClassInfo(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patchedForName = false;
        boolean patchedFind = false;
        for (FieldNode fn : cn.fields) {
            if ("sunlitMethodIdx".equals(fn.name)) {
                patchedFind = true;
                break;
            }
        }
        if (!patchedFind) {
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
                    "sunlitMethodIdx",
                    "[Ljava/lang/Object;",
                    null,
                    null));
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_VOLATILE,
                    "sunlitFieldIdx",
                    "[Ljava/lang/Object;",
                    null,
                    null));
        }

        for (MethodNode mn : cn.methods) {
            if ("forName".equals(mn.name)
                    && "(Ljava/lang/String;)Lorg/spongepowered/asm/mixin/transformer/ClassInfo;".equals(mn.desc)) {
                MethodInsnNode getServiceCall = null;
                VarInsnNode astoreAfterGetNode = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (getServiceCall == null && insn instanceof MethodInsnNode min
                            && "org/spongepowered/asm/service/MixinService".equals(min.owner)
                            && "getService".equals(min.name)) {
                        getServiceCall = min;
                    } else if (getServiceCall != null && astoreAfterGetNode == null
                            && insn instanceof MethodInsnNode min
                            && "org/spongepowered/asm/service/IClassBytecodeProvider".equals(min.owner)
                            && "getClassNode".equals(min.name)) {
                        AbstractInsnNode next = nextReal(min);
                        if (next instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                            astoreAfterGetNode = vin;
                        }
                    }
                }
                if (getServiceCall == null || astoreAfterGetNode == null || mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) {
                    continue;
                }
                mn.instructions.insertBefore(
                        getServiceCall,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "enterFastClassInfo", "()V", false));
                mn.instructions.insert(
                        astoreAfterGetNode,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "exitFastClassInfo", "()V", false));
                for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                    if (tcb.handler != null) {
                        AbstractInsnNode firstInHandler = nextReal(tcb.handler);
                        if (firstInHandler instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                            mn.instructions.insert(
                                    vin,
                                    new MethodInsnNode(Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "exitFastClassInfo", "()V", false));
                        }
                    }
                }
                patchedForName = true;
            } else if (!patchedFind
                    && "findMember".equals(mn.name)
                    && "(Ljava/lang/String;Ljava/lang/String;ILorg/spongepowered/asm/mixin/transformer/ClassInfo$Member$Type;)Lorg/spongepowered/asm/mixin/transformer/ClassInfo$Member;".equals(mn.desc)) {
                int maxVar = 10;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                        maxVar = v.var + 2;
                    } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                        maxVar = i.var + 2;
                    }
                }
                int isMethodSlot = maxVar;
                int setSlot = maxVar + 1;
                int sizeSlot = maxVar + 2;
                int idxSlot = maxVar + 3;

                LabelNode origHead = new LabelNode();
                LabelNode fieldSetLabel = new LabelNode();
                LabelNode afterSetLabel = new LabelNode();
                LabelNode getFieldIdxLabel = new LabelNode();
                LabelNode afterIdxLabel = new LabelNode();
                LabelNode rebuildLabel = new LabelNode();
                LabelNode storeFieldIdxLabel = new LabelNode();
                LabelNode lookupLabel = new LabelNode();

                InsnList fast = new InsnList();
                // isMethod = (type == Member$Type.METHOD)
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "org/spongepowered/asm/mixin/transformer/ClassInfo$Member$Type",
                        "METHOD",
                        "Lorg/spongepowered/asm/mixin/transformer/ClassInfo$Member$Type;"));
                LabelNode notMethodCmp = new LabelNode();
                LabelNode afterIsMethod = new LabelNode();
                fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notMethodCmp));
                fast.add(new InsnNode(Opcodes.ICONST_1));
                fast.add(new JumpInsnNode(Opcodes.GOTO, afterIsMethod));
                fast.add(notMethodCmp);
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(afterIsMethod);
                fast.add(new VarInsnNode(Opcodes.ISTORE, isMethodSlot));

                // set = isMethod ? this.methods : this.fields
                fast.add(new VarInsnNode(Opcodes.ILOAD, isMethodSlot));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, fieldSetLabel));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_CLS, "methods", "Ljava/util/Set;"));
                fast.add(new JumpInsnNode(Opcodes.GOTO, afterSetLabel));
                fast.add(fieldSetLabel);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_CLS, "fields", "Ljava/util/Set;"));
                fast.add(afterSetLabel);
                fast.add(new VarInsnNode(Opcodes.ASTORE, setSlot));

                // if (set.size() < 8) -> origHead
                fast.add(new VarInsnNode(Opcodes.ALOAD, setSlot));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "size", "()I", true));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new VarInsnNode(Opcodes.ISTORE, sizeSlot));
                fast.add(new IntInsnNode(Opcodes.BIPUSH, 8));
                fast.add(new JumpInsnNode(Opcodes.IF_ICMPLT, origHead));

                // idx = isMethod ? this.sunlitMethodIdx : this.sunlitFieldIdx
                fast.add(new VarInsnNode(Opcodes.ILOAD, isMethodSlot));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, getFieldIdxLabel));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_CLS, "sunlitMethodIdx", "[Ljava/lang/Object;"));
                fast.add(new JumpInsnNode(Opcodes.GOTO, afterIdxLabel));
                fast.add(getFieldIdxLabel);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_CLS, "sunlitFieldIdx", "[Ljava/lang/Object;"));
                fast.add(afterIdxLabel);
                fast.add(new VarInsnNode(Opcodes.ASTORE, idxSlot));

                // if (idx == null || ((Integer) idx[0]).intValue() != size) -> rebuild
                fast.add(new VarInsnNode(Opcodes.ALOAD, idxSlot));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, rebuildLabel));
                fast.add(new VarInsnNode(Opcodes.ALOAD, idxSlot));
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new InsnNode(Opcodes.AALOAD));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                fast.add(new VarInsnNode(Opcodes.ILOAD, sizeSlot));
                fast.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lookupLabel));

                // rebuild
                fast.add(rebuildLabel);
                fast.add(new VarInsnNode(Opcodes.ALOAD, setSlot));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        MIXIN_CLASS_INFO_CLS,
                        "sunlitBuildMemberIndex",
                        "(Ljava/util/Set;)[Ljava/lang/Object;",
                        false));
                fast.add(new VarInsnNode(Opcodes.ASTORE, idxSlot));
                fast.add(new VarInsnNode(Opcodes.ILOAD, isMethodSlot));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, storeFieldIdxLabel));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new VarInsnNode(Opcodes.ALOAD, idxSlot));
                fast.add(new FieldInsnNode(Opcodes.PUTFIELD, MIXIN_CLASS_INFO_CLS, "sunlitMethodIdx", "[Ljava/lang/Object;"));
                fast.add(new JumpInsnNode(Opcodes.GOTO, lookupLabel));
                fast.add(storeFieldIdxLabel);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new VarInsnNode(Opcodes.ALOAD, idxSlot));
                fast.add(new FieldInsnNode(Opcodes.PUTFIELD, MIXIN_CLASS_INFO_CLS, "sunlitFieldIdx", "[Ljava/lang/Object;"));

                // lookup and return
                fast.add(lookupLabel);
                fast.add(new VarInsnNode(Opcodes.ALOAD, idxSlot));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_CLS, "initialisers", "Ljava/util/Set;"));
                fast.add(new VarInsnNode(Opcodes.ILOAD, isMethodSlot));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
                fast.add(new VarInsnNode(Opcodes.ILOAD, 3));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        MIXIN_CLASS_INFO_CLS,
                        "sunlitLookupIndexedMember",
                        "([Ljava/lang/Object;Ljava/util/Set;ZLjava/lang/String;Ljava/lang/String;I)Lorg/spongepowered/asm/mixin/transformer/ClassInfo$Member;",
                        false));
                fast.add(new InsnNode(Opcodes.ARETURN));
                fast.add(origHead);
                mn.instructions.insert(fast);
                patchedFind = true;
            }
        }
        if (patchedFind && !hasMethod(cn, "sunlitBuildMemberIndex")) {
            ClassNode payloadCn = loadPayloadClass(MIXIN_PAYLOAD_RES);
            cn.methods.add(copyPayloadMethod(
                    findMethod(payloadCn, "sunlitBuildMemberIndex", "(Ljava/util/Set;)[Ljava/lang/Object;"),
                    "sunlitBuildMemberIndex",
                    MIXIN_PAYLOAD_CLS,
                    MIXIN_CLASS_INFO_CLS));
            cn.methods.add(copyPayloadMethod(
                    findMethod(payloadCn, "sunlitLookupIndexedMember",
                            "([Ljava/lang/Object;Ljava/util/Set;ZLjava/lang/String;Ljava/lang/String;I)Lorg/spongepowered/asm/mixin/transformer/ClassInfo$Member;"),
                    "sunlitLookupIndexedMember",
                    MIXIN_PAYLOAD_CLS,
                    MIXIN_CLASS_INFO_CLS));
        }
        return (patchedForName || patchedFind) ? writeNodeWithFrames(cn, loader) : null;
    }

    private static boolean hasMethod(ClassNode cn, String name) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name)) {
                return true;
            }
        }
        return false;
    }

    public static byte[] transformClassInfoMethod(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (MethodNode mn : cn.methods) {
            if ("sunlitEnsureFrames".equals(mn.name)) {
                return null;
            }
        }
        for (FieldNode fn : cn.fields) {
            if ("frames".equals(fn.name)) {
                fn.access &= ~Opcodes.ACC_FINAL;
            }
        }
        boolean patchedGather = false;
        boolean patchedGet = false;
        for (MethodNode mn : cn.methods) {
            if ("gatherFrames".equals(mn.name)
                    && "(Lorg/objectweb/asm/tree/MethodNode;)Ljava/util/List;".equals(mn.desc)) {
                // Return null when instructions.size() == 0 on a concrete (non-abstract, non-native) method.
                LabelNode hasCode = new LabelNode();
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new FieldInsnNode(
                        Opcodes.GETFIELD, "org/objectweb/asm/tree/MethodNode", "instructions", "Lorg/objectweb/asm/tree/InsnList;"));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/tree/InsnList", "size", "()I", false));
                guard.add(new JumpInsnNode(Opcodes.IFNE, hasCode));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new FieldInsnNode(
                        Opcodes.GETFIELD, "org/objectweb/asm/tree/MethodNode", "access", "I"));
                guard.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE));
                guard.add(new InsnNode(Opcodes.IAND));
                guard.add(new JumpInsnNode(Opcodes.IFNE, hasCode));
                guard.add(new InsnNode(Opcodes.ACONST_NULL));
                guard.add(new InsnNode(Opcodes.ARETURN));
                guard.add(hasCode);
                mn.instructions.insert(guard);
                patchedGather = true;
            } else if ("getFrames".equals(mn.name) && "()Ljava/util/List;".equals(mn.desc)) {
                InsnList lazyGet = new InsnList();
                LabelNode nonNull = new LabelNode();
                lazyGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
                lazyGet.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_CLASS_INFO_METHOD_CLS, "frames", "Ljava/util/List;"));
                lazyGet.add(new InsnNode(Opcodes.DUP));
                lazyGet.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNull));
                lazyGet.add(new InsnNode(Opcodes.POP));
                lazyGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
                lazyGet.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, MIXIN_CLASS_INFO_METHOD_CLS, "sunlitEnsureFrames", "()Ljava/util/List;", false));
                lazyGet.add(nonNull);
                lazyGet.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = lazyGet;
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patchedGet = true;
            }
        }
        if (!patchedGather || !patchedGet) {
            return null;
        }
        MethodNode ensure = new MethodNode(
                Opcodes.ACC_PRIVATE, "sunlitEnsureFrames", "()Ljava/util/List;", null, null);
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();
        LabelNode loopStart = new LabelNode();
        LabelNode loopNext = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        ensure.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/Throwable"));
        ensure.instructions.add(tryStart);
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/spongepowered/asm/service/MixinService",
                "getService",
                "()Lorg/spongepowered/asm/service/IMixinService;",
                false));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/spongepowered/asm/service/IMixinService",
                "getBytecodeProvider",
                "()Lorg/spongepowered/asm/service/IClassBytecodeProvider;",
                true));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, MIXIN_CLASS_INFO_METHOD_CLS, "this$0", "L" + MIXIN_CLASS_INFO_CLS + ";"));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, MIXIN_CLASS_INFO_CLS, "getName", "()Ljava/lang/String;", false));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "org/spongepowered/asm/service/IClassBytecodeProvider",
                "getClassNode",
                "(Ljava/lang/String;)Lorg/objectweb/asm/tree/ClassNode;",
                true));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, "org/objectweb/asm/tree/ClassNode", "methods", "Ljava/util/List;"));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true));
        ensure.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        ensure.instructions.add(loopStart);
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        ensure.instructions.add(new JumpInsnNode(Opcodes.IFEQ, loopEnd));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        ensure.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/objectweb/asm/tree/MethodNode"));
        ensure.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, "org/objectweb/asm/tree/MethodNode", "name", "Ljava/lang/String;"));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, "org/objectweb/asm/tree/MethodNode", "desc", "Ljava/lang/String;"));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, MIXIN_CLASS_INFO_METHOD_CLS, "equals", "(Ljava/lang/String;Ljava/lang/String;)Z", false));
        ensure.instructions.add(new JumpInsnNode(Opcodes.IFEQ, loopNext));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                MIXIN_CLASS_INFO_METHOD_CLS,
                "gatherFrames",
                "(Lorg/objectweb/asm/tree/MethodNode;)Ljava/util/List;",
                false));
        ensure.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ensure.instructions.add(new JumpInsnNode(Opcodes.IFNULL, loopEnd));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, MIXIN_CLASS_INFO_METHOD_CLS, "frames", "Ljava/util/List;"));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        ensure.instructions.add(tryEnd);
        ensure.instructions.add(new InsnNode(Opcodes.ARETURN));
        ensure.instructions.add(loopNext);
        ensure.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        ensure.instructions.add(catchHandler);
        ensure.instructions.add(new InsnNode(Opcodes.POP));
        ensure.instructions.add(loopEnd);
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
        ensure.instructions.add(new InsnNode(Opcodes.DUP));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new InsnNode(Opcodes.SWAP));
        ensure.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, MIXIN_CLASS_INFO_METHOD_CLS, "frames", "Ljava/util/List;"));
        ensure.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(ensure);
        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformAbstractJarDepLocator(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"loadResourceFromModFile".equals(mn.name)
                    || !"(Lnet/minecraftforge/forgespi/locating/IModFile;Ljava/nio/file/Path;)Ljava/util/Optional;".equals(mn.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && "net/minecraftforge/forgespi/locating/IModFile".equals(min.owner)
                        && "findResource".equals(min.name)) {
                    LabelNode existsLabel = new LabelNode();
                    LabelNode missingLabel = new LabelNode();
                    InsnList checkExists = new InsnList();
                    checkExists.add(new InsnNode(Opcodes.DUP));
                    checkExists.add(new JumpInsnNode(Opcodes.IFNULL, missingLabel));
                    checkExists.add(new InsnNode(Opcodes.DUP));
                    checkExists.add(new InsnNode(Opcodes.ICONST_0));
                    checkExists.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/LinkOption"));
                    checkExists.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/nio/file/Files",
                            "notExists",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z",
                            false));
                    checkExists.add(new JumpInsnNode(Opcodes.IFEQ, existsLabel));
                    checkExists.add(missingLabel);
                    checkExists.add(new InsnNode(Opcodes.POP));
                    checkExists.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
                    checkExists.add(new InsnNode(Opcodes.ARETURN));
                    checkExists.add(existsLabel);
                    mn.instructions.insert(min, checkExists);
                    patched = true;
                    break;
                }
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformResolver(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        ClassNode payloadCn = null;
        for (MethodNode mn : cn.methods) {
            if ("checkExportSuppliers".equals(mn.name) && "(Ljava/util/Map;)V".equals(mn.desc)) {
                int maxVar = 20;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                        maxVar = v.var + 2;
                    } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                        maxVar = i.var + 2;
                    }
                }
                int okSlot = maxVar;
                int extraLocalBase = maxVar + 1;
                if (payloadCn == null) {
                    payloadCn = loadPayloadClass(RESOLVER_PAYLOAD_RES);
                }
                MethodNode fastSrc = findMethod(payloadCn, "fastCheckExportSuppliers", "(Ljava/util/Map;[Z)V");
                LabelNode afterCheck = new LabelNode();
                LabelNode fallback = new LabelNode();
                InsnList fastGuard = new InsnList();
                fastGuard.add(new InsnNode(Opcodes.ICONST_1));
                fastGuard.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN));
                fastGuard.add(new VarInsnNode(Opcodes.ASTORE, okSlot));
                fastGuard.add(cloneStaticPayloadBody(fastSrc, new int[]{1, okSlot}, extraLocalBase, afterCheck, null, null));
                fastGuard.add(afterCheck);
                fastGuard.add(new VarInsnNode(Opcodes.ALOAD, okSlot));
                fastGuard.add(new InsnNode(Opcodes.ICONST_0));
                fastGuard.add(new InsnNode(Opcodes.BALOAD));
                fastGuard.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
                fastGuard.add(new InsnNode(Opcodes.RETURN));
                fastGuard.add(fallback);
                mn.instructions.insert(fastGuard);
                patched = true;
                continue;
            }
            if (!"makeGraph".equals(mn.name) || !"(Ljava/lang/module/Configuration;)Ljava/util/Map;".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode newListStart = null;
            AbstractInsnNode mapInitStore = null;
            AbstractInsnNode m1Store = null;
            LabelNode loopHeaderLabel = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (mapInitStore == null
                        && insn instanceof VarInsnNode v
                        && v.getOpcode() == Opcodes.ASTORE
                        && v.var == 5
                        && prevReal(v) instanceof MethodInsnNode min
                        && "java/util/HashMap".equals(min.owner)
                        && "<init>".equals(min.name)) {
                    mapInitStore = v;
                }
                if (m1Store == null
                        && insn instanceof VarInsnNode v
                        && v.getOpcode() == Opcodes.ASTORE
                        && v.var == 10
                        && prevReal(v) instanceof MethodInsnNode min
                        && "computeIfAbsent".equals(min.name)) {
                    m1Store = v;
                }
                if (newListStart == null
                        && insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && "java/util/ArrayList".equals(tin.desc)) {
                    AbstractInsnNode n1 = nextReal(tin);
                    AbstractInsnNode n2 = nextReal(n1);
                    AbstractInsnNode n3 = nextReal(n2);
                    AbstractInsnNode n4 = nextReal(n3);
                    AbstractInsnNode n5 = nextReal(n4);
                    if (n1 != null && n1.getOpcode() == Opcodes.DUP
                            && n2 instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/util/ArrayList".equals(min.owner) && "<init>".equals(min.name)
                            && n3 instanceof VarInsnNode v3 && v3.getOpcode() == Opcodes.ASTORE && v3.var == 7
                            && n4 != null && n4.getOpcode() == Opcodes.ICONST_0
                            && n5 instanceof VarInsnNode v5 && v5.getOpcode() == Opcodes.ISTORE && v5.var == 6) {
                        AbstractInsnNode prevGoto = prevReal(tin);
                        if (prevGoto instanceof JumpInsnNode jin && jin.getOpcode() == Opcodes.GOTO) {
                            loopHeaderLabel = jin.label;
                        }
                        newListStart = tin;
                    }
                }
            }
            if (newListStart == null) {
                continue;
            }
            AbstractInsnNode tailRet = mn.instructions.getLast();
            while (tailRet != null && tailRet.getOpcode() != Opcodes.ARETURN) {
                tailRet = tailRet.getPrevious();
            }
            AbstractInsnNode loadG1 = prevReal(tailRet);
            if (!(loadG1 instanceof VarInsnNode vin) || vin.getOpcode() != Opcodes.ALOAD || vin.var != 3) {
                continue;
            }

            int maxVar = 18;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                    maxVar = v.var + 2;
                } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                    maxVar = i.var + 2;
                }
            }

            if (payloadCn == null) {
                payloadCn = loadPayloadClass(RESOLVER_PAYLOAD_RES);
            }
            int autoD1Slot = maxVar;
            int autoD2Slot = maxVar + 1;
            int scratchBase = maxVar + 2;

            if (mapInitStore != null && m1Store != null && loopHeaderLabel != null) {
                MethodNode hasTwoM = findMethod(payloadCn, "hasTwoAutoModules", "(Ljava/util/Map;[Z)V");
                MethodNode collectParentsM = findMethod(payloadCn, "collectParentModules", "(Ljava/util/List;Ljava/util/Set;Ljava/util/Set;)V");
                if (hasTwoM != null && collectParentsM != null) {
                    int refMapSlot = scratchBase;
                    int flagSlot = scratchBase + 1;
                    int prepExtra = scratchBase + 2;
                    int prepItSlot = scratchBase;
                    int prepMrefSlot = scratchBase + 1;
                    int prepDescSlot = scratchBase + 2;
                    int prepRmSlot = scratchBase + 3;
                    int collectExtra = scratchBase + 4;

                    InsnList prep = new InsnList();
                    LabelNode afterTwoCheck = new LabelNode();
                    LabelNode skipAutoPrep = new LabelNode();
                    LabelNode prepLoopHead = new LabelNode();
                    LabelNode prepLoopExit = new LabelNode();
                    LabelNode notAutoPrep = new LabelNode();
                    LabelNode afterCollect = new LabelNode();

                    prep.add(new InsnNode(Opcodes.ACONST_NULL));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, autoD1Slot));
                    prep.add(new InsnNode(Opcodes.ACONST_NULL));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, autoD2Slot));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prep.add(new FieldInsnNode(
                            Opcodes.GETFIELD, RESOLVER_CLS, "nameToReference", "Ljava/util/Map;"));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, refMapSlot));
                    prep.add(new InsnNode(Opcodes.ICONST_1));
                    prep.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, flagSlot));
                    prep.add(cloneStaticPayloadBody(
                            hasTwoM, new int[]{refMapSlot, flagSlot}, prepExtra, afterTwoCheck, null, null));
                    prep.add(afterTwoCheck);
                    prep.add(new VarInsnNode(Opcodes.ALOAD, flagSlot));
                    prep.add(new InsnNode(Opcodes.ICONST_0));
                    prep.add(new InsnNode(Opcodes.BALOAD));
                    prep.add(new JumpInsnNode(Opcodes.IFEQ, skipAutoPrep));

                    // Build shared HashSet instances for all automatic modules once.
                    prep.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
                    prep.add(new InsnNode(Opcodes.DUP));
                    prep.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    prep.add(new InsnNode(Opcodes.ICONST_2));
                    prep.add(new InsnNode(Opcodes.IMUL));
                    prep.add(new IntInsnNode(Opcodes.BIPUSH, 32));
                    prep.add(new InsnNode(Opcodes.IADD));
                    prep.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "(I)V", false));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, autoD1Slot));

                    prep.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
                    prep.add(new InsnNode(Opcodes.DUP));
                    prep.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    prep.add(new InsnNode(Opcodes.ICONST_2));
                    prep.add(new InsnNode(Opcodes.IMUL));
                    prep.add(new IntInsnNode(Opcodes.BIPUSH, 32));
                    prep.add(new InsnNode(Opcodes.IADD));
                    prep.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "(I)V", false));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, autoD2Slot));

                    prep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prep.add(new FieldInsnNode(
                            Opcodes.GETFIELD, RESOLVER_CLS, "nameToReference", "Ljava/util/Map;"));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, prepItSlot));

                    prep.add(prepLoopHead);
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepItSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
                    prep.add(new JumpInsnNode(Opcodes.IFEQ, prepLoopExit));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepItSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
                    prep.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/module/ModuleReference"));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, prepMrefSlot));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepMrefSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/module/ModuleReference", "descriptor",
                            "()Ljava/lang/module/ModuleDescriptor;", false));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, prepDescSlot));

                    prep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, 5));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepDescSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/module/ModuleDescriptor", "name",
                            "()Ljava/lang/String;", false));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepMrefSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, RESOLVER_CLS, "computeIfAbsent",
                            "(Ljava/util/Map;Ljava/lang/String;Ljava/lang/module/Configuration;Ljava/lang/module/ModuleReference;)Ljava/lang/module/ResolvedModule;",
                            false));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, prepRmSlot));

                    prep.add(new VarInsnNode(Opcodes.ALOAD, autoD1Slot));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepRmSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
                    prep.add(new InsnNode(Opcodes.POP));

                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepDescSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/module/ModuleDescriptor", "isAutomatic", "()Z", false));
                    prep.add(new JumpInsnNode(Opcodes.IFEQ, notAutoPrep));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, autoD2Slot));
                    prep.add(new VarInsnNode(Opcodes.ALOAD, prepRmSlot));
                    prep.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
                    prep.add(new InsnNode(Opcodes.POP));
                    prep.add(notAutoPrep);
                    prep.add(new JumpInsnNode(Opcodes.GOTO, prepLoopHead));

                    prep.add(prepLoopExit);
                    int parentsArgSlot = refMapSlot;
                    prep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prep.add(new FieldInsnNode(
                            Opcodes.GETFIELD, RESOLVER_CLS, "parents", "Ljava/util/List;"));
                    prep.add(new VarInsnNode(Opcodes.ASTORE, parentsArgSlot));
                    prep.add(cloneStaticPayloadBody(
                            collectParentsM,
                            new int[]{parentsArgSlot, autoD1Slot, autoD2Slot},
                            collectExtra,
                            afterCollect,
                            null,
                            null));
                    prep.add(afterCollect);
                    prep.add(skipAutoPrep);
                    mn.instructions.insert(mapInitStore, prep);

                    InsnList autoFastAssign = new InsnList();
                    LabelNode needFreshSets = new LabelNode();
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, autoD1Slot));
                    autoFastAssign.add(new JumpInsnNode(Opcodes.IFNULL, needFreshSets));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, 8));
                    autoFastAssign.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/module/ModuleDescriptor", "isAutomatic", "()Z", false));
                    autoFastAssign.add(new JumpInsnNode(Opcodes.IFEQ, needFreshSets));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, 10));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, autoD1Slot));
                    autoFastAssign.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                    autoFastAssign.add(new InsnNode(Opcodes.POP));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, 4));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, 10));
                    autoFastAssign.add(new VarInsnNode(Opcodes.ALOAD, autoD2Slot));
                    autoFastAssign.add(new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                    autoFastAssign.add(new InsnNode(Opcodes.POP));
                    autoFastAssign.add(new JumpInsnNode(Opcodes.GOTO, loopHeaderLabel));
                    autoFastAssign.add(needFreshSets);
                    mn.instructions.insert(m1Store, autoFastAssign);
                }
            }

            MethodNode closeGraph = findMethod(payloadCn, "closeGraph", "(Ljava/util/Map;Ljava/util/Map;)V");
            LabelNode afterClose = new LabelNode();
            InsnList spliced = cloneStaticPayloadBody(closeGraph, new int[]{3, 4}, scratchBase, afterClose, null, null);
            spliced.add(afterClose);

            // Remove old tail loop [newListStart .. loadG1) and splice BitSet closure in its place.
            AbstractInsnNode cur = newListStart;
            mn.instructions.insertBefore(newListStart, spliced);
            while (cur != null && cur != loadG1) {
                AbstractInsnNode next = cur.getNext();
                mn.instructions.remove(cur);
                cur = next;
            }
            patched = true;
        }
        return patched ? writeNodeWithFrames(cn, loader != null ? loader : ClassLoader.getPlatformClassLoader()) : null;
    }

    public static byte[] transformMixinHandler(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (FieldNode fn : cn.fields) {
            if ("registry".equals(fn.name) || "transformer".equals(fn.name)) {
                fn.access |= Opcodes.ACC_VOLATILE;
                patched = true;
            }
        }
        for (MethodNode mn : cn.methods) {
            if (!"handlesClass".equals(mn.name)) {
                continue;
            }
            if ((mn.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                mn.access &= ~Opcodes.ACC_SYNCHRONIZED;
                patched = true;
            }
            if (!"(Lorg/objectweb/asm/Type;ZLjava/lang/String;)Ljava/util/EnumSet;".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode first = mn.instructions.getFirst();
            while (first != null && first.getOpcode() < 0) {
                first = first.getNext();
            }
            AbstractInsnNode second = nextReal(first);
            AbstractInsnNode third = nextReal(second);
            AbstractInsnNode fourth = nextReal(third);
            if (first instanceof VarInsnNode v0 && v0.getOpcode() == Opcodes.ILOAD && v0.var == 2
                    && second instanceof JumpInsnNode j1 && j1.getOpcode() == Opcodes.IFNE
                    && third instanceof FieldInsnNode f2 && f2.getOpcode() == Opcodes.GETSTATIC
                    && "AFTER_ONLY".equals(f2.name)
                    && fourth != null && fourth.getOpcode() == Opcodes.ARETURN) {
                InsnList fastPath = new InsnList();
                LabelNode retAfter = new LabelNode();
                LabelNode retNull = new LabelNode();

                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_HANDLER_CLS, "transformer", "Lorg/spongepowered/asm/mixin/transformer/IMixinTransformer;"));
                fastPath.add(new JumpInsnNode(Opcodes.IFNULL, retAfter));
                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_HANDLER_CLS, "registry", "Lorg/spongepowered/asm/service/ISyntheticClassRegistry;"));
                fastPath.add(new JumpInsnNode(Opcodes.IFNULL, retAfter));

                fastPath.add(new LdcInsnNode("mixin"));
                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fastPath.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                fastPath.add(new JumpInsnNode(Opcodes.IFNE, retNull));

                fastPath.add(new LdcInsnNode("computing_frames"));
                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fastPath.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                fastPath.add(new JumpInsnNode(Opcodes.IFNE, retNull));

                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fastPath.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MIXIN_HANDLER_CLS, "generatesClass", "(Lorg/objectweb/asm/Type;)Z", false));
                fastPath.add(new JumpInsnNode(Opcodes.IFNE, retAfter));

                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastPath.add(new FieldInsnNode(Opcodes.GETFIELD, MIXIN_HANDLER_CLS, "transformer", "Lorg/spongepowered/asm/mixin/transformer/IMixinTransformer;"));
                fastPath.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fastPath.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/Type", "getClassName", "()Ljava/lang/String;", false));
                fastPath.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MIXIN_PROCESSOR_CLS, "sunlitShouldSkip", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                fastPath.add(new JumpInsnNode(Opcodes.IFNE, retNull));

                fastPath.add(retAfter);
                fastPath.add(new FieldInsnNode(Opcodes.GETSTATIC, f2.owner, f2.name, f2.desc));
                fastPath.add(new InsnNode(Opcodes.ARETURN));
                fastPath.add(retNull);
                fastPath.add(new InsnNode(Opcodes.ACONST_NULL));
                fastPath.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.insertBefore(third, fastPath);
                mn.instructions.remove(third);
                mn.instructions.remove(fourth);
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformMixinProcessor(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        cn.access = makePublicAccess(cn.access);
        for (FieldNode fn : cn.fields) {
            if ("configs".equals(fn.name)
                    || "pendingConfigs".equals(fn.name)
                    || "coprocessors".equals(fn.name)
                    || "currentEnvironment".equals(fn.name)
                    || "errorState".equals(fn.name)) {
                fn.access = makePublicAccess(fn.access);
            }
            if ("SUNLIT_SNAPSHOT".equals(fn.name)) {
                return null;
            }
        }

        cn.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "SUNLIT_SNAPSHOT",
                "[Ljava/lang/Object;",
                null,
                null
        ));

        ClassNode payloadCn = loadPayloadClass(MIXIN_PAYLOAD_RES);
        for (MethodNode pmn : payloadCn.methods) {
            if ("<init>".equals(pmn.name) || "<clinit>".equals(pmn.name)) {
                continue;
            }
            MethodNode copy = new MethodNode(pmn.access, pmn.name, pmn.desc, null, null);
            copy.instructions = cloneStaticPayloadBody(pmn, null, 0, null, MIXIN_PAYLOAD_CLS, MIXIN_PROCESSOR_CLS);
            cn.methods.add(copy);
        }

        boolean hookedSelect = false;
        boolean hookedApply = false;
        for (MethodNode mn : cn.methods) {
            if ("select".equals(mn.name) && "(Lorg/spongepowered/asm/mixin/MixinEnvironment;)V".equals(mn.desc)) {
                InsnList clear = new InsnList();
                clear.add(new InsnNode(Opcodes.ACONST_NULL));
                clear.add(new FieldInsnNode(Opcodes.PUTSTATIC, MIXIN_PROCESSOR_CLS, "SUNLIT_SNAPSHOT", "[Ljava/lang/Object;"));
                mn.instructions.insert(clear);

                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList rebuild = new InsnList();
                        rebuild.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        rebuild.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MIXIN_PROCESSOR_CLS, "sunlitRebuildSnapshot", "(Lorg/spongepowered/asm/mixin/transformer/MixinProcessor;)V", false));
                        mn.instructions.insertBefore(insn, rebuild);
                    }
                }
                hookedSelect = true;
            } else if ("applyMixins".equals(mn.name)
                    && "(Lorg/spongepowered/asm/mixin/MixinEnvironment;Ljava/lang/String;Lorg/objectweb/asm/tree/ClassNode;)Z".equals(mn.desc)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.IRETURN) {
                        InsnList checkRebuild = new InsnList();
                        LabelNode skip = new LabelNode();
                        checkRebuild.add(new FieldInsnNode(Opcodes.GETSTATIC, MIXIN_PROCESSOR_CLS, "SUNLIT_SNAPSHOT", "[Ljava/lang/Object;"));
                        checkRebuild.add(new JumpInsnNode(Opcodes.IFNONNULL, skip));
                        checkRebuild.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        checkRebuild.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MIXIN_PROCESSOR_CLS, "sunlitRebuildSnapshot", "(Lorg/spongepowered/asm/mixin/transformer/MixinProcessor;)V", false));
                        checkRebuild.add(skip);
                        mn.instructions.insertBefore(insn, checkRebuild);
                    }
                }
                injectFastMixinLookup(mn);
                hookedApply = true;
            }
        }
        return (hookedSelect && hookedApply) ? writeNodeWithFrames(cn, loader) : null;
    }

    private static void injectFastMixinLookup(MethodNode mn) {
        MethodInsnNode pkgMatchCall = null;
        MethodInsnNode hasMixinsCall = null;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof MethodInsnNode min && MIXIN_CONFIG_CLS.equals(min.owner)) {
                if (pkgMatchCall == null
                        && "packageMatch".equals(min.name)
                        && "(Ljava/lang/String;)Z".equals(min.desc)) {
                    pkgMatchCall = min;
                } else if (hasMixinsCall == null
                        && "hasMixinsFor".equals(min.name)
                        && "(Ljava/lang/String;)Z".equals(min.desc)) {
                    hasMixinsCall = min;
                }
            }
        }
        if (pkgMatchCall == null || hasMixinsCall == null) {
            return;
        }
        // Locate `ACONST_NULL; ASTORE <matchedVar>` right before the packageMatch loop.
        AbstractInsnNode anchorStart = null;
        for (AbstractInsnNode cur = pkgMatchCall; cur != null; cur = cur.getPrevious()) {
            if (cur.getOpcode() == Opcodes.ASTORE) {
                AbstractInsnNode prev = prevReal(cur);
                if (prev != null && prev.getOpcode() == Opcodes.ACONST_NULL) {
                    anchorStart = prev;
                    break;
                }
            }
        }
        // Locate loop exit label `IFEQ <L507>` right before `hasMixinsCall`.
        LabelNode loopExit = null;
        for (AbstractInsnNode cur = hasMixinsCall; cur != null; cur = cur.getPrevious()) {
            if (cur instanceof JumpInsnNode jin && jin.getOpcode() == Opcodes.IFEQ) {
                AbstractInsnNode prev = prevReal(jin);
                if (prev instanceof MethodInsnNode iterCall
                        && "java/util/Iterator".equals(iterCall.owner)
                        && "hasNext".equals(iterCall.name)) {
                    loopExit = jin.label;
                    break;
                }
            }
        }
        if (anchorStart == null || loopExit == null) {
            return;
        }
        // Verify first real insns after `loopExit` load `mixins` (`ALOAD <slot>; IFNULL ...`).
        AbstractInsnNode postLoopAload = nextReal(loopExit);
        if (!(postLoopAload instanceof VarInsnNode vin) || vin.getOpcode() != Opcodes.ALOAD) {
            return;
        }
        AbstractInsnNode postIfNull = nextReal(postLoopAload);
        if (!(postIfNull instanceof JumpInsnNode) || postIfNull.getOpcode() != Opcodes.IFNULL) {
            return;
        }
        int mixinsSlot = vin.var;
        InsnList fast = new InsnList();
        LabelNode fallback = new LabelNode();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fast.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                MIXIN_PROCESSOR_CLS,
                "sunlitLookupSortedMixins",
                "(Lorg/spongepowered/asm/mixin/transformer/MixinProcessor;Lorg/spongepowered/asm/mixin/MixinEnvironment;Ljava/lang/String;)Ljava/lang/Object;",
                false));
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
        fast.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, fallback));
        fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/SortedSet"));
        fast.add(new VarInsnNode(Opcodes.ASTORE, mixinsSlot));
        fast.add(new JumpInsnNode(Opcodes.GOTO, loopExit));
        fast.add(fallback);
        fast.add(new InsnNode(Opcodes.POP));
        mn.instructions.insertBefore(anchorStart, fast);
    }

    public static byte[] transformPackCacheEngine(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patchedInit = false;
        boolean patchedValid = false;
        boolean patchedDecompose = false;
        boolean hasRun = false;

        for (MethodNode mn : cn.methods) {
            if ("run".equals(mn.name) && "()V".equals(mn.desc)) {
                hasRun = true;
            } else if ("<init>".equals(mn.name) && "(Ljava/util/function/Function;)V".equals(mn.desc)) {
                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext(); ) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList warmup = new InsnList();
                        warmup.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        warmup.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                JAR_PAYLOAD_CLS,
                                "submitPackCacheWarmup",
                                "(Ljava/lang/Runnable;)V",
                                false));
                        mn.instructions.insertBefore(insn, warmup);
                        patchedInit = true;
                    }
                }
            } else if ("isValidCachedResourcePath".equals(mn.name)
                    && "(Ljava/nio/file/Path;)Z".equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode fallback = new LabelNode();
                LabelNode retTrue = new LabelNode();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastCheckResourcePath",
                        "(Ljava/nio/file/Path;)I",
                        false));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new JumpInsnNode(Opcodes.IFLT, fallback));
                fast.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new InsnNode(Opcodes.IRETURN));
                fast.add(retTrue);
                fast.add(new InsnNode(Opcodes.ICONST_1));
                fast.add(new InsnNode(Opcodes.IRETURN));
                fast.add(fallback);
                fast.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(fast);
                patchedValid = true;
            } else if ("decomposeCached".equals(mn.name)
                    && "(Ljava/lang/String;)[Ljava/lang/String;".equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode miss = new LabelNode();
                fast.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        PACK_CACHE_CLS,
                        "CACHED_SPLIT_PATHS",
                        "Ljava/util/concurrent/ConcurrentHashMap;"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "java/util/concurrent/ConcurrentHashMap",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/String;"));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                fast.add(new InsnNode(Opcodes.ARETURN));
                fast.add(miss);
                fast.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(fast);
                patchedDecompose = true;
            }
        }

        if (!hasRun && patchedInit) {
            if (!cn.interfaces.contains("java/lang/Runnable")) {
                cn.interfaces.add("java/lang/Runnable");
            }
            MethodNode runMethod = new MethodNode(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
            runMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            runMethod.instructions.add(new InsnNode(Opcodes.ICONST_0));
            runMethod.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            runMethod.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    PACK_CACHE_CLS,
                    "hasResource",
                    "([Ljava/lang/String;)Z",
                    false));
            runMethod.instructions.add(new InsnNode(Opcodes.POP));
            runMethod.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(runMethod);
        }

        return (patchedInit || patchedValid || patchedDecompose) ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformPackCacheNode(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"optimize".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/util/Map".equals(min.owner)
                        && "copyOf".equals(min.name)
                        && "(Ljava/util/Map;)Ljava/util/Map;".equals(min.desc)) {
                    min.owner = JAR_PAYLOAD_CLS;
                    min.name = "fastPackCacheMapCopy";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformFastMap(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (FieldNode fn : cn.fields) {
            if ("sunlitKeyArr".equals(fn.name)) {
                return null;
            }
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "sunlitKeyArr", "[L" + FAST_MAP_KEY_CLS + ";", null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "sunlitPropArr", "[Ljava/lang/Object;", null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "sunlitIndexCache", "Ljava/util/IdentityHashMap;", null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE, "sunlitPopulating", "Z", null, null));

        boolean patchedInit = false;
        boolean patchedGetIndex = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "(Ljava/util/Collection;Ljava/util/Map;Z)V".equals(mn.desc)) {
                // Pre-size toKeyIndex Object2IntOpenHashMap(properties.size()) instead of default 16.
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap".equals(min.owner)
                            && "<init>".equals(min.name)
                            && "()V".equals(min.desc)) {
                        InsnList sizeArg = new InsnList();
                        sizeArg.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        sizeArg.add(new MethodInsnNode(
                                Opcodes.INVOKEINTERFACE, "java/util/Collection", "size", "()I", true));
                        mn.instructions.insertBefore(min, sizeArg);
                        min.desc = "(I)V";
                        break;
                    }
                }
                // Replace `new ArrayList(factor); for (0..factor) list.add(null);` with Arrays.asList(new Object[factor]).
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof TypeInsnNode tin
                            && tin.getOpcode() == Opcodes.NEW
                            && "java/util/ArrayList".equals(tin.desc)) {
                        AbstractInsnNode d = nextReal(tin);
                        AbstractInsnNode iloadFactor = d != null ? nextReal(d) : null;
                        AbstractInsnNode ctor = iloadFactor != null ? nextReal(iloadFactor) : null;
                        AbstractInsnNode storeList = ctor != null ? nextReal(ctor) : null;
                        if (d != null && d.getOpcode() == Opcodes.DUP
                                && iloadFactor instanceof VarInsnNode factorVin && factorVin.getOpcode() == Opcodes.ILOAD
                                && ctor instanceof MethodInsnNode ctorMin
                                && "java/util/ArrayList".equals(ctorMin.owner)
                                && "<init>".equals(ctorMin.name)
                                && "(I)V".equals(ctorMin.desc)
                                && storeList instanceof VarInsnNode listVin && listVin.getOpcode() == Opcodes.ASTORE) {
                            AbstractInsnNode entrySetLoad = null;
                            for (AbstractInsnNode cur = storeList.getNext(); cur != null; cur = cur.getNext()) {
                                if (cur instanceof VarInsnNode av && av.getOpcode() == Opcodes.ALOAD && av.var == 2) {
                                    AbstractInsnNode nxt = nextReal(av);
                                    if (nxt instanceof MethodInsnNode emin
                                            && "java/util/Map".equals(emin.owner)
                                            && "entrySet".equals(emin.name)) {
                                        entrySetLoad = av;
                                        break;
                                    }
                                }
                            }
                            if (entrySetLoad != null) {
                                InsnList fixedList = new InsnList();
                                fixedList.add(new VarInsnNode(Opcodes.ILOAD, factorVin.var));
                                fixedList.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
                                fixedList.add(new MethodInsnNode(
                                        Opcodes.INVOKESTATIC,
                                        "java/util/Arrays",
                                        "asList",
                                        "([Ljava/lang/Object;)Ljava/util/List;",
                                        false));
                                fixedList.add(new VarInsnNode(Opcodes.ASTORE, listVin.var));
                                mn.instructions.insertBefore(tin, fixedList);
                                AbstractInsnNode cur = tin;
                                while (cur != null && cur != entrySetLoad) {
                                    AbstractInsnNode next = cur.getNext();
                                    mn.instructions.remove(cur);
                                    cur = next;
                                }
                                break;
                            }
                        }
                    }
                }
                AbstractInsnNode putKeysInsn = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof FieldInsnNode fin
                            && insn.getOpcode() == Opcodes.PUTFIELD
                            && FAST_MAP_CLS.equals(fin.owner)
                            && "keys".equals(fin.name)) {
                        putKeysInsn = insn;
                        break;
                    }
                }
                if (putKeysInsn == null) {
                    return null;
                }
                InsnList initCall = new InsnList();
                initCall.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initCall.add(new VarInsnNode(Opcodes.ALOAD, 2));
                initCall.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true));
                initCall.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, FAST_MAP_CLS, "sunlitInitFastKeys", "(I)V", false));
                mn.instructions.insert(putKeysInsn, initCall);

                for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext(); ) {
                    AbstractInsnNode insn = it.next();
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList done = new InsnList();
                        done.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        done.add(new InsnNode(Opcodes.ICONST_0));
                        done.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitPopulating", "Z"));
                        mn.instructions.insertBefore(insn, done);
                    }
                }
                patchedInit = true;
            } else if ("getIndexOf".equals(mn.name) && "(Ljava/util/Map;)I".equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode fallback = new LabelNode();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, FAST_MAP_CLS, "sunlitGetIndexOfFast", "(Ljava/util/Map;)I", false));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new IntInsnNode(Opcodes.BIPUSH, -2));
                fast.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, fallback));
                fast.add(new InsnNode(Opcodes.IRETURN));
                fast.add(fallback);
                fast.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(fast);
                patchedGetIndex = true;
            }
        }
        if (!patchedInit || !patchedGetIndex) {
            return null;
        }

        // sunlitInitFastKeys(int mapSize)
        MethodNode initHelper = new MethodNode(Opcodes.ACC_PRIVATE, "sunlitInitFastKeys", "(I)V", null, null);
        LabelNode loopCond = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode noCache = new LabelNode();
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initHelper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "keys", "Ljava/util/List;"));
        initHelper.instructions.add(new InsnNode(Opcodes.DUP));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2)); // list
        initHelper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3)); // n
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        initHelper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, FAST_MAP_KEY_CLS));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4)); // ka
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        initHelper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5)); // pa
        initHelper.instructions.add(new InsnNode(Opcodes.ICONST_0));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6)); // i
        initHelper.instructions.add(loopCond);
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        initHelper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        initHelper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        initHelper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, FAST_MAP_KEY_CLS));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        initHelper.instructions.add(new InsnNode(Opcodes.AASTORE));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        initHelper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, FAST_MAP_KEY_CLS, "getProperty", "()Lnet/minecraft/world/level/block/state/properties/Property;", false));
        initHelper.instructions.add(new InsnNode(Opcodes.AASTORE));
        initHelper.instructions.add(new IincInsnNode(6, 1));
        initHelper.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
        initHelper.instructions.add(loopEnd);
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        initHelper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitKeyArr", "[L" + FAST_MAP_KEY_CLS + ";"));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        initHelper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitPropArr", "[Ljava/lang/Object;"));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        initHelper.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initHelper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLE, noCache));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initHelper.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap"));
        initHelper.instructions.add(new InsnNode(Opcodes.DUP));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        initHelper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "(I)V", false));
        initHelper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitIndexCache", "Ljava/util/IdentityHashMap;"));
        initHelper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initHelper.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initHelper.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitPopulating", "Z"));
        initHelper.instructions.add(noCache);
        initHelper.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(initHelper);

        // sunlitGetIndexOfFast(Map map) -> int (-2 means fall back)
        MethodNode clearCache = new MethodNode(Opcodes.ACC_PUBLIC, "sunlitClearIndexCache", "()V", null, null);
        clearCache.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        clearCache.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        clearCache.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, FAST_MAP_CLS, "sunlitIndexCache", "Ljava/util/IdentityHashMap;"));
        clearCache.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(clearCache);

        MethodNode fastIdx = new MethodNode(Opcodes.ACC_PRIVATE, "sunlitGetIndexOfFast", "(Ljava/util/Map;)I", null, null);
        LabelNode skipCacheLookup = new LabelNode();
        LabelNode idxLoop = new LabelNode();
        LabelNode idxDone = new LabelNode();
        LabelNode skipStore = new LabelNode();

        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastIdx.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "sunlitIndexCache", "Ljava/util/IdentityHashMap;"));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2)); // cache
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastIdx.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "sunlitPopulating", "Z"));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFNE, skipCacheLookup));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFNULL, skipCacheLookup));

        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastIdx.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/IdentityHashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        fastIdx.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3)); // hit
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFNULL, skipCacheLookup));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastIdx.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
        fastIdx.instructions.add(new InsnNode(Opcodes.IRETURN));

        fastIdx.instructions.add(skipCacheLookup);
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastIdx.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "sunlitKeyArr", "[L" + FAST_MAP_KEY_CLS + ";"));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3)); // ka
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        LabelNode hasKeys = new LabelNode();
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, hasKeys));
        fastIdx.instructions.add(new IntInsnNode(Opcodes.BIPUSH, -2));
        fastIdx.instructions.add(new InsnNode(Opcodes.IRETURN));

        fastIdx.instructions.add(hasKeys);
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastIdx.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "sunlitPropArr", "[Ljava/lang/Object;"));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4)); // pa
        fastIdx.instructions.add(new InsnNode(Opcodes.ICONST_0));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5)); // idx
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastIdx.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6)); // n
        fastIdx.instructions.add(new InsnNode(Opcodes.ICONST_0));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ISTORE, 7)); // i

        fastIdx.instructions.add(idxLoop);
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, idxDone));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
        fastIdx.instructions.add(new InsnNode(Opcodes.AALOAD));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 7));
        fastIdx.instructions.add(new InsnNode(Opcodes.AALOAD));
        fastIdx.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        fastIdx.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Comparable"));
        fastIdx.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, FAST_MAP_KEY_CLS, "toPartialMapIndex", "(Ljava/lang/Comparable;)I", false));
        fastIdx.instructions.add(new InsnNode(Opcodes.IADD));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ISTORE, 5));
        fastIdx.instructions.add(new IincInsnNode(7, 1));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.GOTO, idxLoop));

        fastIdx.instructions.add(idxDone);
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastIdx.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, FAST_MAP_CLS, "sunlitPopulating", "Z"));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skipStore));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastIdx.instructions.add(new JumpInsnNode(Opcodes.IFNULL, skipStore));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastIdx.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        fastIdx.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/IdentityHashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        fastIdx.instructions.add(new InsnNode(Opcodes.POP));

        fastIdx.instructions.add(skipStore);
        fastIdx.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        fastIdx.instructions.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(fastIdx);

        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformModLauncherClassTransformer(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("transform".equals(mn.name) && "([BLjava/lang/String;Ljava/lang/String;)[B".equals(mn.desc)) {
                target = mn;
                break;
            }
        }
        if (target == null) {
            return null;
        }
        int pluginsVar = -1;
        int needsVar = -1;
        AbstractInsnNode newClassNodeInsn = null;
        for (AbstractInsnNode insn : target.instructions) {
            if (insn instanceof MethodInsnNode min) {
                if (pluginsVar < 0
                        && "cpw/mods/modlauncher/LaunchPluginHandler".equals(min.owner)
                        && "computeLaunchPluginTransformerSet".equals(min.name)) {
                    AbstractInsnNode next = nextReal(min);
                    if (next instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                        pluginsVar = vin.var;
                    }
                } else if (needsVar < 0
                        && "cpw/mods/modlauncher/TransformStore".equals(min.owner)
                        && "needsTransforming".equals(min.name)) {
                    AbstractInsnNode next = nextReal(min);
                    if (next instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ISTORE) {
                        needsVar = vin.var;
                    }
                }
            } else if (newClassNodeInsn == null
                    && insn instanceof TypeInsnNode tin
                    && tin.getOpcode() == Opcodes.NEW
                    && "org/objectweb/asm/tree/ClassNode".equals(tin.desc)) {
                newClassNodeInsn = tin;
                break;
            }
        }
        if (pluginsVar < 0 || needsVar < 0 || newClassNodeInsn == null) {
            return null;
        }
        InsnList fastSkip = new InsnList();
        LabelNode proceed = new LabelNode();
        fastSkip.add(new VarInsnNode(Opcodes.ILOAD, needsVar));
        fastSkip.add(new JumpInsnNode(Opcodes.IFNE, proceed));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastSkip.add(new InsnNode(Opcodes.ARRAYLENGTH));
        fastSkip.add(new JumpInsnNode(Opcodes.IFLE, proceed));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, pluginsVar));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastSkip.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                CLASS_TRANSFORMER_CLS,
                "transformingClassLoader",
                "Lcpw/mods/modlauncher/TransformingClassLoader;"));
        fastSkip.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                JAR_PAYLOAD_CLS,
                "canSkipDistCleanerOnlyFast",
                "(Ljava/util/Map;[BLjava/lang/ClassLoader;)Z",
                false));
        fastSkip.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastSkip.add(new InsnNode(Opcodes.ARETURN));
        fastSkip.add(proceed);
        target.instructions.insertBefore(newClassNodeInsn, fastSkip);
        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformAccessTransformerList(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (FieldNode fn : cn.fields) {
            if ("sunlitTargetCache".equals(fn.name)) {
                return null;
            }
        }
        MethodNode getMethod = null;
        for (MethodNode mn : cn.methods) {
            if ("getTransformersForTarget".equals(mn.name)
                    && "(Lorg/objectweb/asm/Type;)Ljava/util/Map;".equals(mn.desc)) {
                getMethod = mn;
                break;
            }
        }
        if (getMethod == null) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitTargetCache",
                "Ljava/util/Map;",
                null,
                null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitCachedSize",
                "I",
                null,
                Integer.valueOf(-1)));

        // Replace getTransformersForTarget(Type) with volatile O(1) Map lookup + synchronized rebuild.
        InsnList fastGet = new InsnList();
        LabelNode doRebuild = new LabelNode();
        LabelNode hasEntry = new LabelNode();
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastGet.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "sunlitTargetCache", "Ljava/util/Map;"));
        fastGet.add(new VarInsnNode(Opcodes.ASTORE, 2)); // cache
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastGet.add(new JumpInsnNode(Opcodes.IFNULL, doRebuild));
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastGet.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "sunlitCachedSize", "I"));
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastGet.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "accessTransformers", "Ljava/util/Map;"));
        fastGet.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true));
        fastGet.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, hasEntry));
        fastGet.add(doRebuild);
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fastGet.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, AT_LIST_CLS, "sunlitRebuildTargetCache", "()Ljava/util/Map;", false));
        fastGet.add(new VarInsnNode(Opcodes.ASTORE, 2));
        fastGet.add(hasEntry);
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fastGet.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fastGet.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        fastGet.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        fastGet.add(new InsnNode(Opcodes.DUP));
        LabelNode nonNullRes = new LabelNode();
        fastGet.add(new JumpInsnNode(Opcodes.IFNONNULL, nonNullRes));
        fastGet.add(new InsnNode(Opcodes.POP));
        fastGet.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/util/Collections", "emptyMap", "()Ljava/util/Map;", false));
        fastGet.add(nonNullRes);
        fastGet.add(new InsnNode(Opcodes.ARETURN));
        getMethod.instructions = fastGet;
        if (getMethod.tryCatchBlocks != null) {
            getMethod.tryCatchBlocks.clear();
        }
        if (getMethod.localVariables != null) {
            getMethod.localVariables.clear();
        }

        // private synchronized Map sunlitRebuildTargetCache()
        MethodNode rebuild = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED,
                "sunlitRebuildTargetCache",
                "()Ljava/util/Map;",
                null,
                null);
        String atCls = "net/minecraftforge/accesstransformer/AccessTransformer";
        String targetCls = "net/minecraftforge/accesstransformer/Target";
        String targetTypeCls = "net/minecraftforge/accesstransformer/TargetType";
        LabelNode loopCond = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode hasTypeMap = new LabelNode();
        LabelNode hasTtMap = new LabelNode();

        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "accessTransformers", "Ljava/util/Map;"));
        rebuild.instructions.add(new InsnNode(Opcodes.DUP));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1)); // atMap
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ISTORE, 2)); // size
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "sunlitTargetCache", "Ljava/util/Map;"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3)); // curCache
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        LabelNode doBuild = new LabelNode();
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFNULL, doBuild));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, AT_LIST_CLS, "sunlitCachedSize", "I"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, doBuild));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        rebuild.instructions.add(new InsnNode(Opcodes.ARETURN));

        rebuild.instructions.add(doBuild);
        rebuild.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        rebuild.instructions.add(new InsnNode(Opcodes.DUP));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new InsnNode(Opcodes.ICONST_2));
        rebuild.instructions.add(new InsnNode(Opcodes.IMUL));
        rebuild.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "(I)V", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4)); // built

        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5)); // it

        rebuild.instructions.add(loopCond);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFEQ, loopEnd));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, atCls));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6)); // at
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, atCls, "getTarget", "()L" + targetCls + ";", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 7)); // target
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, targetCls, "getASMType", "()Lorg/objectweb/asm/Type;", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 8)); // asmType

        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 9)); // typeMap
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, hasTypeMap));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/EnumMap"));
        rebuild.instructions.add(new InsnNode(Opcodes.DUP));
        rebuild.instructions.add(new LdcInsnNode(Type.getObjectType(targetTypeCls)));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/EnumMap", "<init>", "(Ljava/lang/Class;)V", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 9));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        rebuild.instructions.add(new InsnNode(Opcodes.POP));

        rebuild.instructions.add(hasTypeMap);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, targetCls, "getType", "()L" + targetTypeCls + ";", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 10)); // tt
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 10));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 11)); // ttMap
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 11));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, hasTtMap));
        rebuild.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        rebuild.instructions.add(new InsnNode(Opcodes.DUP));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ASTORE, 11));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 9));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 10));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 11));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        rebuild.instructions.add(new InsnNode(Opcodes.POP));

        rebuild.instructions.add(hasTtMap);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 11));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, targetCls, "targetName", "()Ljava/lang/String;", false));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        rebuild.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        rebuild.instructions.add(new InsnNode(Opcodes.POP));
        rebuild.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopCond));

        rebuild.instructions.add(loopEnd);
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rebuild.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, AT_LIST_CLS, "sunlitCachedSize", "I"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        rebuild.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, AT_LIST_CLS, "sunlitTargetCache", "Ljava/util/Map;"));
        rebuild.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        rebuild.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(rebuild);

        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformImmutableMap(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (MethodNode mn : cn.methods) {
            if ("sunlitBuildStateMap".equals(mn.name)) {
                return null;
            }
        }
        String imMap = "com/google/common/collect/ImmutableMap";
        String imEntry = "com/google/common/collect/ImmutableMapEntry";
        String imNonTerm = "com/google/common/collect/ImmutableMapEntry$NonTerminalImmutableMapEntry";
        String regMap = "com/google/common/collect/RegularImmutableMap";
        String hashing = "com/google/common/collect/Hashing";

        // public static int[] sunlitPrecomputeLayout(Object[] keys)
        MethodNode layoutM = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "sunlitPrecomputeLayout",
                "([Ljava/lang/Object;)[I",
                null,
                null);
        {
            InsnList il = layoutM.instructions;
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            LabelNode noOverflow = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ARRAYLENGTH));
            il.add(new VarInsnNode(Opcodes.ISTORE, 1)); // n
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new LdcInsnNode(Double.valueOf(1.2d)));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hashing, "closedTableSize", "(ID)I", false));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2)); // ts
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.ISUB));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3)); // mask
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4)); // layout
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new InsnNode(Opcodes.IASTORE));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5)); // counts
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6)); // i = 0
            il.add(loopCond);
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hashing, "smear", "(I)I", false));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new InsnNode(Opcodes.IAND));
            il.add(new VarInsnNode(Opcodes.ISTORE, 7)); // b
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.DUP2));
            il.add(new InsnNode(Opcodes.IALOAD));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new InsnNode(Opcodes.DUP_X2));
            il.add(new InsnNode(Opcodes.IASTORE));
            il.add(new IntInsnNode(Opcodes.BIPUSH, 6));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPLT, noOverflow));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(noOverflow);
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.IASTORE));
            il.add(new IincInsnNode(6, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        cn.methods.add(layoutM);

        // public static ImmutableMap sunlitBuildStateMap(Object[] keys, Object[] vals, int[] layout)
        MethodNode buildM = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "sunlitBuildStateMap",
                "([Ljava/lang/Object;[Ljava/lang/Object;[I)L" + imMap + ";",
                null,
                null);
        {
            InsnList il = buildM.instructions;
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            LabelNode hasPrev = new LabelNode();
            LabelNode storeEntry = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ARRAYLENGTH));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3)); // n
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IALOAD));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4)); // mask
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, imEntry, "createEntryArray", "(I)[L" + imEntry + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5)); // entries
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, imEntry, "createEntryArray", "(I)[L" + imEntry + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 6)); // table
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 7)); // i = 0
            il.add(loopCond);
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new InsnNode(Opcodes.IALOAD));
            il.add(new VarInsnNode(Opcodes.ISTORE, 8)); // b
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 8));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new VarInsnNode(Opcodes.ASTORE, 9)); // prev
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasPrev));
            il.add(new TypeInsnNode(Opcodes.NEW, imEntry));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, imEntry, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            il.add(new JumpInsnNode(Opcodes.GOTO, storeEntry));
            il.add(hasPrev);
            il.add(new TypeInsnNode(Opcodes.NEW, imNonTerm));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, imNonTerm, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;L" + imEntry + ";)V", false));
            il.add(storeEntry);
            il.add(new VarInsnNode(Opcodes.ASTORE, 10)); // entry
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new InsnNode(Opcodes.AASTORE));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 8));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new InsnNode(Opcodes.AASTORE));
            il.add(new IincInsnNode(7, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new TypeInsnNode(Opcodes.NEW, regMap));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, regMap, "<init>", "([Ljava/util/Map$Entry;[L" + imEntry + ";I)V", false));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        // Batch 24: replace RegularImmutableMap entry/table allocation in sunlitBuildStateMap with zero-entry SunlitFlatStateMap.
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "SUNLIT_FLAT_CTOR",
                "Ljava/lang/invoke/MethodHandle;",
                null,
                null));
        MethodNode initFlatM = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                "sunlitInitFlatCtor",
                "()Ljava/lang/invoke/MethodHandle;",
                null,
                null);
        initFlatM.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, imMap, "SUNLIT_FLAT_CTOR", "Ljava/lang/invoke/MethodHandle;"));
        initFlatM.instructions.add(new InsnNode(Opcodes.DUP));
        LabelNode flatAlreadyInit = new LabelNode();
        initFlatM.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, flatAlreadyInit));
        initFlatM.instructions.add(new InsnNode(Opcodes.POP));
        initFlatM.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false));
        initFlatM.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                JAR_PAYLOAD_CLS,
                "initFlatStateMapCtor",
                "(Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandle;",
                false));
        initFlatM.instructions.add(new InsnNode(Opcodes.DUP));
        initFlatM.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, imMap, "SUNLIT_FLAT_CTOR", "Ljava/lang/invoke/MethodHandle;"));
        initFlatM.instructions.add(flatAlreadyInit);
        initFlatM.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(initFlatM);

        buildM.instructions.clear();
        buildM.tryCatchBlocks.clear();
        buildM.localVariables.clear();
        buildM.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, imMap, "SUNLIT_FLAT_CTOR", "Ljava/lang/invoke/MethodHandle;"));
        buildM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        buildM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        LabelNode ctorReady = new LabelNode();
        buildM.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ctorReady));
        buildM.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, imMap, "sunlitInitFlatCtor", "()Ljava/lang/invoke/MethodHandle;", false));
        buildM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        buildM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        LabelNode ctorNull = new LabelNode();
        buildM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, ctorNull));
        buildM.instructions.add(ctorReady);
        buildM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        buildM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        buildM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        buildM.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle",
                "invokeExact",
                "([Ljava/lang/Object;[Ljava/lang/Object;)L" + imMap + ";",
                false));
        buildM.instructions.add(new InsnNode(Opcodes.ARETURN));
        buildM.instructions.add(ctorNull);
        buildM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        buildM.instructions.add(new InsnNode(Opcodes.ARETURN));

        cn.methods.add(buildM);
        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformRegularImmutableMap(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && (mn.access & Opcodes.ACC_PRIVATE) != 0) {
                mn.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                patched = true;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformStarShapeProvider(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        MethodNode target = null;
        String desc = "(Lnet/minecraft/world/phys/shapes/VoxelShape;[Lnet/minecraft/world/phys/shapes/VoxelShape;[Lnet/minecraft/world/phys/shapes/VoxelShape;)[Lnet/minecraft/world/phys/shapes/VoxelShape;";
        for (MethodNode mn : cn.methods) {
            if ("constructStateShapes".equals(mn.name) && desc.equals(mn.desc)) {
                target = mn;
                break;
            }
            if ("sunlitBuildOneInto".equals(mn.name)) {
                return null;
            }
        }
        if (target == null) {
            return null;
        }
        String voxelShape = "net/minecraft/world/phys/shapes/VoxelShape";
        String voxelColl = "fuzs/diagonalblocks/world/phys/shapes/VoxelCollection";
        MethodNode worker = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlitBuildOneInto",
                "(L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelColl + ";I)V",
                null,
                null);
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode skipBit = new LabelNode();
        worker.instructions.add(new TypeInsnNode(Opcodes.NEW, voxelColl));
        worker.instructions.add(new InsnNode(Opcodes.DUP));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        worker.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, voxelColl, "<init>", "(L" + voxelShape + ";)V", false));
        worker.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5)); // collection
        worker.instructions.add(new InsnNode(Opcodes.ICONST_0));
        worker.instructions.add(new VarInsnNode(Opcodes.ISTORE, 6)); // j
        worker.instructions.add(loopStart);
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        worker.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        worker.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        worker.instructions.add(new InsnNode(Opcodes.ICONST_1));
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        worker.instructions.add(new InsnNode(Opcodes.ISHL));
        worker.instructions.add(new InsnNode(Opcodes.IAND));
        worker.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skipBit));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        worker.instructions.add(new InsnNode(Opcodes.AALOAD));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 6));
        worker.instructions.add(new InsnNode(Opcodes.AALOAD));
        worker.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, voxelColl, "addVoxelShape", "(L" + voxelShape + ";L" + voxelShape + ";)V", false));
        worker.instructions.add(skipBit);
        worker.instructions.add(new IincInsnNode(6, 1));
        worker.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        worker.instructions.add(loopEnd);
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        worker.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        worker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        worker.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, voxelColl, "optimize", "()L" + voxelColl + ";", false));
        worker.instructions.add(new InsnNode(Opcodes.AASTORE));
        worker.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(worker);

        InsnList replaced = new InsnList();
        replaced.add(new InsnNode(Opcodes.ICONST_1));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replaced.add(new InsnNode(Opcodes.ARRAYLENGTH));
        replaced.add(new InsnNode(Opcodes.ISHL));
        replaced.add(new VarInsnNode(Opcodes.ISTORE, 4)); // count
        replaced.add(new VarInsnNode(Opcodes.ILOAD, 4));
        replaced.add(new TypeInsnNode(Opcodes.ANEWARRAY, voxelColl));
        replaced.add(new VarInsnNode(Opcodes.ASTORE, 5)); // out
        replaced.add(new VarInsnNode(Opcodes.ILOAD, 4));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 2));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 3));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 5));
        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        Handle impl = new Handle(
                Opcodes.H_INVOKESTATIC,
                STAR_SHAPE_CLS,
                "sunlitBuildOneInto",
                "(L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelColl + ";I)V",
                true);
        replaced.add(new InvokeDynamicInsnNode(
                "accept",
                "(L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelShape + ";[L" + voxelColl + ";)Ljava/util/function/IntConsumer;",
                bsm,
                Type.getMethodType("(I)V"),
                impl,
                Type.getMethodType("(I)V")));
        replaced.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "runParallelRange", "(ILjava/util/function/IntConsumer;)V", false));
        replaced.add(new VarInsnNode(Opcodes.ALOAD, 5));
        replaced.add(new InsnNode(Opcodes.ARETURN));
        target.instructions = replaced;
        if (target.tryCatchBlocks != null) {
            target.tryCatchBlocks.clear();
        }
        if (target.localVariables != null) {
            target.localVariables.clear();
        }

        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap".equals(min.owner)
                            && "<init>".equals(min.name)
                            && "()V".equals(min.desc)) {
                        mn.instructions.insertBefore(min, new LdcInsnNode(Integer.valueOf(262144)));
                        min.desc = "(I)V";
                        break;
                    }
                }
            } else if ("_getAABBIndex".equals(mn.name)
                    && "(Lnet/minecraft/world/level/block/state/BlockState;)I".equals(mn.desc)) {
                InsnList fastIdx = new InsnList();
                LabelNode haveIdx = new LabelNode();
                fastIdx.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, STAR_SHAPE_CLS, "STATE_INDEX_CACHE", "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
                fastIdx.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fastIdx.add(new InsnNode(Opcodes.ICONST_M1));
                fastIdx.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "it/unimi/dsi/fastutil/objects/Object2IntMap",
                        "getOrDefault",
                        "(Ljava/lang/Object;I)I",
                        true));
                fastIdx.add(new InsnNode(Opcodes.DUP));
                fastIdx.add(new JumpInsnNode(Opcodes.IFGE, haveIdx));
                fastIdx.add(new InsnNode(Opcodes.POP));
                fastIdx.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastIdx.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fastIdx.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        STAR_SHAPE_CLS,
                        "makeIndex",
                        "(Lnet/minecraft/world/level/block/state/BlockState;)I",
                        true));
                fastIdx.add(new VarInsnNode(Opcodes.ISTORE, 2));
                fastIdx.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, STAR_SHAPE_CLS, "STATE_INDEX_CACHE", "Lit/unimi/dsi/fastutil/objects/Object2IntMap;"));
                fastIdx.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fastIdx.add(new VarInsnNode(Opcodes.ILOAD, 2));
                fastIdx.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "it/unimi/dsi/fastutil/objects/Object2IntMap",
                        "put",
                        "(Ljava/lang/Object;I)I",
                        true));
                fastIdx.add(new InsnNode(Opcodes.POP));
                fastIdx.add(new VarInsnNode(Opcodes.ILOAD, 2));
                fastIdx.add(haveIdx);
                fastIdx.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = fastIdx;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
            }
        }

        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformUnionFileSystemProvider(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "()V".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW && "java/util/HashMap".equals(tin.desc)) {
                        tin.desc = "java/util/concurrent/ConcurrentHashMap";
                        patched = true;
                    } else if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/util/HashMap".equals(min.owner)
                            && "<init>".equals(min.name)) {
                        min.owner = "java/util/concurrent/ConcurrentHashMap";
                        patched = true;
                    }
                }
            } else if ("newFileSystemInternal".equals(mn.name)) {
                // Move MONITORENTER after `new UnionFileSystem(...)` (`ASTORE 6`) so ZipFS opens concurrently.
                AbstractInsnNode monitorStart = null;
                AbstractInsnNode monitorEnter = null;
                AbstractInsnNode astore6 = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn.getOpcode() == Opcodes.MONITORENTER && monitorEnter == null) {
                        AbstractInsnNode p3 = prevReal(insn); // ASTORE 5
                        AbstractInsnNode p2 = prevReal(p3);   // DUP
                        AbstractInsnNode p1 = prevReal(p2);   // GETFIELD fileSystems
                        AbstractInsnNode p0 = prevReal(p1);   // ALOAD 0
                        if (p0 instanceof VarInsnNode && p3 instanceof VarInsnNode v3 && v3.var == 5) {
                            monitorStart = p0;
                            monitorEnter = insn;
                        }
                    } else if (insn instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE && vin.var == 6) {
                        astore6 = insn;
                    }
                }
                if (monitorStart != null && monitorEnter != null && astore6 != null && mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
                    InsnList lockBlock = new InsnList();
                    AbstractInsnNode cur = monitorStart;
                    while (true) {
                        AbstractInsnNode next = cur.getNext();
                        mn.instructions.remove(cur);
                        lockBlock.add(cur);
                        if (cur == monitorEnter) {
                            break;
                        }
                        cur = next;
                    }
                    LabelNode newTryStart = new LabelNode();
                    lockBlock.add(newTryStart);
                    mn.instructions.insert(astore6, lockBlock);
                    mn.tryCatchBlocks.get(0).start = newTryStart;
                    patched = true;
                }
            }
        }
        boolean hasNewInputStream = false;
        for (MethodNode mn : cn.methods) {
            if ("newInputStream".equals(mn.name)) {
                hasNewInputStream = true;
                break;
            }
        }
        if (!hasNewInputStream) {
            MethodNode m = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_VARARGS,
                    "newInputStream",
                    "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;",
                    null,
                    new String[] { "java/io/IOException" });
            LabelNode superFallback = new LabelNode();
            LabelNode fastCall = new LabelNode();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "cpw/mods/niofs/union/UnionPath"));
            m.instructions.add(new JumpInsnNode(Opcodes.IFEQ, superFallback));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
            m.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fastCall));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
            m.instructions.add(new InsnNode(Opcodes.ICONST_1));
            m.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPNE, superFallback));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new InsnNode(Opcodes.ICONST_0));
            m.instructions.add(new InsnNode(Opcodes.AALOAD));
            m.instructions.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, "java/nio/file/StandardOpenOption", "READ", "Ljava/nio/file/StandardOpenOption;"));
            m.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, superFallback));
            m.instructions.add(fastCall);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "cpw/mods/niofs/union/UnionPath"));
            m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath", "getFileSystem",
                    "()Lcpw/mods/niofs/union/UnionFileSystem;", false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, UNION_CLS, "sunlitNewInputStream",
                    "(Lcpw/mods/niofs/union/UnionPath;)Ljava/io/InputStream;", false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
            m.instructions.add(superFallback);
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            m.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/nio/file/spi/FileSystemProvider",
                    "newInputStream",
                    "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;",
                    false));
            m.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(m);
            patched = true;
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>();
        }
        if (!cn.interfaces.contains("java/util/function/Predicate")) {
            cn.interfaces.add("java/util/function/Predicate");
            patched = true;
        }
        boolean hasPredicateTest = false;
        for (MethodNode mn : cn.methods) {
            if ("test".equals(mn.name) && "(Ljava/lang/Object;)Z".equals(mn.desc)) {
                hasPredicateTest = true;
                break;
            }
        }
        if (!hasPredicateTest) {
            MethodNode testM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "test",
                    "(Ljava/lang/Object;)Z",
                    null,
                    null);
            LabelNode notUnionPath = new LabelNode();
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            testM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "cpw/mods/niofs/union/UnionPath"));
            testM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notUnionPath));
            testM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            testM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "cpw/mods/niofs/union/UnionPath"));
            testM.instructions.add(new InsnNode(Opcodes.DUP));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "cpw/mods/niofs/union/UnionPath", "getFileSystem",
                    "()Lcpw/mods/niofs/union/UnionFileSystem;", false));
            testM.instructions.add(new InsnNode(Opcodes.SWAP));
            testM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, UNION_CLS, "sunlitExists",
                    "(Lcpw/mods/niofs/union/UnionPath;)Z", false));
            testM.instructions.add(new InsnNode(Opcodes.IRETURN));
            testM.instructions.add(notUnionPath);
            testM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            testM.instructions.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(testM);
            patched = true;
        }
        return patched ? writeNodeWithFrames(cn, null) : null;
    }

    public static byte[] transformJavaFiles(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            boolean isExists = "exists".equals(mn.name)
                    && "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z".equals(mn.desc);
            boolean isNotExists = !isExists
                    && "notExists".equals(mn.name)
                    && "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z".equals(mn.desc);
            if (!isExists && !isNotExists) {
                continue;
            }
            InsnList fast = new InsnList();
            LabelNode popFallback = new LabelNode();
            LabelNode fallback = new LabelNode();
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new InsnNode(Opcodes.ARRAYLENGTH));
            fast.add(new JumpInsnNode(Opcodes.IFNE, fallback));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAVA_FILES_CLS,
                    "provider",
                    "(Ljava/nio/file/Path;)Ljava/nio/file/spi/FileSystemProvider;",
                    false));
            fast.add(new InsnNode(Opcodes.DUP));
            fast.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/util/function/Predicate"));
            fast.add(new JumpInsnNode(Opcodes.IFEQ, popFallback));
            fast.add(new InsnNode(Opcodes.DUP));
            fast.add(new LdcInsnNode("union"));
            fast.add(new InsnNode(Opcodes.SWAP));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/nio/file/spi/FileSystemProvider",
                    "getScheme",
                    "()Ljava/lang/String;",
                    false));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false));
            fast.add(new JumpInsnNode(Opcodes.IFEQ, popFallback));
            fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Predicate"));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/Predicate",
                    "test",
                    "(Ljava/lang/Object;)Z",
                    true));
            if (isNotExists) {
                LabelNode retFalse = new LabelNode();
                fast.add(new JumpInsnNode(Opcodes.IFNE, retFalse));
                fast.add(new InsnNode(Opcodes.ICONST_1));
                fast.add(new InsnNode(Opcodes.IRETURN));
                fast.add(retFalse);
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new InsnNode(Opcodes.IRETURN));
            } else {
                fast.add(new InsnNode(Opcodes.IRETURN));
            }
            fast.add(popFallback);
            fast.add(new InsnNode(Opcodes.POP));
            fast.add(fallback);
            mn.instructions.insert(fast);
            patched = true;
        }
        return patched ? writeNodeWithFrames(cn, null) : null;
    }

    public static byte[] transformJar(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        boolean patchedVerify = false;
        for (MethodNode mn : cn.methods) {
            if ("verifyPath".equals(mn.name)
                    && "(Ljava/nio/file/Path;)Lcpw/mods/jarhandling/SecureJar$Status;".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof JumpInsnNode jin && jin.getOpcode() == Opcodes.IF_ACMPEQ && jin.label != null) {
                        LabelNode hasSecurityLabel = new LabelNode();
                        InsnList fastUnverified = new InsnList();
                        fastUnverified.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        fastUnverified.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL, JAR_CLS, "hasSecurityData", "()Z", false));
                        fastUnverified.add(new JumpInsnNode(Opcodes.IFNE, hasSecurityLabel));
                        fastUnverified.add(new FieldInsnNode(
                                Opcodes.GETSTATIC,
                                "cpw/mods/jarhandling/SecureJar$Status",
                                "UNVERIFIED",
                                "Lcpw/mods/jarhandling/SecureJar$Status;"));
                        fastUnverified.add(new InsnNode(Opcodes.ARETURN));
                        fastUnverified.add(hasSecurityLabel);
                        mn.instructions.insert(jin.label, fastUnverified);
                        patchedVerify = true;
                        break;
                    }
                }
                continue;
            }
            if (!"<init>".equals(mn.name)
                    || !"(Ljava/util/function/Supplier;Ljava/util/function/Function;Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)V".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode afterStatusPut = null;
            AbstractInsnNode mergeHead = null;
            int maxVar = 16;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                    maxVar = v.var + 2;
                } else if (insn instanceof IincInsnNode i && i.var + 2 > maxVar) {
                    maxVar = i.var + 2;
                }
                if (afterStatusPut == null
                        && insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.PUTFIELD
                        && JAR_CLS.equals(fin.owner)
                        && "statusData".equals(fin.name)) {
                    afterStatusPut = insn.getNext();
                }
                if (mergeHead == null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/function/Supplier".equals(min.owner)
                        && "get".equals(min.name)) {
                    AbstractInsnNode p1 = prevReal(min);
                    AbstractInsnNode p2 = prevReal(p1);
                    AbstractInsnNode p3 = prevReal(p2);
                    AbstractInsnNode p4 = prevReal(p3);
                    if (p4 instanceof VarInsnNode v4 && v4.getOpcode() == Opcodes.ALOAD && v4.var == 0
                            && p3 instanceof VarInsnNode v3 && v3.getOpcode() == Opcodes.ALOAD && v3.var == 6
                            && p2 instanceof JumpInsnNode j2 && j2.getOpcode() == Opcodes.IFNONNULL
                            && p1 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 1) {
                        mergeHead = p4;
                    }
                }
            }
            if (afterStatusPut != null && mergeHead != null) {
                LabelNode origLabel = new LabelNode();
                LabelNode mergeLabel = new LabelNode();
                LabelNode skipPkgs = new LabelNode();
                LabelNode skipProvs = new LabelNode();
                int fastVar = maxVar;

                InsnList fast = new InsnList();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "tryFastSingleJar",
                        "([Ljava/nio/file/Path;Ljava/util/function/BiPredicate;)[Ljava/lang/Object;",
                        false));
                fast.add(new VarInsnNode(Opcodes.ASTORE, fastVar));
                fast.add(new VarInsnNode(Opcodes.ALOAD, fastVar));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, origLabel));

                // filteredPaths = paths; moduleDataProvider = new Jar$JarModuleDataProvider(this); filesystem = UFSP.newFileSystem(pathFilter, filteredPaths);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 5));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new TypeInsnNode(Opcodes.NEW, "cpw/mods/jarhandling/impl/Jar$JarModuleDataProvider"));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "cpw/mods/jarhandling/impl/Jar$JarModuleDataProvider",
                        "<init>",
                        "(Lcpw/mods/jarhandling/impl/Jar;)V",
                        false));
                fast.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        JAR_CLS,
                        "moduleDataProvider",
                        "Lcpw/mods/jarhandling/impl/Jar$JarModuleDataProvider;"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, JAR_CLS, "UFSP", "Lcpw/mods/niofs/union/UnionFileSystemProvider;"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "cpw/mods/niofs/union/UnionFileSystemProvider",
                        "newFileSystem",
                        "(Ljava/util/function/BiPredicate;[Ljava/nio/file/Path;)Lcpw/mods/niofs/union/UnionFileSystem;",
                        false));
                fast.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, JAR_CLS, "filesystem", "Lcpw/mods/niofs/union/UnionFileSystem;"));

                // manifest = (Manifest) fast[0];
                fast.add(new VarInsnNode(Opcodes.ALOAD, fastVar));
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new InsnNode(Opcodes.AALOAD));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/jar/Manifest"));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 6));

                // if (fast[1] != null) this.packages = (Set) fast[1];
                fast.add(new VarInsnNode(Opcodes.ALOAD, fastVar));
                fast.add(new InsnNode(Opcodes.ICONST_1));
                fast.add(new InsnNode(Opcodes.AALOAD));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, skipPkgs));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Set"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new InsnNode(Opcodes.SWAP));
                fast.add(new FieldInsnNode(Opcodes.PUTFIELD, JAR_CLS, "packages", "Ljava/util/Set;"));
                LabelNode afterPkgs = new LabelNode();
                fast.add(new JumpInsnNode(Opcodes.GOTO, afterPkgs));
                fast.add(skipPkgs);
                fast.add(new InsnNode(Opcodes.POP));
                fast.add(afterPkgs);

                // if (fast[2] != null) this.providers = sunlitBuildProviders((List) fast[2]);
                fast.add(new VarInsnNode(Opcodes.ALOAD, fastVar));
                fast.add(new InsnNode(Opcodes.ICONST_2));
                fast.add(new InsnNode(Opcodes.AALOAD));
                fast.add(new InsnNode(Opcodes.DUP));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, skipProvs));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_CLS, "sunlitBuildProviders", "(Ljava/util/List;)Ljava/util/List;", false));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new InsnNode(Opcodes.SWAP));
                fast.add(new FieldInsnNode(Opcodes.PUTFIELD, JAR_CLS, "providers", "Ljava/util/List;"));
                fast.add(new JumpInsnNode(Opcodes.GOTO, mergeLabel));
                fast.add(skipProvs);
                fast.add(new InsnNode(Opcodes.POP));
                fast.add(new JumpInsnNode(Opcodes.GOTO, mergeLabel));
                fast.add(origLabel);

                mn.instructions.insertBefore(afterStatusPut, fast);
                mn.instructions.insertBefore(mergeHead, mergeLabel);
                patched = true;
            }
        }
        if (patched) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlitBuildProviders",
                    "(Ljava/util/List;)Ljava/util/List;",
                    null,
                    null);
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            helper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 1));
            helper.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            helper.instructions.add(new InsnNode(Opcodes.ICONST_0));
            helper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
            helper.instructions.add(loopCond);
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            helper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            helper.instructions.add(new TypeInsnNode(Opcodes.NEW, "cpw/mods/jarhandling/SecureJar$Provider"));
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
            helper.instructions.add(new InsnNode(Opcodes.ICONST_0));
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
            helper.instructions.add(new InsnNode(Opcodes.ICONST_1));
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
            helper.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, "cpw/mods/jarhandling/SecureJar$Provider", "<init>", "(Ljava/lang/String;Ljava/util/List;)V", false));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
            helper.instructions.add(new InsnNode(Opcodes.POP));
            helper.instructions.add(new IincInsnNode(3, 1));
            helper.instructions.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            helper.instructions.add(loopEnd);
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "copyOf", "(Ljava/util/Collection;)Ljava/util/List;", true));
            helper.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(helper);
        }
        boolean hasSunlitOpen = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitOpen".equals(mn.name)) {
                hasSunlitOpen = true;
                break;
            }
        }
        if (!hasSunlitOpen) {
            MethodNode openM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "sunlitOpen",
                    "(Ljava/lang/String;)Ljava/util/Optional;",
                    null,
                    null);
            LabelNode noOverridePop = new LabelNode();
            LabelNode doOpen = new LabelNode();
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            openM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            openM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            openM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                    false));
            openM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            openM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "nameOverrides", "Ljava/util/Map;"));
            openM.instructions.add(new InsnNode(Opcodes.DUP));
            openM.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
            openM.instructions.add(new JumpInsnNode(Opcodes.IFNE, noOverridePop));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
            openM.instructions.add(new InsnNode(Opcodes.DUP));
            openM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, noOverridePop));
            openM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "toString", "()Ljava/lang/String;", false));
            openM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            openM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            openM.instructions.add(new LdcInsnNode("META-INF"));
            openM.instructions.add(new InsnNode(Opcodes.ICONST_2));
            openM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            openM.instructions.add(new InsnNode(Opcodes.DUP));
            openM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            openM.instructions.add(new LdcInsnNode("versions"));
            openM.instructions.add(new InsnNode(Opcodes.AASTORE));
            openM.instructions.add(new InsnNode(Opcodes.DUP));
            openM.instructions.add(new InsnNode(Opcodes.ICONST_1));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            openM.instructions.add(new InsnNode(Opcodes.AASTORE));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                    false));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/nio/file/Path",
                    "resolve",
                    "(Ljava/nio/file/Path;)Ljava/nio/file/Path;",
                    true));
            openM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            openM.instructions.add(new JumpInsnNode(Opcodes.GOTO, doOpen));
            openM.instructions.add(noOverridePop);
            openM.instructions.add(new InsnNode(Opcodes.POP));
            openM.instructions.add(doOpen);
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            openM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            openM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            openM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "sunlitOpenOptional",
                    "(Ljava/nio/file/Path;)Ljava/util/Optional;",
                    false));
            openM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(openM);
            patchedVerify = true;
        }
        boolean hasSunlitReadBytes = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitReadBytes".equals(mn.name)) {
                hasSunlitReadBytes = true;
                break;
            }
        }
        if (!hasSunlitReadBytes) {
            MethodNode readM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "sunlitReadBytes",
                    "(Ljava/lang/String;)[B",
                    null,
                    null);
            LabelNode noOverridePop = new LabelNode();
            LabelNode doRead = new LabelNode();
            LabelNode retNull = new LabelNode();
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, JAR_CLS, "hasSecurityData", "()Z", false));
            readM.instructions.add(new JumpInsnNode(Opcodes.IFNE, retNull));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            readM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/util/function/BiFunction"));
            readM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, retNull));

            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            readM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            readM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                    false));
            readM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "nameOverrides", "Ljava/util/Map;"));
            readM.instructions.add(new InsnNode(Opcodes.DUP));
            readM.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
            readM.instructions.add(new JumpInsnNode(Opcodes.IFNE, noOverridePop));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
            readM.instructions.add(new InsnNode(Opcodes.DUP));
            readM.instructions.add(new JumpInsnNode(Opcodes.IFNULL, noOverridePop));
            readM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "toString", "()Ljava/lang/String;", false));
            readM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            readM.instructions.add(new LdcInsnNode("META-INF"));
            readM.instructions.add(new InsnNode(Opcodes.ICONST_2));
            readM.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            readM.instructions.add(new InsnNode(Opcodes.DUP));
            readM.instructions.add(new InsnNode(Opcodes.ICONST_0));
            readM.instructions.add(new LdcInsnNode("versions"));
            readM.instructions.add(new InsnNode(Opcodes.AASTORE));
            readM.instructions.add(new InsnNode(Opcodes.DUP));
            readM.instructions.add(new InsnNode(Opcodes.ICONST_1));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            readM.instructions.add(new InsnNode(Opcodes.AASTORE));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    UNION_CLS,
                    "getPath",
                    "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                    false));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/nio/file/Path",
                    "resolve",
                    "(Ljava/nio/file/Path;)Ljava/nio/file/Path;",
                    true));
            readM.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
            readM.instructions.add(new JumpInsnNode(Opcodes.GOTO, doRead));
            readM.instructions.add(noOverridePop);
            readM.instructions.add(new InsnNode(Opcodes.POP));
            readM.instructions.add(doRead);
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            readM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_CLS, "filesystem", "L" + UNION_CLS + ";"));
            readM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/BiFunction"));
            readM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            readM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            readM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/function/BiFunction",
                    "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true));
            readM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[B"));
            readM.instructions.add(new InsnNode(Opcodes.ARETURN));
            readM.instructions.add(retNull);
            readM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            readM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(readM);
            patchedVerify = true;
        }
        return (patched || patchedVerify) ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformJarModuleDataProvider(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        boolean hasApply = false;
        for (MethodNode mn : cn.methods) {
            if ("apply".equals(mn.name) && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(mn.desc)) {
                hasApply = true;
            }
            if ("open".equals(mn.name) && "(Ljava/lang/String;)Ljava/util/Optional;".equals(mn.desc)) {
                InsnList fast = new InsnList();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_PROVIDER_CLS, "jar", "L" + JAR_CLS + ";"));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, JAR_CLS, "sunlitOpen", "(Ljava/lang/String;)Ljava/util/Optional;", false));
                fast.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = fast;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patched = true;
            }
        }
        if (!hasApply) {
            if (cn.interfaces == null) {
                cn.interfaces = new java.util.ArrayList<>(1);
            }
            if (!cn.interfaces.contains("java/util/function/Function")) {
                cn.interfaces.add("java/util/function/Function");
            }
            MethodNode applyM = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    null,
                    null);
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            applyM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, JAR_PROVIDER_CLS, "jar", "L" + JAR_CLS + ";"));
            applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            applyM.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, JAR_CLS, "sunlitReadBytes", "(Ljava/lang/String;)[B", false));
            applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(applyM);
            patched = true;
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformJarModuleReader(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (MethodNode mn : cn.methods) {
            if ("apply".equals(mn.name) && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(mn.desc)) {
                return null;
            }
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>(1);
        }
        if (!cn.interfaces.contains("java/util/function/Function")) {
            cn.interfaces.add("java/util/function/Function");
        }
        MethodNode applyM = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null);
        LabelNode miss = new LabelNode();
        applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        applyM.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "cpw/mods/cl/JarModuleFinder$JarModuleReader",
                "jar",
                "Lcpw/mods/jarhandling/SecureJar$ModuleDataProvider;"));
        applyM.instructions.add(new InsnNode(Opcodes.DUP));
        applyM.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/util/function/Function"));
        applyM.instructions.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        applyM.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Function"));
        applyM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        applyM.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/function/Function",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
        applyM.instructions.add(miss);
        applyM.instructions.add(new InsnNode(Opcodes.POP));
        applyM.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        applyM.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(applyM);
        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformModernUiOutlineFont(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean hasField = false;
        for (FieldNode fn : cn.fields) {
            if ("sunlitRootFamily".equals(fn.name)) {
                hasField = true;
                break;
            }
        }
        if (!hasField) {
            cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlitRootFamily", "Ljava/lang/String;", null, null));
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"getFamilyName".equals(mn.name) || !"(Ljava/util/Locale;)Ljava/lang/String;".equals(mn.desc)) {
                continue;
            }
            LabelNode checkCached = new LabelNode();
            LabelNode computeRoot = new LabelNode();
            LabelNode fallback = new LabelNode();
            InsnList fast = new InsnList();
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new JumpInsnNode(Opcodes.IFNULL, checkCached));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/util/Locale", "ROOT", "Ljava/util/Locale;"));
            fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));
            fast.add(checkCached);
            fast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new FieldInsnNode(Opcodes.GETFIELD, OUTLINE_FONT_CLS, "sunlitRootFamily", "Ljava/lang/String;"));
            fast.add(new VarInsnNode(Opcodes.ASTORE, 2));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fast.add(new JumpInsnNode(Opcodes.IFNULL, computeRoot));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fast.add(new InsnNode(Opcodes.ARETURN));
            fast.add(computeRoot);
            fast.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[]{"java/lang/String"}, 0, null));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new FieldInsnNode(Opcodes.GETFIELD, OUTLINE_FONT_CLS, "mFont", "Ljava/awt/Font;"));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/awt/Font", "getFamily", "(Ljava/util/Locale;)Ljava/lang/String;", false));
            fast.add(new VarInsnNode(Opcodes.ASTORE, 2));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fast.add(new FieldInsnNode(Opcodes.PUTFIELD, OUTLINE_FONT_CLS, "sunlitRootFamily", "Ljava/lang/String;"));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fast.add(new InsnNode(Opcodes.ARETURN));
            fast.add(fallback);
            fast.add(new FrameNode(Opcodes.F_CHOP, 1, null, 0, null));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new FieldInsnNode(Opcodes.GETFIELD, OUTLINE_FONT_CLS, "mFont", "Ljava/awt/Font;"));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/awt/Font", "getFamily", "(Ljava/util/Locale;)Ljava/lang/String;", false));
            fast.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = fast;
            if (mn.tryCatchBlocks != null) {
                mn.tryCatchBlocks.clear();
            }
            if (mn.localVariables != null) {
                mn.localVariables.clear();
            }
            patched = true;
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformAttributeInstanceTemplates1(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"equals".equals(mn.name)
                    || !"(Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;)Z".equals(mn.desc)) {
                continue;
            }
            for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == Opcodes.DCMPL || insn.getOpcode() == Opcodes.DCMPG) {
                    it.set(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false));
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformModDirDiscoverer(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"scan".equals(mn.name) || !"(Ljava/nio/file/Path;)V".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode walkAnchor = null;
            int maxVar = 6;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof VarInsnNode v && v.var + 2 > maxVar) {
                    maxVar = v.var + 2;
                }
                if (walkAnchor == null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(min.owner)
                        && "walk".equals(min.name)) {
                    AbstractInsnNode p = insn.getPrevious();
                    for (int k = 0; k < 8 && p != null; k++, p = p.getPrevious()) {
                        if (p instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD && vin.var == 1) {
                            walkAnchor = p;
                            break;
                        }
                    }
                }
            }
            if (walkAnchor != null) {
                int listVar = maxVar;
                int idxVar = maxVar + 1;
                int sizeVar = maxVar + 2;
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                InsnList fast = new InsnList();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "filterServiceJars", "(Ljava/nio/file/Path;)Ljava/util/List;", false));
                fast.add(new VarInsnNode(Opcodes.ASTORE, listVar));
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new VarInsnNode(Opcodes.ISTORE, idxVar));
                fast.add(new VarInsnNode(Opcodes.ALOAD, listVar));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                fast.add(new VarInsnNode(Opcodes.ISTORE, sizeVar));
                fast.add(loopCond);
                fast.add(new VarInsnNode(Opcodes.ILOAD, idxVar));
                fast.add(new VarInsnNode(Opcodes.ILOAD, sizeVar));
                fast.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
                fast.add(new VarInsnNode(Opcodes.ALOAD, listVar));
                fast.add(new VarInsnNode(Opcodes.ILOAD, idxVar));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/nio/file/Path"));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, MOD_DISCOVERER_CLS, "visitFile", "(Ljava/nio/file/Path;)V", false));
                fast.add(new IincInsnNode(idxVar, 1));
                fast.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                fast.add(loopEnd);
                fast.add(new InsnNode(Opcodes.RETURN));
                mn.instructions.insertBefore(walkAnchor, fast);
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformAbstractJarLocator(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"scanMods".equals(mn.name) || !"()Ljava/util/List;".equals(mn.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/stream/Stream".equals(min.owner)
                        && "map".equals(min.name)) {
                    AbstractInsnNode next = nextReal(min);
                    if (next instanceof MethodInsnNode min2
                            && min2.getOpcode() == Opcodes.INVOKEINTERFACE
                            && "java/util/stream/Stream".equals(min2.owner)
                            && "toList".equals(min2.name)) {
                        mn.instructions.set(
                                min,
                                new MethodInsnNode(
                                        Opcodes.INVOKESTATIC,
                                        JAR_PAYLOAD_CLS,
                                        "mapStreamInParallel",
                                        "(Ljava/util/stream/Stream;Ljava/util/function/Function;)Ljava/util/List;",
                                        false));
                        mn.instructions.remove(min2);
                        patched = true;
                    }
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformAbstractModProvider(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"createMod".equals(mn.name)
                    || !"(Ljava/lang/String;[Ljava/nio/file/Path;)Lnet/minecraftforge/forgespi/locating/IModLocator$ModFileOrException;".equals(mn.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode idin
                        && "()Ljava/util/function/BiPredicate;".equals(idin.desc)) {
                    AbstractInsnNode n1 = nextReal(idin);
                    AbstractInsnNode n2 = nextReal(n1);
                    if (n2 instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "cpw/mods/jarhandling/SecureJar".equals(min.owner)
                            && "from".equals(min.name)) {
                        mn.instructions.set(idin, new InsnNode(Opcodes.ACONST_NULL));
                        patched = true;
                    }
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] widenClass(byte[] bytes, Set<String> fieldsToWiden, Set<String> invalidateMethods) {
        ClassNode cn = readNode(bytes);
        cn.access = makePublicAccess(cn.access);
        for (FieldNode fn : cn.fields) {
            if (fieldsToWiden.contains(fn.name)) {
                fn.access = makePublicAccess(fn.access);
            }
        }
        if (!invalidateMethods.isEmpty()) {
            for (MethodNode mn : cn.methods) {
                if (invalidateMethods.contains(mn.name)) {
                    InsnList clear = new InsnList();
                    clear.add(new InsnNode(Opcodes.ACONST_NULL));
                    clear.add(new FieldInsnNode(Opcodes.PUTSTATIC, MIXIN_PROCESSOR_CLS, "SUNLIT_SNAPSHOT", "[Ljava/lang/Object;"));
                    mn.instructions.insert(clear);
                }
            }
        }
        return writeNodeMaxs(cn);
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        AbstractInsnNode cur = insn.getNext();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        AbstractInsnNode cur = insn.getPrevious();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getPrevious();
        }
        return cur;
    }

    private static int makePublicAccess(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }

    private static ClassNode loadPayloadClass(String resourcePath) {
        try (InputStream is = SunlitBootTransformer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Missing payload resource: " + resourcePath);
            }
            ClassNode cn = new ClassNode();
            new ClassReader(is.readAllBytes()).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return cn;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load payload " + resourcePath, e);
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        throw new IllegalStateException("Missing payload method " + cn.name + "." + name + desc);
    }

    private static InsnList cloneStaticPayloadBody(
            MethodNode src,
            int[] argLocalSlots,
            int extraLocalBase,
            LabelNode returnTarget,
            String ownerFrom,
            String ownerTo) {
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : src.instructions) {
            if (insn instanceof LabelNode ln) {
                labels.put(ln, new LabelNode());
            }
        }
        InsnList out = new InsnList();
        int argCount = argLocalSlots != null ? argLocalSlots.length : 0;
        for (AbstractInsnNode insn : src.instructions) {
            if (returnTarget != null && insn.getOpcode() == Opcodes.RETURN) {
                out.add(new JumpInsnNode(Opcodes.GOTO, returnTarget));
                continue;
            }
            AbstractInsnNode copy = insn.clone(labels);
            if (argLocalSlots != null) {
                if (copy instanceof VarInsnNode vin) {
                    vin.var = vin.var < argCount ? argLocalSlots[vin.var] : extraLocalBase + (vin.var - argCount);
                } else if (copy instanceof IincInsnNode iin) {
                    iin.var = iin.var < argCount ? argLocalSlots[iin.var] : extraLocalBase + (iin.var - argCount);
                }
            }
            if (ownerFrom != null && ownerTo != null) {
                if (copy instanceof FieldInsnNode fin && ownerFrom.equals(fin.owner)) {
                    fin.owner = ownerTo;
                } else if (copy instanceof MethodInsnNode min && ownerFrom.equals(min.owner)) {
                    min.owner = ownerTo;
                }
            }
            out.add(copy);
        }
        return out;
    }

    public static byte[] transformDownloadUtils(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (FieldNode fn : cn.fields) {
            if ("sunlitShaProps".equals(fn.name)) {
                return null;
            }
        }
        MethodNode origGetSha1 = null;
        for (MethodNode mn : cn.methods) {
            if ("getSha1".equals(mn.name) && "(Ljava/io/File;)Ljava/lang/String;".equals(mn.desc)) {
                origGetSha1 = mn;
                break;
            }
        }
        if (origGetSha1 == null) {
            return null;
        }
        origGetSha1.name = "sunlitRawSha1";
        origGetSha1.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;

        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "sunlitShaProps",
                "Ljava/util/Properties;",
                null,
                null));

        ClassNode payloadCn = loadPayloadClass(DOWNLOAD_PAYLOAD_RES);
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitCachedGetSha1", "(Ljava/io/File;)Ljava/lang/String;"),
                "getSha1",
                DOWNLOAD_PAYLOAD_CLS,
                DOWNLOAD_UTILS_CLS));
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitGetProps", "()Ljava/util/Properties;"),
                "sunlitGetProps",
                DOWNLOAD_PAYLOAD_CLS,
                DOWNLOAD_UTILS_CLS));
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitPersistProps", "(Ljava/util/Properties;)V"),
                "sunlitPersistProps",
                DOWNLOAD_PAYLOAD_CLS,
                DOWNLOAD_UTILS_CLS));

        return writeNodeWithFrames(cn, loader != null ? loader : ClassLoader.getPlatformClassLoader());
    }

    public static byte[] transformPostProcessors(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (FieldNode fn : cn.fields) {
            if ("sunlitShaProps".equals(fn.name)) {
                return null;
            }
        }
        MethodNode origProcess = null;
        for (MethodNode mn : cn.methods) {
            if ("process".equals(mn.name)
                    && "(Ljava/io/File;Ljava/io/File;Ljava/io/File;Ljava/io/File;)Ljava/util/Set;".equals(mn.desc)) {
                origProcess = mn;
                break;
            }
        }
        if (origProcess == null) {
            return null;
        }
        origProcess.name = "sunlitRawProcess";
        origProcess.access = Opcodes.ACC_PRIVATE;

        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "sunlitShaProps",
                "Ljava/util/Properties;",
                null,
                null));

        ClassNode payloadCn = loadPayloadClass(DOWNLOAD_PAYLOAD_RES);
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitCachedProcess", "(Ljava/io/File;Ljava/io/File;Ljava/io/File;Ljava/io/File;)Ljava/util/Set;"),
                "process",
                DOWNLOAD_PAYLOAD_CLS,
                POST_PROCESSORS_CLS));
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitGetProps", "()Ljava/util/Properties;"),
                "sunlitGetProps",
                DOWNLOAD_PAYLOAD_CLS,
                POST_PROCESSORS_CLS));
        cn.methods.add(copyPayloadMethod(
                findMethod(payloadCn, "sunlitPersistProps", "(Ljava/util/Properties;)V"),
                "sunlitPersistProps",
                DOWNLOAD_PAYLOAD_CLS,
                POST_PROCESSORS_CLS));

        return writeNodeWithFrames(cn, loader != null ? loader : ClassLoader.getPlatformClassLoader());
    }

    private static MethodNode copyPayloadMethod(MethodNode src, String newName, String ownerFrom, String ownerTo) {
        String[] ex = src.exceptions == null ? null : src.exceptions.toArray(new String[0]);
        MethodNode dst = new MethodNode(src.access, newName, src.desc, src.signature, ex);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode insn : src.instructions) {
            if (insn instanceof LabelNode ln) {
                labels.put(ln, new LabelNode());
            }
        }
        for (AbstractInsnNode insn : src.instructions) {
            if (insn instanceof FrameNode) {
                continue;
            }
            AbstractInsnNode copy = insn.clone(labels);
            if (copy instanceof FieldInsnNode fin && ownerFrom.equals(fin.owner)) {
                fin.owner = ownerTo;
            } else if (copy instanceof MethodInsnNode min && ownerFrom.equals(min.owner)) {
                min.owner = ownerTo;
            } else if (copy instanceof LdcInsnNode lin
                    && lin.cst instanceof Type t
                    && ownerFrom.equals(t.getInternalName())) {
                lin.cst = Type.getObjectType(ownerTo);
            }
            dst.instructions.add(copy);
        }
        if (src.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : src.tryCatchBlocks) {
                dst.tryCatchBlocks.add(new TryCatchBlockNode(
                        labels.get(tcb.start), labels.get(tcb.end), labels.get(tcb.handler), tcb.type));
            }
        }
        return dst;
    }

    public static byte[] transformUnionPath(byte[] bytes) {
        return transformUnionPath(null, bytes, true);
    }

    public static byte[] transformUnionPath(ClassLoader loader, byte[] bytes, boolean canAddFields) {
        ClassNode cn = readNode(bytes);
        MethodNode partsMethod = null;
        MethodNode toStringMethod = null;
        MethodNode ctorVarargs = null;
        MethodNode normalizeMethod = null;
        for (MethodNode mn : cn.methods) {
            if ("getPathParts".equals(mn.name) && "(Ljava/lang/String;)[Ljava/lang/String;".equals(mn.desc)) {
                partsMethod = mn;
            } else if ("toString".equals(mn.name) && "()Ljava/lang/String;".equals(mn.desc)) {
                toStringMethod = mn;
            } else if ("normalize".equals(mn.name) && "()Ljava/nio/file/Path;".equals(mn.desc)) {
                normalizeMethod = mn;
            } else if ("<init>".equals(mn.name)
                    && "(Lcpw/mods/niofs/union/UnionFileSystem;[Ljava/lang/String;)V".equals(mn.desc)) {
                ctorVarargs = mn;
            }
        }
        if (partsMethod == null && toStringMethod == null && ctorVarargs == null && normalizeMethod == null) {
            return null;
        }
        boolean needsFrames = false;
        if (ctorVarargs != null && ctorVarargs.instructions != null) {
            AbstractInsnNode sbNew = null;
            for (AbstractInsnNode insn : ctorVarargs.instructions) {
                if (insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && "java/lang/StringBuilder".equals(tin.desc)) {
                    sbNew = tin;
                    break;
                }
            }
            if (sbNew != null) {
                LabelNode fallback = new LabelNode();
                InsnList fastCtor = new InsnList();
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 2));
                fastCtor.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "fastTryCleanSegments", "([Ljava/lang/String;)Z", false));
                fastCtor.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastCtor.add(new InsnNode(Opcodes.ICONST_0));
                fastCtor.add(new FieldInsnNode(Opcodes.PUTFIELD, UNION_PATH_CLS, "absolute", "Z"));
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 2));
                fastCtor.add(new FieldInsnNode(Opcodes.PUTFIELD, UNION_PATH_CLS, "pathParts", "[Ljava/lang/String;"));
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastCtor.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastCtor.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, UNION_PATH_CLS, "normalized", "Lcpw/mods/niofs/union/UnionPath;"));
                fastCtor.add(new InsnNode(Opcodes.RETURN));
                fastCtor.add(fallback);
                ctorVarargs.instructions.insertBefore(sbNew, fastCtor);
                needsFrames = true;
            }
        }
        if (normalizeMethod != null && normalizeMethod.instructions != null) {
            AbstractInsnNode dqNew = null;
            for (AbstractInsnNode insn : normalizeMethod.instructions) {
                if (insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && "java/util/ArrayDeque".equals(tin.desc)) {
                    dqNew = tin;
                    break;
                }
            }
            if (dqNew != null) {
                LabelNode slowNorm = new LabelNode();
                InsnList fastNorm = new InsnList();
                fastNorm.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastNorm.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "pathParts", "[Ljava/lang/String;"));
                fastNorm.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "hasNoDotSegments", "([Ljava/lang/String;)Z", false));
                fastNorm.add(new JumpInsnNode(Opcodes.IFEQ, slowNorm));
                fastNorm.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastNorm.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastNorm.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, UNION_PATH_CLS, "normalized", "Lcpw/mods/niofs/union/UnionPath;"));
                fastNorm.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fastNorm.add(new InsnNode(Opcodes.ARETURN));
                fastNorm.add(slowNorm);
                normalizeMethod.instructions.insertBefore(dqNew, fastNorm);
                needsFrames = true;
            }
        }
        if (partsMethod != null) {
            InsnList fast = new InsnList();
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAR_PAYLOAD_CLS,
                    "fastSplitPathParts",
                    "(Ljava/lang/String;)[Ljava/lang/String;",
                    false));
            fast.add(new InsnNode(Opcodes.ARETURN));
            partsMethod.instructions = fast;
            if (partsMethod.tryCatchBlocks != null) {
                partsMethod.tryCatchBlocks.clear();
            }
            if (partsMethod.localVariables != null) {
                partsMethod.localVariables.clear();
            }
        }
        boolean addedStringField = false;
        if (toStringMethod != null) {
            boolean hasStrField = false;
            for (FieldNode fn : cn.fields) {
                if ("sunlitString".equals(fn.name)) {
                    hasStrField = true;
                    break;
                }
            }
            if (!hasStrField && canAddFields) {
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, "sunlitString", "Ljava/lang/String;", null, null));
                hasStrField = true;
                addedStringField = true;
            }
            InsnList il = new InsnList();
            if (hasStrField) {
                LabelNode missLabel = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "sunlitString", "Ljava/lang/String;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(missLabel);
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "absolute", "Z"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "pathParts", "[Ljava/lang/String;"));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastUnionToString",
                        "(Z[Ljava/lang/String;)Ljava/lang/String;",
                        false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, UNION_PATH_CLS, "sunlitString", "Ljava/lang/String;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.ARETURN));
            } else {
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "absolute", "Z"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, UNION_PATH_CLS, "pathParts", "[Ljava/lang/String;"));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastUnionToString",
                        "(Z[Ljava/lang/String;)Ljava/lang/String;",
                        false));
                il.add(new InsnNode(Opcodes.ARETURN));
            }
            toStringMethod.instructions = il;
            if (toStringMethod.tryCatchBlocks != null) {
                toStringMethod.tryCatchBlocks.clear();
            }
            if (toStringMethod.localVariables != null) {
                toStringMethod.localVariables.clear();
            }
        }
        return (addedStringField || needsFrames) ? writeNodeWithFrames(cn, loader) : writeNodeMaxs(cn);
    }

    public static byte[] transformListenerListInst(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        for (FieldNode fn : cn.fields) {
            if ("sunlitHasListeners".equals(fn.name)) {
                return null;
            }
        }
        boolean hasPriorities = false;
        boolean hasWriteLock = false;
        boolean hasForceRebuild = false;
        for (FieldNode fn : cn.fields) {
            if ("priorities".equals(fn.name) && "Ljava/util/ArrayList;".equals(fn.desc)) {
                hasPriorities = true;
            } else if ("writeLock".equals(fn.name) && "Ljava/util/concurrent/Semaphore;".equals(fn.desc)) {
                hasWriteLock = true;
            }
        }
        for (MethodNode mn : cn.methods) {
            if ("forceRebuild".equals(mn.name) && "()V".equals(mn.desc)) {
                hasForceRebuild = true;
                break;
            }
        }
        if (!hasPriorities || !hasWriteLock || !hasForceRebuild) {
            return null;
        }

        MethodNode regMethod = null;
        MethodNode disposeMethod = null;
        MethodNode unregMethod = null;
        for (MethodNode mn : cn.methods) {
            if ("register".equals(mn.name)
                    && "(Lnet/minecraftforge/eventbus/api/EventPriority;Lnet/minecraftforge/eventbus/api/IEventListener;)V".equals(mn.desc)) {
                regMethod = mn;
            } else if ("dispose".equals(mn.name) && "()V".equals(mn.desc)) {
                disposeMethod = mn;
            } else if ("unregister".equals(mn.name)
                    && "(Lnet/minecraftforge/eventbus/api/IEventListener;)V".equals(mn.desc)) {
                unregMethod = mn;
            }
        }
        if (regMethod == null || unregMethod == null) {
            return null;
        }

        // Insert `this.sunlitHasListeners = true` right before writeLock.release() in register()
        AbstractInsnNode releaseGetLock = null;
        for (AbstractInsnNode insn : regMethod.instructions) {
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "java/util/concurrent/Semaphore".equals(min.owner)
                    && "release".equals(min.name)) {
                AbstractInsnNode prev1 = prevReal(min);
                AbstractInsnNode prev2 = prevReal(prev1);
                if (prev1 instanceof FieldInsnNode fin && "writeLock".equals(fin.name) && prev2 != null) {
                    releaseGetLock = prev2;
                    break;
                }
            }
        }
        if (releaseGetLock == null) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "sunlitHasListeners", "Z", null, null));

        InsnList markPresent = new InsnList();
        markPresent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        markPresent.add(new InsnNode(Opcodes.ICONST_1));
        markPresent.add(new FieldInsnNode(Opcodes.PUTFIELD, LISTENER_LIST_INST_CLS, "sunlitHasListeners", "Z"));
        regMethod.instructions.insertBefore(releaseGetLock, markPresent);

        if (disposeMethod != null) {
            for (ListIterator<AbstractInsnNode> it = disposeMethod.instructions.iterator(); it.hasNext(); ) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList clr = new InsnList();
                    clr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    clr.add(new InsnNode(Opcodes.ICONST_0));
                    clr.add(new FieldInsnNode(Opcodes.PUTFIELD, LISTENER_LIST_INST_CLS, "sunlitHasListeners", "Z"));
                    disposeMethod.instructions.insertBefore(insn, clr);
                }
            }
        }

        // Replace unregister(IEventListener) with lockless empty guard + direct ArrayList loop
        InsnList il = new InsnList();
        LabelNode hasAny = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchBlock = new LabelNode();
        LabelNode loopCond = new LabelNode();
        LabelNode loopNext = new LabelNode();
        LabelNode loopDone = new LabelNode();
        LabelNode skipRebuild = new LabelNode();

        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_LIST_INST_CLS, "sunlitHasListeners", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFNE, hasAny));
        il.add(new InsnNode(Opcodes.RETURN));

        il.add(hasAny);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_LIST_INST_CLS, "writeLock", "Ljava/util/concurrent/Semaphore;"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/Semaphore", "acquireUninterruptibly", "()V", false));

        il.add(tryStart);
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2)); // changed
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3)); // anyLeft
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_LIST_INST_CLS, "priorities", "Ljava/util/ArrayList;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4)); // pList
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 5)); // i
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 6)); // n

        il.add(loopCond);
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ILOAD, 6));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopDone));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/ArrayList"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 7)); // arr
        il.add(new VarInsnNode(Opcodes.ALOAD, 7));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "isEmpty", "()Z", false));
        il.add(new JumpInsnNode(Opcodes.IFNE, loopNext));
        il.add(new VarInsnNode(Opcodes.ALOAD, 7));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "remove", "(Ljava/lang/Object;)Z", false));
        LabelNode notRemoved = new LabelNode();
        il.add(new JumpInsnNode(Opcodes.IFEQ, notRemoved));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2)); // changed = true
        il.add(notRemoved);
        il.add(new VarInsnNode(Opcodes.ALOAD, 7));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "isEmpty", "()Z", false));
        il.add(new JumpInsnNode(Opcodes.IFNE, loopNext));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3)); // anyLeft = true

        il.add(loopNext);
        il.add(new IincInsnNode(5, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));

        il.add(loopDone);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, LISTENER_LIST_INST_CLS, "sunlitHasListeners", "Z"));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skipRebuild));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LISTENER_LIST_INST_CLS, "forceRebuild", "()V", false));
        il.add(skipRebuild);
        il.add(tryEnd);

        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_LIST_INST_CLS, "writeLock", "Ljava/util/concurrent/Semaphore;"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/Semaphore", "release", "()V", false));
        il.add(new InsnNode(Opcodes.RETURN));

        il.add(catchBlock);
        il.add(new VarInsnNode(Opcodes.ASTORE, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, LISTENER_LIST_INST_CLS, "writeLock", "Ljava/util/concurrent/Semaphore;"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/Semaphore", "release", "()V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new InsnNode(Opcodes.ATHROW));

        unregMethod.instructions = il;
        if (unregMethod.tryCatchBlocks == null) {
            unregMethod.tryCatchBlocks = new java.util.ArrayList<>();
        } else {
            unregMethod.tryCatchBlocks.clear();
        }
        unregMethod.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchBlock, null));
        if (unregMethod.localVariables != null) {
            unregMethod.localVariables.clear();
        }

        return writeNodeWithFrames(cn, loader);
    }

    public static byte[] transformMixinSquaredSlashReplace(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(min.owner)
                        && "replaceAll".equals(min.name)
                        && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                    AbstractInsnNode repArg = prevReal(min);
                    AbstractInsnNode patArg = prevReal(repArg);
                    if (patArg instanceof LdcInsnNode l1
                            && repArg instanceof LdcInsnNode l2
                            && "/".equals(l1.cst)
                            && ".".equals(l2.cst)) {
                        mn.instructions.set(patArg, new IntInsnNode(Opcodes.BIPUSH, '/'));
                        mn.instructions.set(repArg, new IntInsnNode(Opcodes.BIPUSH, '.'));
                        min.name = "replace";
                        min.desc = "(CC)Ljava/lang/String;";
                        patched = true;
                    }
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformMainAttributes(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"getJarAttributes".equals(mn.name) || !"(Ljava/io/File;)Ljava/util/jar/Attributes;".equals(mn.desc)) {
                continue;
            }
            LabelNode missLabel = new LabelNode();
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAR_PAYLOAD_CLS,
                    "getCachedJarAttributes",
                    "(Ljava/io/File;)Ljava/util/jar/Attributes;",
                    false));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
            hook.add(new InsnNode(Opcodes.ARETURN));
            hook.add(missLabel);
            hook.add(new InsnNode(Opcodes.POP));
            mn.instructions.insert(hook);
            patched = true;
            break;
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformCustomPotionBrewing(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"addMix".equals(mn.name)) {
                continue;
            }
            for (ListIterator<AbstractInsnNode> it = mn.instructions.iterator(); it.hasNext();) {
                AbstractInsnNode insn = it.next();
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && ("java/lang/Exception".equals(min.owner) || "java/lang/Throwable".equals(min.owner))
                        && "printStackTrace".equals(min.name)
                        && "()V".equals(min.desc)) {
                    it.set(new InsnNode(Opcodes.POP));
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformMinecraftRemapper(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"load".equals(mn.name)
                    || !"(Ljava/io/InputStream;Z)Ldev/latvian/mods/rhino/mod/util/MinecraftRemapper;".equals(mn.desc)) {
                continue;
            }
            InsnList wrap = new InsnList();
            wrap.add(new VarInsnNode(Opcodes.ALOAD, 0));
            wrap.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    JAR_PAYLOAD_CLS,
                    "ensureBuffered",
                    "(Ljava/io/InputStream;)Ljava/io/InputStream;",
                    false));
            wrap.add(new VarInsnNode(Opcodes.ASTORE, 0));
            mn.instructions.insert(wrap);
            patched = true;

            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "java/util/HashMap".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    mn.instructions.insertBefore(min, new LdcInsnNode(16384));
                    min.desc = "(I)V";
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformDeduplicator(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/util/concurrent/ConcurrentHashMap".equals(min.owner)
                            && "<init>".equals(min.name)
                            && "()V".equals(min.desc)) {
                        AbstractInsnNode next = nextReal(min);
                        if (next instanceof FieldInsnNode fin
                                && fin.getOpcode() == Opcodes.PUTSTATIC
                                && FERRITE_DEDUP_CLS.equals(fin.owner)
                                && "VARIANT_IDENTITIES".equals(fin.name)) {
                            mn.instructions.insertBefore(min, new IntInsnNode(Opcodes.SIPUSH, 4096));
                            min.desc = "(I)V";
                            patched = true;
                            break;
                        }
                    }
                }
            } else if ("deduplicateVariant".equals(mn.name)
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {
                InsnList il = new InsnList();
                LabelNode missLabel = new LabelNode();
                LabelNode returnArgLabel = new LabelNode();
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, FERRITE_DEDUP_CLS, "VARIANT_IDENTITIES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(missLabel);
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, FERRITE_DEDUP_CLS, "VARIANT_IDENTITIES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/Map",
                        "putIfAbsent",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, returnArgLabel));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(returnArgLabel);
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformDelegatingPackResources(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        String targetCls = "net/minecraftforge/resource/DelegatingPackResources";
        String packTypeCls = "net/minecraft/server/packs/PackType";
        String packResCls = "net/minecraft/server/packs/PackResources";
        String outDesc = "(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("m_8031_".equals(mn.name) && outDesc.equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode isData = new LabelNode();
                LabelNode checkMap = new LabelNode();
                LabelNode hasList = new LabelNode();
                LabelNode loopCond = new LabelNode();
                LabelNode done = new LabelNode();
                LabelNode fallback = new LabelNode();

                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new FieldInsnNode(Opcodes.GETSTATIC, packTypeCls, "CLIENT_RESOURCES", "L" + packTypeCls + ";"));
                fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, isData));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, targetCls, "namespacesAssets", "Ljava/util/Map;"));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 5));
                fast.add(new JumpInsnNode(Opcodes.GOTO, checkMap));

                fast.add(isData);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new FieldInsnNode(Opcodes.GETSTATIC, packTypeCls, "SERVER_DATA", "L" + packTypeCls + ";"));
                fast.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fallback));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, targetCls, "namespacesData", "Ljava/util/Map;"));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 5));

                fast.add(checkMap);
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
                fast.add(new VarInsnNode(Opcodes.ASTORE, 5));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new JumpInsnNode(Opcodes.IFNONNULL, hasList));
                fast.add(new InsnNode(Opcodes.RETURN));

                fast.add(hasList);
                fast.add(new InsnNode(Opcodes.ICONST_0));
                fast.add(new VarInsnNode(Opcodes.ISTORE, 6));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                fast.add(new VarInsnNode(Opcodes.ISTORE, 7));

                fast.add(loopCond);
                fast.add(new VarInsnNode(Opcodes.ILOAD, 6));
                fast.add(new VarInsnNode(Opcodes.ILOAD, 7));
                fast.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 5));
                fast.add(new VarInsnNode(Opcodes.ILOAD, 6));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                fast.add(new TypeInsnNode(Opcodes.CHECKCAST, packResCls));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 2));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 3));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 4));
                fast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, packResCls, "m_8031_", outDesc, true));
                fast.add(new IincInsnNode(6, 1));
                fast.add(new JumpInsnNode(Opcodes.GOTO, loopCond));

                fast.add(done);
                fast.add(new InsnNode(Opcodes.RETURN));
                fast.add(fallback);
                mn.instructions.insert(fast);
                patched = true;
                break;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformPathPackResources(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getPathFromLocation".equals(mn.name)
                    && "(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)[Ljava/lang/String;"
                            .equals(mn.desc)) {
                InsnList fast = new InsnList();
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/server/packs/PackType",
                        "m_10305_",
                        "()Ljava/lang/String;",
                        false));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135827_",
                        "()Ljava/lang/String;",
                        false));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135815_",
                        "()Ljava/lang/String;",
                        false));
                fast.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastPathFromLocation",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
                        false));
                fast.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = fast;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patched = true;
                break;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformModWorkManager(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("parallelExecutor".equals(mn.name) && "()Ljava/util/concurrent/Executor;".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && "net/minecraftforge/fml/loading/FMLConfig".equals(min.owner)
                            && "getIntConfigValue".equals(min.name)) {
                        AbstractInsnNode next = nextReal(min);
                        if (next instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ISTORE) {
                            int slot = vin.var;
                            InsnList boost = new InsnList();
                            boost.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false));
                            boost.add(new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "availableProcessors", "()I", false));
                            boost.add(new IntInsnNode(Opcodes.BIPUSH, 16));
                            boost.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false));
                            boost.add(new VarInsnNode(Opcodes.ILOAD, slot));
                            boost.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
                            boost.add(new VarInsnNode(Opcodes.ISTORE, slot));
                            mn.instructions.insert(vin, boost);
                            patched = true;
                            break;
                        }
                    }
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformAbstractOutputStreamAppender(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        final String owner = "org/apache/logging/log4j/core/appender/AbstractOutputStreamAppender";
        for (MethodNode mn : cn.methods) {
            boolean isDirect = "directEncodeEvent".equals(mn.name)
                    && "(Lorg/apache/logging/log4j/core/LogEvent;)V".equals(mn.desc);
            boolean isWriteArr = "writeByteArrayToManager".equals(mn.name)
                    && "(Lorg/apache/logging/log4j/core/LogEvent;)V".equals(mn.desc);
            if (!isDirect && !isWriteArr) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof VarInsnNode vin) || vin.getOpcode() != Opcodes.ALOAD || vin.var != 0) {
                    continue;
                }
                AbstractInsnNode fNode = nextReal(vin);
                if (!(fNode instanceof FieldInsnNode fin)
                        || fin.getOpcode() != Opcodes.GETFIELD
                        || !owner.equals(fin.owner)
                        || !"immediateFlush".equals(fin.name)
                        || !"Z".equals(fin.desc)) {
                    continue;
                }
                AbstractInsnNode ifne = nextReal(fin);
                AbstractInsnNode aload1 = ifne != null ? nextReal(ifne) : null;
                AbstractInsnNode batchCall = aload1 != null ? nextReal(aload1) : null;
                AbstractInsnNode ifeq = batchCall != null ? nextReal(batchCall) : null;
                if (!(ifne instanceof JumpInsnNode jFlush) || jFlush.getOpcode() != Opcodes.IFNE
                        || !(aload1 instanceof VarInsnNode v1) || v1.getOpcode() != Opcodes.ALOAD || v1.var != 1
                        || !(batchCall instanceof MethodInsnNode minBatch)
                        || !"org/apache/logging/log4j/core/LogEvent".equals(minBatch.owner)
                        || !"isEndOfBatch".equals(minBatch.name)
                        || !"()Z".equals(minBatch.desc)
                        || !(ifeq instanceof JumpInsnNode jSkip) || jSkip.getOpcode() != Opcodes.IFEQ) {
                    continue;
                }
                LabelNode notDebugFile = new LabelNode();
                InsnList patch = new InsnList();
                patch.add(new LdcInsnNode("DebugFile"));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, owner, "getName", "()Ljava/lang/String;", false));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                patch.add(new JumpInsnNode(Opcodes.IFEQ, notDebugFile));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "org/apache/logging/log4j/core/LogEvent",
                        "getLevel",
                        "()Lorg/apache/logging/log4j/Level;",
                        true));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "org/apache/logging/log4j/Level", "intLevel", "()I", false));
                patch.add(new IntInsnNode(Opcodes.SIPUSH, 300));
                patch.add(new JumpInsnNode(Opcodes.IF_ICMPGT, jSkip.label));
                patch.add(new JumpInsnNode(Opcodes.GOTO, jFlush.label));
                patch.add(notDebugFile);
                mn.instructions.insertBefore(vin, patch);
                patched = true;
                break;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    public static byte[] transformObjModel(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"parse".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof LdcInsnNode ldc) || !"/".equals(ldc.cst)) {
                    continue;
                }
                AbstractInsnNode i1 = nextReal(ldc);
                AbstractInsnNode i2 = i1 != null ? nextReal(i1) : null;
                AbstractInsnNode i3 = i2 != null ? nextReal(i2) : null;
                AbstractInsnNode i4 = i3 != null ? nextReal(i3) : null;
                AbstractInsnNode i5 = i4 != null ? nextReal(i4) : null;
                AbstractInsnNode i6 = i5 != null ? nextReal(i5) : null;
                AbstractInsnNode i7 = i6 != null ? nextReal(i6) : null;
                if (!(i1 instanceof MethodInsnNode splitCall)
                        || !"java/lang/String".equals(splitCall.owner)
                        || !"split".equals(splitCall.name)
                        || !"(Ljava/lang/String;)[Ljava/lang/String;".equals(splitCall.desc)
                        || !(i2 instanceof VarInsnNode ptsStore)
                        || ptsStore.getOpcode() != Opcodes.ASTORE
                        || !(i3 instanceof VarInsnNode ptsLoad)
                        || ptsLoad.getOpcode() != Opcodes.ALOAD
                        || ptsLoad.var != ptsStore.var
                        || !(i4 instanceof MethodInsnNode streamCall)
                        || !"java/util/Arrays".equals(streamCall.owner)
                        || !"stream".equals(streamCall.name)
                        || !(i5 instanceof InvokeDynamicInsnNode)
                        || !(i6 instanceof MethodInsnNode mapCall)
                        || !"java/util/stream/Stream".equals(mapCall.owner)
                        || !"mapToInt".equals(mapCall.name)
                        || !(i7 instanceof MethodInsnNode toArrCall)
                        || !"java/util/stream/IntStream".equals(toArrCall.owner)
                        || !"toArray".equals(toArrCall.name)) {
                    continue;
                }
                mn.instructions.insertBefore(
                        ldc,
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                JAR_PAYLOAD_CLS,
                                "fastParseObjFaceVertex",
                                "(Ljava/lang/String;)[I",
                                false));
                AbstractInsnNode[] removeList = {ldc, i1, i2, i3, i4, i5, i6, i7};
                for (AbstractInsnNode rem : removeList) {
                    mn.instructions.remove(rem);
                }
                patched = true;
                break;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static ClassNode readNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static byte[] writeNodeMaxs(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static final ConcurrentHashMap<String, TypeMeta> TYPE_META_CACHE = new ConcurrentHashMap<>(256);

    private record TypeMeta(boolean isInterface, String superName, String[] interfaces) {
    }

    private static TypeMeta resolveTypeMeta(String internalName, ClassLoader loader) {
        if (internalName == null) {
            return null;
        }
        TypeMeta cached = TYPE_META_CACHE.get(internalName);
        if (cached != null) {
            return cached;
        }
        ClassLoader cl = loader != null ? loader : ClassLoader.getPlatformClassLoader();
        InputStream opened = cl.getResourceAsStream(internalName + ".class");
        if (opened == null && cl != ClassLoader.getSystemClassLoader()) {
            opened = ClassLoader.getSystemResourceAsStream(internalName + ".class");
        }
        if (opened == null) {
            return null;
        }
        try (InputStream is = opened) {
            ClassReader cr = new ClassReader(is);
            TypeMeta meta = new TypeMeta(
                    (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0,
                    cr.getSuperName(),
                    cr.getInterfaces());
            if (TYPE_META_CACHE.size() < 4096) {
                TYPE_META_CACHE.putIfAbsent(internalName, meta);
            }
            return meta;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isMetaAssignable(String target, String candidate, ClassLoader loader, int depth) {
        if (target.equals(candidate)) {
            return true;
        }
        if ("java/lang/Object".equals(target)) {
            return true;
        }
        if (depth > 24 || candidate == null || "java/lang/Object".equals(candidate)) {
            return false;
        }
        TypeMeta meta = resolveTypeMeta(candidate, loader);
        if (meta == null) {
            return false;
        }
        if (meta.superName() != null && isMetaAssignable(target, meta.superName(), loader, depth + 1)) {
            return true;
        }
        String[] intfs = meta.interfaces();
        if (intfs != null) {
            for (int i = 0; i < intfs.length; i++) {
                if (isMetaAssignable(target, intfs[i], loader, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isStdLibType(String type) {
        return type.startsWith("java/") || type.startsWith("javax/") || type.startsWith("jdk/") || type.startsWith("sun/");
    }

    private static byte[] writeNodeWithFrames(ClassNode cn, ClassLoader loader) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                if (type1.equals(type2)) {
                    return type1;
                }
                if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                    return "java/lang/Object";
                }
                ClassLoader cl = loader != null ? loader : ClassLoader.getPlatformClassLoader();
                if (!isStdLibType(type1) || !isStdLibType(type2)) {
                    // Resolve class hierarchy from bytecode headers to avoid re-entrant Class.forName
                    // inside ClassFileTransformer (which causes JVMTI to skip transforming inner targets).
                    TypeMeta m1 = resolveTypeMeta(type1, cl);
                    TypeMeta m2 = resolveTypeMeta(type2, cl);
                    if (m1 != null && m2 != null) {
                        if (isMetaAssignable(type1, type2, cl, 0)) {
                            return type1;
                        }
                        if (isMetaAssignable(type2, type1, cl, 0)) {
                            return type2;
                        }
                        if (m1.isInterface() || m2.isInterface()) {
                            return "java/lang/Object";
                        }
                        String cur = m1.superName();
                        for (int depth = 0; depth < 24 && cur != null; depth++) {
                            if (isMetaAssignable(cur, type2, cl, 0)) {
                                return cur;
                            }
                            TypeMeta cm = resolveTypeMeta(cur, cl);
                            cur = cm != null ? cm.superName() : null;
                        }
                        return "java/lang/Object";
                    }
                    if (!isStdLibType(type1) || !isStdLibType(type2)) {
                        return "java/lang/Object";
                    }
                }
                try {
                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, cl);
                    Class<?> c2 = Class.forName(type2.replace('/', '.'), false, cl);
                    if (c1.isAssignableFrom(c2)) {
                        return type1;
                    }
                    if (c2.isAssignableFrom(c1)) {
                        return type2;
                    }
                    if (c1.isInterface() || c2.isInterface()) {
                        return "java/lang/Object";
                    }
                    do {
                        c1 = c1.getSuperclass();
                    } while (!c1.isAssignableFrom(c2));
                    return c1.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] transformMixinStringFormat(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/lang/String".equals(min.owner)
                        && "format".equals(min.name)
                        && "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;".equals(min.desc)) {
                    min.owner = JAR_PAYLOAD_CLS;
                    min.name = "fastMixinFormat";
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformAsmType(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getDescriptor".equals(mn.name) && "(Ljava/lang/Class;)Ljava/lang/String;".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "fastAsmDescriptor",
                        "(Ljava/lang/Class;)Ljava/lang/String;", false));
                mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                patched = true;
            } else if ("getInternalName".equals(mn.name) && "(Ljava/lang/Class;)Ljava/lang/String;".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "fastAsmInternalName",
                        "(Ljava/lang/Class;)Ljava/lang/String;", false));
                mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                patched = true;
            } else if ("appendDescriptor".equals(mn.name)
                    && "(Ljava/lang/Class;Ljava/lang/StringBuilder;)V".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, JAR_PAYLOAD_CLS, "fastAsmDescriptor",
                        "(Ljava/lang/Class;)Ljava/lang/String;", false));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                mn.instructions.add(new InsnNode(Opcodes.POP));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                patched = true;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformNamespacedWrapper(ClassLoader loader, byte[] bytes, boolean canAddMethods) {
        if (!canAddMethods) {
            return null;
        }
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        String wrapperOwner = "net/minecraftforge/registries/NamespacedWrapper";
        for (MethodNode mn : cn.methods) {
            if (!"onAdded".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "it/unimi/dsi/fastutil/objects/ObjectList".equals(min.owner)
                        && "size".equals(min.name)
                        && "(I)V".equals(min.desc)) {
                    min.setOpcode(Opcodes.INVOKESTATIC);
                    min.owner = wrapperOwner;
                    min.name = "sunlitResizeHolders";
                    min.desc = "(Lit/unimi/dsi/fastutil/objects/ObjectList;I)V";
                    min.itf = false;
                    patched = true;
                    break;
                }
            }
        }
        if (!patched) {
            return null;
        }
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlitResizeHolders",
                "(Lit/unimi/dsi/fastutil/objects/ObjectList;I)V",
                null,
                null);
        LabelNode skipGrow = new LabelNode();
        String arrList = "it/unimi/dsi/fastutil/objects/ObjectArrayList";
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, arrList));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skipGrow));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, arrList));
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, arrList, "elements", "()[Ljava/lang/Object;", false));
        helper.instructions.add(new InsnNode(Opcodes.ARRAYLENGTH));
        helper.instructions.add(new VarInsnNode(Opcodes.ISTORE, 3));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        helper.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPLE, skipGrow));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new IntInsnNode(Opcodes.SIPUSH, 256));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        helper.instructions.add(new InsnNode(Opcodes.ICONST_1));
        helper.instructions.add(new InsnNode(Opcodes.ISHL));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, arrList, "ensureCapacity", "(I)V", false));
        helper.instructions.add(skipGrow);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "it/unimi/dsi/fastutil/objects/ObjectList", "size", "(I)V", true));
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(helper);
        return writeNodeWithFrames(cn, loader);
    }

    private static byte[] transformDynamicTexturePack(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"addPackLogo".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(min.owner)
                        && "readAllBytes".equals(min.name)
                        && "(Ljava/nio/file/Path;)[B".equals(min.desc)) {
                    min.owner = JAR_PAYLOAD_CLS;
                    min.name = "fastReadModIconBytes";
                    patched = true;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformModuleClassLoader(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getClassBytes".equals(mn.name)
                    && "(Ljava/lang/module/ModuleReader;Ljava/lang/module/ModuleReference;Ljava/lang/String;)[B"
                            .equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastGetClassBytes",
                        "(Ljava/lang/module/ModuleReader;Ljava/lang/String;)[B",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.ARETURN));
                patched = true;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformModFile(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"findResource".equals(mn.name)
                    || !"([Ljava/lang/String;)Ljava/nio/file/Path;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            LdcInsnNode slashLdc = null;
            MethodInsnNode joinCall = null;
            MethodInsnNode getPathCall = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof LdcInsnNode ldc && "/".equals(ldc.cst)) {
                    slashLdc = ldc;
                } else if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/lang/String".equals(min.owner)
                        && "join".equals(min.name)
                        && "(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;".equals(min.desc)) {
                    joinCall = min;
                } else if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "cpw/mods/jarhandling/SecureJar".equals(min.owner)
                        && "getPath".equals(min.name)) {
                    getPathCall = min;
                }
            }
            if (slashLdc != null && joinCall != null && getPathCall != null) {
                InsnList repl = new InsnList();
                repl.add(new VarInsnNode(Opcodes.ALOAD, 1));
                repl.add(new InsnNode(Opcodes.ICONST_0));
                repl.add(new InsnNode(Opcodes.AALOAD));
                repl.add(new VarInsnNode(Opcodes.ALOAD, 1));
                repl.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastModFileTail",
                        "([Ljava/lang/String;)[Ljava/lang/String;",
                        false));
                mn.instructions.insertBefore(slashLdc, repl);
                // Remove everything from slashLdc through just before getPathCall (LDC "/", ALOAD 1, String.join, ICONST_0, ANEWARRAY String).
                AbstractInsnNode cur = slashLdc;
                while (cur != null && cur != getPathCall) {
                    AbstractInsnNode next = cur.getNext();
                    mn.instructions.remove(cur);
                    cur = next;
                }
                getPathCall.desc = "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;";
                patched = true;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformMethodMapper(byte[] bytes) {
        byte[] formatted = transformMixinStringFormat(bytes);
        byte[] src = formatted != null ? formatted : bytes;
        ClassNode cn = readNode(src);
        boolean patched = formatted != null;
        for (MethodNode mn : cn.methods) {
            if ("getClassUID".equals(mn.name) && "(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)) {
                InsnList body = new InsnList();
                body.add(new FieldInsnNode(Opcodes.GETSTATIC, MIXIN_METHOD_MAPPER_CLS, "classes", "Ljava/util/List;"));
                body.add(new VarInsnNode(Opcodes.ALOAD, 0));
                body.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastMixinClassUID",
                        "(Ljava/util/List;Ljava/lang/String;)Ljava/lang/String;",
                        false));
                body.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = body;
                mn.tryCatchBlocks = null;
                mn.localVariables = null;
                patched = true;
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformCupboardClientConfigCompat(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("similarity".equals(mn.name) && "(Ljava/lang/String;Ljava/lang/String;)D".equals(mn.desc)
                    && mn.instructions != null && mn.instructions.size() > 0) {
                InsnList head = new InsnList();
                LabelNode cont = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "shouldSkipCupboardSimilarity",
                        "(Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                head.add(new InsnNode(Opcodes.DCONST_0));
                head.add(new InsnNode(Opcodes.DRETURN));
                head.add(cont);
                mn.instructions.insert(head);
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static byte[] transformFancyPropertiesParser(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            if ("deserializeSetFromFile".equals(mn.name)
                    && "(Ljava/lang/String;)Lde/keksuccino/fancymenu/util/properties/PropertyContainerSet;".equals(mn.desc)) {
                MethodInsnNode getLinesCall = null;
                MethodInsnNode deserCall = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min) {
                        if ("de/keksuccino/fancymenu/util/file/FileUtils".equals(min.owner)
                                && "getFileLines".equals(min.name)) {
                            getLinesCall = min;
                        } else if (getLinesCall != null
                                && "de/keksuccino/fancymenu/util/properties/PropertiesParser".equals(min.owner)
                                && "deserializeSetFromFancyString".equals(min.name)) {
                            deserCall = min;
                            break;
                        }
                    }
                }
                if (getLinesCall != null && deserCall != null) {
                    AbstractInsnNode cur = getLinesCall.getNext();
                    while (cur != null && cur != deserCall) {
                        AbstractInsnNode next = cur.getNext();
                        mn.instructions.remove(cur);
                        cur = next;
                    }
                    mn.instructions.insertBefore(
                            deserCall,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    JAR_PAYLOAD_CLS,
                                    "fastJoinFancyLines",
                                    "(Ljava/util/List;)Ljava/lang/String;",
                                    false));
                    patched = true;
                }
            } else if ("deserializeSetFromFancyString".equals(mn.name)) {
                java.util.List<AbstractInsnNode> toRemove = new java.util.ArrayList<>();
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof LdcInsnNode ldc && "[\\p{Z}\\s]+".equals(ldc.cst)) {
                        AbstractInsnNode n1 = ldc.getNext();
                        AbstractInsnNode n2 = n1 != null ? n1.getNext() : null;
                        if (n1 instanceof LdcInsnNode empty && "".equals(empty.cst)
                                && n2 instanceof MethodInsnNode rep
                                && "java/lang/String".equals(rep.owner)
                                && "replaceAll".equals(rep.name)
                                && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(rep.desc)) {
                            mn.instructions.insertBefore(
                                    ldc,
                                    new MethodInsnNode(
                                            Opcodes.INVOKESTATIC,
                                            JAR_PAYLOAD_CLS,
                                            "fastStripFancyWhitespace",
                                            "(Ljava/lang/String;)Ljava/lang/String;",
                                            false));
                            toRemove.add(ldc);
                            toRemove.add(n1);
                            toRemove.add(n2);
                            patched = true;
                        }
                    }
                }
                for (AbstractInsnNode rem : toRemove) {
                    mn.instructions.remove(rem);
                }
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static byte[] transformDomesticationLayerManager(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"addLayerIfApplicable".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "net/minecraftforge/client/event/EntityRenderersEvent$AddLayers".equals(min.owner)
                        && "getRenderer".equals(min.name)) {
                    InsnList repl = new InsnList();
                    LabelNode fallback = new LabelNode();
                    LabelNode done = new LabelNode();
                    repl.add(new InsnNode(Opcodes.DUP2));
                    repl.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            JAR_PAYLOAD_CLS,
                            "fastSafeGetLivingRenderer",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            false));
                    repl.add(new InsnNode(Opcodes.DUP));
                    repl.add(new FieldInsnNode(
                            Opcodes.GETSTATIC,
                            JAR_PAYLOAD_CLS,
                            "RENDERER_LOOKUP_FAILED",
                            "Ljava/lang/Object;"));
                    repl.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, fallback));
                    repl.add(new InsnNode(Opcodes.DUP_X2));
                    repl.add(new InsnNode(Opcodes.POP));
                    repl.add(new InsnNode(Opcodes.POP2));
                    repl.add(new TypeInsnNode(
                            Opcodes.CHECKCAST,
                            "net/minecraft/client/renderer/entity/LivingEntityRenderer"));
                    repl.add(new JumpInsnNode(Opcodes.GOTO, done));
                    repl.add(fallback);
                    repl.add(new InsnNode(Opcodes.POP));
                    repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, min.owner, min.name, min.desc, false));
                    repl.add(done);
                    mn.instructions.insertBefore(min, repl);
                    mn.instructions.remove(min);
                    patched = true;
                }
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static byte[] transformSpriteFinderImpl(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        String owner = "net/fabricmc/fabric/impl/renderer/SpriteFinderImpl";
        String nodeCls = "net/fabricmc/fabric/impl/renderer/SpriteFinderImpl$Node";
        String spriteCls = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
        boolean hasField = false;
        for (FieldNode fn : cn.fields) {
            if ("sunlitPendingSprites".equals(fn.name)) {
                return null;
            }
            if ("root".equals(fn.name)) {
                fn.access = (fn.access & ~Opcodes.ACC_FINAL) | Opcodes.ACC_VOLATILE;
                hasField = true;
            }
        }
        if (!hasField) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlitPendingSprites", "[Ljava/lang/Object;", null, null));

        boolean patchedInit = false;
        boolean patchedFind = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)
                    && "(Ljava/util/Map;Lnet/minecraft/client/renderer/texture/TextureAtlas;)V".equals(mn.desc)) {
                InsnList initBody = new InsnList();
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initBody.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initBody.add(new InsnNode(Opcodes.ICONST_0));
                initBody.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "badSpriteCount", "I"));
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 2));
                initBody.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        owner,
                        "spriteAtlasTexture",
                        "Lnet/minecraft/client/renderer/texture/TextureAtlas;"));
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
                initBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
                initBody.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "values", "()Ljava/util/Collection;", true));
                initBody.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Collection", "toArray", "()[Ljava/lang/Object;", true));
                initBody.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "sunlitPendingSprites", "[Ljava/lang/Object;"));
                initBody.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = initBody;
                mn.tryCatchBlocks = null;
                mn.localVariables = null;
                patchedInit = true;
            } else if ("find".equals(mn.name)
                    && "(FF)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode fin
                            && fin.getOpcode() == Opcodes.GETFIELD
                            && owner.equals(fin.owner)
                            && "root".equals(fin.name)) {
                        InsnList ensure = new InsnList();
                        LabelNode ready = new LabelNode();
                        ensure.add(new InsnNode(Opcodes.DUP));
                        ensure.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
                        ensure.add(new InsnNode(Opcodes.POP));
                        ensure.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        ensure.add(new MethodInsnNode(
                                Opcodes.INVOKESPECIAL,
                                owner,
                                "sunlitEnsureRoot",
                                "()L" + nodeCls + ";",
                                false));
                        ensure.add(ready);
                        mn.instructions.insert(fin, ensure);
                        patchedFind = true;
                    }
                }
            }
        }
        if (!patchedInit || !patchedFind) {
            return null;
        }

        MethodNode ensureRoot = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED,
                "sunlitEnsureRoot",
                "()L" + nodeCls + ";",
                null,
                null);
        InsnList il = ensureRoot.instructions;
        LabelNode needBuild = new LabelNode();
        LabelNode loopCond = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "root", "L" + nodeCls + ";"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new JumpInsnNode(Opcodes.IFNULL, needBuild));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(needBuild);
        il.add(new InsnNode(Opcodes.POP));
        il.add(new TypeInsnNode(Opcodes.NEW, nodeCls));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new LdcInsnNode(Float.valueOf(0.5F)));
        il.add(new LdcInsnNode(Float.valueOf(0.5F)));
        il.add(new LdcInsnNode(Float.valueOf(0.25F)));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, nodeCls, "<init>", "(L" + owner + ";FFF)V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "sunlitPendingSprites", "[Ljava/lang/Object;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFNULL, loopEnd));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new VarInsnNode(Opcodes.ISTORE, 4));
        il.add(loopCond);
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, spriteCls));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, nodeCls, "add", "(L" + spriteCls + ";)V", false));
        il.add(new IincInsnNode(3, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
        il.add(loopEnd);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "sunlitPendingSprites", "[Ljava/lang/Object;"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "root", "L" + nodeCls + ";"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(ensureRoot);
        return writeNodeWithFrames(cn, loader);
    }

    private static byte[] transformBlockIdMapper(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<clinit>".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "net/minecraft/core/IdMapper".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    mn.instructions.insertBefore(min, new LdcInsnNode(Integer.valueOf(131072)));
                    min.desc = "(I)V";
                    patched = true;
                    break;
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformListenerList(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        String owner = "net/minecraftforge/eventbus/ListenerList";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("resize".equals(mn.name) && "(I)V".equals(mn.desc)
                    && mn.instructions != null && mn.instructions.size() > 0) {
                InsnList head = new InsnList();
                LabelNode needGrow = new LabelNode();
                LabelNode nonZero = new LabelNode();
                LabelNode haveDoubled = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "maxSize", "I"));
                head.add(new JumpInsnNode(Opcodes.IF_ICMPGT, needGrow));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(needGrow);
                head.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "maxSize", "I"));
                head.add(new InsnNode(Opcodes.DUP));
                head.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
                head.add(new InsnNode(Opcodes.POP));
                head.add(new IntInsnNode(Opcodes.BIPUSH, 64));
                head.add(new JumpInsnNode(Opcodes.GOTO, haveDoubled));
                head.add(nonZero);
                head.add(new InsnNode(Opcodes.ICONST_1));
                head.add(new InsnNode(Opcodes.ISHL));
                head.add(haveDoubled);
                head.add(new VarInsnNode(Opcodes.ILOAD, 0));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false));
                head.add(new VarInsnNode(Opcodes.ISTORE, 0));
                mn.instructions.insert(head);
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }

    private static byte[] transformMixinProfiler(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        String owner = "org/spongepowered/asm/util/perf/Profiler";
        String sectionCls = "org/spongepowered/asm/util/perf/Profiler$Section";
        String disabledCls = "org/spongepowered/asm/util/perf/Profiler$DisabledSection";
        for (FieldNode fn : cn.fields) {
            if ("sunlitNoop".equals(fn.name)) {
                return null;
            }
            if ("active".equals(fn.name)) {
                fn.access |= Opcodes.ACC_VOLATILE;
            }
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "sunlitNoop",
                "L" + sectionCls + ";",
                null,
                null));

        boolean patchedInit = false;
        java.util.List<MethodNode> newMethods = new java.util.ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "(Ljava/lang/String;)V".equals(mn.desc)) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList initNoop = new InsnList();
                        initNoop.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        initNoop.add(new TypeInsnNode(Opcodes.NEW, disabledCls));
                        initNoop.add(new InsnNode(Opcodes.DUP));
                        initNoop.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        initNoop.add(new LdcInsnNode("disabled"));
                        initNoop.add(new MethodInsnNode(
                                Opcodes.INVOKESPECIAL, disabledCls, "<init>", "(L" + owner + ";Ljava/lang/String;)V", false));
                        initNoop.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL, sectionCls, "invalidate", "()L" + sectionCls + ";", false));
                        initNoop.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "sunlitNoop", "L" + sectionCls + ";"));
                        mn.instructions.insertBefore(insn, initNoop);
                        patchedInit = true;
                    }
                }
            } else if (("get".equals(mn.name) && ("(Ljava/lang/String;)L" + sectionCls + ";").equals(mn.desc))
                    || ("begin".equals(mn.name) && ("(ILjava/lang/String;)L" + sectionCls + ";").equals(mn.desc))) {
                String slowName = "get".equals(mn.name) ? "sunlitGetSlow" : "sunlitBeginSlow";
                String desc = mn.desc;
                int origAccess = mn.access;
                mn.name = slowName;
                mn.access = (origAccess & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;

                MethodNode wrapper = new MethodNode(
                        origAccess & ~Opcodes.ACC_SYNCHRONIZED,
                        "sunlitGetSlow".equals(slowName) ? "get" : "begin",
                        desc,
                        null,
                        null);
                LabelNode isActive = new LabelNode();
                wrapper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "active", "Z"));
                wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNE, isActive));
                wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                wrapper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "sunlitNoop", "L" + sectionCls + ";"));
                wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
                wrapper.instructions.add(isActive);
                wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if ("get".equals(wrapper.name)) {
                    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                } else {
                    wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                }
                wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, slowName, desc, false));
                wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
                newMethods.add(wrapper);
            } else if ("begin".equals(mn.name)
                    && (("(Ljava/lang/String;)L" + sectionCls + ";").equals(mn.desc)
                            || ("([Ljava/lang/String;)L" + sectionCls + ";").equals(mn.desc)
                            || ("(I[Ljava/lang/String;)L" + sectionCls + ";").equals(mn.desc))) {
                InsnList fast = new InsnList();
                LabelNode isActive = new LabelNode();
                fast.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "active", "Z"));
                fast.add(new JumpInsnNode(Opcodes.IFNE, isActive));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "sunlitNoop", "L" + sectionCls + ";"));
                fast.add(new InsnNode(Opcodes.ARETURN));
                fast.add(isActive);
                mn.instructions.insert(fast);
            } else if ("end".equals(mn.name) && ("(L" + sectionCls + ";)V").equals(mn.desc)) {
                InsnList fast = new InsnList();
                LabelNode isActive = new LabelNode();
                fast.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "active", "Z"));
                fast.add(new JumpInsnNode(Opcodes.IFNE, isActive));
                fast.add(new InsnNode(Opcodes.RETURN));
                fast.add(isActive);
                mn.instructions.insert(fast);
            }
        }
        if (!patchedInit || newMethods.isEmpty()) {
            return null;
        }
        cn.methods.addAll(newMethods);
        return writeNodeWithFrames(cn, loader);
    }

    private static byte[] transformStateDefinitionBuilder(byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!("(Lnet/minecraft/world/level/block/state/properties/Property;)V".equals(mn.desc))
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.GETSTATIC
                        && "net/minecraft/world/level/block/state/StateDefinition".equals(fin.owner)
                        && "Ljava/util/regex/Pattern;".equals(fin.desc)) {
                    AbstractInsnNode a1 = nextReal(fin);
                    AbstractInsnNode m1 = a1 != null ? nextReal(a1) : null;
                    AbstractInsnNode m2 = m1 != null ? nextReal(m1) : null;
                    if (a1 instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD
                            && m1 instanceof MethodInsnNode matcherCall
                            && "java/util/regex/Pattern".equals(matcherCall.owner)
                            && "matcher".equals(matcherCall.name)
                            && m2 instanceof MethodInsnNode matchesCall
                            && "java/util/regex/Matcher".equals(matchesCall.owner)
                            && "matches".equals(matchesCall.name)) {
                        mn.instructions.insertBefore(
                                matchesCall,
                                new MethodInsnNode(
                                        Opcodes.INVOKESTATIC,
                                        JAR_PAYLOAD_CLS,
                                        "isValidStateName",
                                        "(Ljava/lang/String;)Z",
                                        false));
                        mn.instructions.remove(fin);
                        mn.instructions.remove(matcherCall);
                        mn.instructions.remove(matchesCall);
                        patched = true;
                    }
                }
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    private static byte[] transformIdMapper(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"(Ljava/lang/Object;I)V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            MethodInsnNode setCall = null;
            FieldInsnNode listField = null;
            AbstractInsnNode startLoad = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (startLoad == null
                        && insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.GETFIELD
                        && "net/minecraft/core/IdMapper".equals(fin.owner)
                        && "Ljava/util/List;".equals(fin.desc)
                        && prevReal(insn) instanceof VarInsnNode prev
                        && prev.getOpcode() == Opcodes.ALOAD
                        && prev.var == 0) {
                    startLoad = prev;
                    listField = fin;
                } else if (startLoad != null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(min.owner)
                        && "set".equals(min.name)
                        && "(ILjava/lang/Object;)Ljava/lang/Object;".equals(min.desc)) {
                    AbstractInsnNode next = nextReal(min);
                    if (next != null && next.getOpcode() == Opcodes.POP) {
                        setCall = min;
                        break;
                    }
                }
            }
            if (startLoad != null && listField != null && setCall != null) {
                AbstractInsnNode popAfterSet = nextReal(setCall);
                InsnList repl = new InsnList();
                repl.add(new VarInsnNode(Opcodes.ALOAD, 0));
                repl.add(new FieldInsnNode(
                        Opcodes.GETFIELD, listField.owner, listField.name, "Ljava/util/List;"));
                repl.add(new VarInsnNode(Opcodes.ILOAD, 2));
                repl.add(new VarInsnNode(Opcodes.ALOAD, 1));
                repl.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        JAR_PAYLOAD_CLS,
                        "fastIdMapperAddToList",
                        "(Ljava/util/List;ILjava/lang/Object;)V",
                        false));
                mn.instructions.insertBefore(startLoad, repl);
                AbstractInsnNode end = popAfterSet != null ? popAfterSet.getNext() : setCall.getNext();
                AbstractInsnNode cur = startLoad;
                while (cur != null && cur != end) {
                    AbstractInsnNode next = cur.getNext();
                    mn.instructions.remove(cur);
                    cur = next;
                }
                patched = true;
            }
        }
        return patched ? writeNodeWithFrames(cn, loader) : null;
    }
}

