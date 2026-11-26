package dev.sunlit.bootagent;

import dev.sunlit.bootagent.payload.BootJarPayload;
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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Modular Batch 24 transformer for startup-critical targets:
 * <ul>
 *   <li>Generates {@code com.google.common.collect.SunlitFlatStateMap} for zero-entry BlockState property maps.</li>
 *   <li>Eliminates {@code HashSet} copies in {@code java.lang.module.Configuration.reads}.</li>
 *   <li>Replaces ETF {@code Plugin.hasClass} bytecode parsing with classloader resource existence checks.</li>
 *   <li>Synchronizes {@code RemappingHelper.getMinecraftRemapper} for safe background pre-warming.</li>
 * </ul>
 */
public final class SunlitExtraTransformer implements ClassFileTransformer {
    private static final String CONFIG_CLS = "java/lang/module/Configuration";
    private static final String ETF_PLUGIN_CLS = "traben/entity_texture_features/mixin/Plugin";
    private static final String RHINO_REMAP_CLS = "dev/latvian/mods/rhino/mod/util/RemappingHelper";
    private static final String JDK_ZIPPATH_CLS = "jdk/nio/zipfs/ZipPath";
    private static final String MF_SCAN_COMPACTOR_CLS = "org/embeddedt/modernfix/forge/load/ModFileScanDataCompactor";
    private static final String CLIENT_LANG_CLS = "net/minecraft/client/resources/language/ClientLanguage";
    private static final String FLW_BACKEND_MGR_CLS = "dev/engine_room/flywheel/impl/BackendManagerImpl";
    private static final String MOONLIGHT_RES_TX_CLS = "net/mehvahdjukaar/moonlight/api/resources/BlockTypeResTransformer";
    private static final String EMF_RES_CACHING_CLS = "traben/entity_model_features/utils/EMFResourceCaching";
    private static final String MC_BOOTSTRAP_CLS = "net/minecraft/server/Bootstrap";
    private static final String CARRYON_LIST_HANDLER_CLS = "tschipp/carryon/common/config/ListHandler";
    private static final String EVERYCOMP_PENDING_CLS =
            "net/mehvahdjukaar/every_compat/misc/HardcodedBlockType$PendingBlockInfo";
    private static final String MCLIB_MATH_BUILDER_CLS = "com/eliotlash/mclib/math/MathBuilder";
    private static final String EMF_MAPPINGS_CLS = "traben/entity_model_features/models/EMFModelMappings";
    private static final String EMF_MANAGER_CLS = "traben/entity_model_features/EMFManager";
    private static final String EMF_MATH_PARSER_CLS =
            "traben/entity_model_features/models/animation/math/expression_tree/MathExpressionParser";
    private static final String EMF_VAR_FACTORY_CLS =
            "traben/entity_model_features/models/animation/math/variables/factories/ModelVariableFactory";
    private static final String FUSION_ENTITY_LOADER_CLS =
            "com/supermartijn642/fusion/entity/model/loader/FusionEntityModelLoader";
    private static final String CSC_MATH_CLS = "com/Pink_Cats/createschematicchecker/core/attach/Math";
    private static final String CSC_TOML_EDITOR_CLS =
            "com/Pink_Cats/createschematicchecker/FancyConfig/SimpleTomlEditor";
    private static final String FM_LOADING_REQ_CLS =
            "de/keksuccino/fancymenu/customization/loadingrequirement/internal/LoadingRequirementContainer";
    private static final String EMBERBIUM_MODULE_SCANNER_CLS =
            "me/jellysquid/mods/sodium/client/compatibility/checks/ModuleScanner";
    private static final String WGL_CLS = "org/lwjgl/opengl/WGL";
    private static final String USER32_CLS = "org/lwjgl/system/windows/User32";
    private static final String AMENDMENTS_WALL_LANTERN_CLS =
            "net/mehvahdjukaar/amendments/common/block/WallLanternBlock";
    private static final String EMF_JEM_DATA_CLS =
            "traben/entity_model_features/models/jem_objects/EMFJemData";
    private static final String KJS_ICON_PROVIDER_CLS =
            "dev/latvian/mods/kubejs/core/WindowKJS$KJSScaledIconProvider";
    private static final String MOONLIGHT_PATH_TRIE_CLS =
            "net/mehvahdjukaar/moonlight/api/misc/PathTrie";
    private static final String RHINO_JAVA_MEMBERS_CLS =
            "dev/latvian/mods/rhino/JavaMembers";
    private static final String BLUEPRINT_REMOLDED_CLS =
            "com/teamabnormals/blueprint/common/remolder/RemoldedResourceManager";
    private static final String TANUKI_ROTATING_CLS = "tanukidecor/block/RotatingBlock";
    private static final String TANUKI_MULTIBLOCK_CLS = "tanukidecor/block/RotatingMultiblock";
    private static final String TANUKI_TALL_ROTATING_CLS = "tanukidecor/block/RotatingTallBlock";
    private static final String TANUKI_WIDE_CLS = "tanukidecor/block/RotatingWideBlock";
    private static final String TANUKI_TALL_CLS = "tanukidecor/block/TallBlock";
    private static final String TANUKI_PLASMA_CLS = "tanukidecor/block/misc/PlasmaBallBlock";
    private static final String PAYLOAD_CLS = "dev/sunlit/bootagent/payload/BootJarPayload";
    private static Instrumentation extraInst;
    private static Module extraPayloadModule;

    private SunlitExtraTransformer() {
    }

    public static void install(Instrumentation inst, Module payloadModule) {
        extraInst = inst;
        extraPayloadModule = payloadModule;
        BootJarPayload.setFlatStateMapBytes(buildFlatStateMapBytes());
        if (inst == null) {
            return;
        }
        SunlitExtraTransformer transformer = new SunlitExtraTransformer();
        inst.addTransformer(transformer, true);
        try {
            Class<?> cfgCls = java.lang.module.Configuration.class;
            if (payloadModule != null) {
                Module base = cfgCls.getModule();
                if (base != null && base.isNamed() && !base.canRead(payloadModule)) {
                    inst.redefineModule(
                            base,
                            java.util.Set.of(payloadModule),
                            java.util.Map.of(),
                            java.util.Map.of(),
                            java.util.Set.of(),
                            java.util.Map.of());
                }
                ModuleLayer.boot().findModule("jdk.zipfs").ifPresent(zipfs -> {
                    if (zipfs.isNamed() && !zipfs.canRead(payloadModule)) {
                        inst.redefineModule(
                                zipfs,
                                java.util.Set.of(payloadModule),
                                java.util.Map.of(),
                                java.util.Map.of(),
                                java.util.Set.of(),
                                java.util.Map.of());
                    }
                });
            }
            inst.retransformClasses(cfgCls);
            for (Class<?> loadedCls : inst.getAllLoadedClasses()) {
                if ("jdk.nio.zipfs.ZipPath".equals(loadedCls.getName())) {
                    inst.retransformClasses(loadedCls);
                    break;
                }
            }
        } catch (Throwable ignored) {
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
        if (className == null || classfileBuffer == null) {
            return null;
        }
        return switch (className) {
            case CONFIG_CLS -> transformConfiguration(loader, classfileBuffer);
            case ETF_PLUGIN_CLS -> transformEtfPlugin(loader, classfileBuffer);
            case RHINO_REMAP_CLS -> transformRemappingHelper(classfileBuffer);
            case JDK_ZIPPATH_CLS -> transformZipPath(loader, classfileBuffer, classBeingRedefined == null);
            case MF_SCAN_COMPACTOR_CLS -> transformScanDataCompactor(module, classfileBuffer, classBeingRedefined == null);
            case CLIENT_LANG_CLS -> transformClientLanguage(loader, classfileBuffer);
            case FLW_BACKEND_MGR_CLS -> transformFlywheelBackendManager(classfileBuffer);
            case MOONLIGHT_RES_TX_CLS -> transformMoonlightResTransformer(module, classfileBuffer);
            case EMF_RES_CACHING_CLS -> transformEmfResourceCaching(classfileBuffer);
            case MC_BOOTSTRAP_CLS -> transformMinecraftBootstrap(module, classfileBuffer);
            case CARRYON_LIST_HANDLER_CLS -> transformCarryOnListHandler(module, classfileBuffer);
            case EVERYCOMP_PENDING_CLS,
                 MCLIB_MATH_BUILDER_CLS,
                 EMF_MAPPINGS_CLS,
                 EMF_MANAGER_CLS,
                 EMF_MATH_PARSER_CLS,
                 EMF_VAR_FACTORY_CLS,
                 FUSION_ENTITY_LOADER_CLS,
                 CSC_MATH_CLS,
                 CSC_TOML_EDITOR_CLS,
                 FM_LOADING_REQ_CLS,
                 MOONLIGHT_PATH_TRIE_CLS -> transformStringMatches(module, classfileBuffer);
            case EMBERBIUM_MODULE_SCANNER_CLS -> transformEmberbiumModuleScanner(module, classfileBuffer);
            case WGL_CLS, USER32_CLS -> classBeingRedefined == null
                    ? restoreLegacyWindowsBindings(className, classfileBuffer) : null;
            case AMENDMENTS_WALL_LANTERN_CLS -> transformWallLanternBlock(module, classfileBuffer);
            case EMF_JEM_DATA_CLS -> transformEmfJemData(module, classfileBuffer);
            case KJS_ICON_PROVIDER_CLS -> transformKubeJsIconProvider(module, classfileBuffer);
            case RHINO_JAVA_MEMBERS_CLS -> transformRhinoJavaMembers(module, classfileBuffer);
            case "dev/latvian/mods/rhino/NativeJavaMethod" -> classBeingRedefined == null
                    ? transformRhinoNativeJavaMethod(classfileBuffer) : null;
            case BLUEPRINT_REMOLDED_CLS -> transformBlueprintRemolded(module, classfileBuffer);
            case TANUKI_ROTATING_CLS,
                 TANUKI_MULTIBLOCK_CLS,
                 TANUKI_TALL_ROTATING_CLS,
                 TANUKI_WIDE_CLS,
                 TANUKI_TALL_CLS,
                 TANUKI_PLASMA_CLS -> transformTanukiBlockShapes(className, classfileBuffer);
            default -> null;
        };
    }

    public static byte[] transformConfiguration(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"reads".equals(mn.name)
                    || !"(Ljava/lang/module/ResolvedModule;)Ljava/util/Set;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/util/Set".equals(min.owner)
                        && "copyOf".equals(min.name)
                        && "(Ljava/util/Collection;)Ljava/util/Set;".equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastUnmodifiableSet";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformClientLanguage(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasStorageField = false;
        if (cn.fields != null) {
            for (var fn : cn.fields) {
                if ("f_118910_".equals(fn.name) && "Ljava/util/Map;".equals(fn.desc)) {
                    hasStorageField = true;
                    break;
                }
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("m_264420_".equals(mn.name)
                    && "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;"
                            .equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "com/google/common/collect/ImmutableMap".equals(min.owner)
                            && "copyOf".equals(min.name)
                            && "(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;".equals(min.desc)) {
                        min.owner = "java/util/Collections";
                        min.name = "unmodifiableMap";
                        min.desc = "(Ljava/util/Map;)Ljava/util/Map;";
                        min.itf = false;
                        patched = true;
                    }
                }
                continue;
            }
            if (!"iris$lookupOverriddenEntry".equals(mn.name)
                    || !"(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            InsnList guard = new InsnList();
            LabelNode cont = new LabelNode();
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, PAYLOAD_CLS, "irisDeferredPending", "Z"));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            guard.add(new InsnNode(Opcodes.ACONST_NULL));
            guard.add(new InsnNode(Opcodes.ARETURN));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            if (hasStorageField) {
                LabelNode cont2 = new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new FieldInsnNode(Opcodes.GETFIELD, CLIENT_LANG_CLS, "f_118910_", "Ljava/util/Map;"));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, cont2));
                guard.add(new InsnNode(Opcodes.ACONST_NULL));
                guard.add(new InsnNode(Opcodes.ARETURN));
                guard.add(cont2);
                guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            }
            mn.instructions.insert(guard);
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformFlywheelBackendManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"onEndClientResourceReload".equals(mn.name)
                    || !"(Z)V".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            InsnList guard = new InsnList();
            LabelNode cont = new LabelNode();
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, PAYLOAD_CLS, "irisDeferredPending", "Z"));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEtfPlugin(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("hasClass".equals(mn.name) && "(Ljava/lang/String;)Z".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new LdcInsnNode(Type.getObjectType(ETF_PLUGIN_CLS)));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "classResourceExists",
                        "(Ljava/lang/String;Ljava/lang/ClassLoader;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformRemappingHelper(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getMinecraftRemapper".equals(mn.name) && (mn.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
                mn.access |= Opcodes.ACC_SYNCHRONIZED;
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformZipPath(ClassLoader loader, byte[] bytes) {
        return transformZipPath(loader, bytes, true);
    }

    public static byte[] transformZipPath(ClassLoader loader, byte[] bytes, boolean canAddField) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        boolean hasCachedField = false;
        for (FieldNode fn : cn.fields) {
            if ("sunlitCachedStr".equals(fn.name)) {
                hasCachedField = true;
                break;
            }
        }
        boolean canCacheToString = canAddField && !hasCachedField;
        for (MethodNode mn : cn.methods) {
            if ("normalize".equals(mn.name)
                    && "(Ljava/lang/String;)[B".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "jdk/nio/zipfs/ZipCoder".equals(min.owner)
                            && "isUTF8".equals(min.name)
                            && "()Z".equals(min.desc)
                            && min.getNext() instanceof JumpInsnNode jmp
                            && jmp.getOpcode() == Opcodes.IFEQ) {
                        InsnList fast = new InsnList();
                        LabelNode slow = new LabelNode();
                        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        fast.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                PAYLOAD_CLS,
                                "fastZipNormalizeAscii",
                                "(Ljava/lang/String;)[B",
                                false));
                        fast.add(new InsnNode(Opcodes.DUP));
                        fast.add(new JumpInsnNode(Opcodes.IFNULL, slow));
                        fast.add(new InsnNode(Opcodes.ARETURN));
                        fast.add(slow);
                        fast.add(new InsnNode(Opcodes.POP));
                        mn.instructions.insert(jmp, fast);
                        patched = true;
                        break;
                    }
                }
            } else if (canCacheToString
                    && "toString".equals(mn.name)
                    && "()Ljava/lang/String;".equals(mn.desc)
                    && mn.instructions != null) {
                cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlitCachedStr", "Ljava/lang/String;", null, null));
                InsnList head = new InsnList();
                LabelNode miss = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new FieldInsnNode(Opcodes.GETFIELD, JDK_ZIPPATH_CLS, "sunlitCachedStr", "Ljava/lang/String;"));
                head.add(new InsnNode(Opcodes.DUP));
                head.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(miss);
                head.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(head);
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        InsnList store = new InsnList();
                        store.add(new InsnNode(Opcodes.DUP));
                        store.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        store.add(new InsnNode(Opcodes.SWAP));
                        store.add(new FieldInsnNode(
                                Opcodes.PUTFIELD, JDK_ZIPPATH_CLS, "sunlitCachedStr", "Ljava/lang/String;"));
                        mn.instructions.insertBefore(insn, store);
                    }
                }
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : ClassLoader.getPlatformClassLoader();
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Most reflected methods never resolve competing overloads, so allocate their caches on demand. */
    public static byte[] transformRhinoNativeJavaMethod(byte[] bytes) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!"db99d3a7e01384927a450e46827729e509c8c0d410c656ba3c8551fb06f95a15".equals(hash)) return null;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        String list = "java/util/concurrent/CopyOnWriteArrayList";
        String desc = "L" + list + ";";
        String getter = "sunlit$getOverloadCache";
        String initialize = "sunlit$initializeOverloadCache";
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (FieldNode field : node.fields) {
            if ("overloadCache".equals(field.name)) {
                field.access = (field.access & ~Opcodes.ACC_FINAL) | Opcodes.ACC_VOLATILE;
            }
        }
        for (MethodNode method : node.methods) {
            var code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof FieldInsnNode field) || !node.name.equals(field.owner)
                        || !"overloadCache".equals(field.name)) continue;
                if (field.getOpcode() == Opcodes.PUTFIELD) {
                    // The reviewed constructors use ALOAD 0; NEW; DUP; INVOKESPECIAL; PUTFIELD.
                    for (int j = i - 4; j <= i; j++) method.instructions.remove(code.get(j));
                } else if (field.getOpcode() == Opcodes.GETFIELD) {
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESPECIAL,
                            node.name, getter, "()" + desc, false));
                }
            }
        }
        var read = new MethodNode(Opcodes.ACC_PRIVATE, getter, "()" + desc, null, null);
        var create = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED, initialize, "()" + desc, null, null);
        for (MethodNode method : new MethodNode[] {read, create}) {
            LabelNode ready = new LabelNode();
            InsnList code = method.instructions;
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "overloadCache", desc));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
            code.add(new InsnNode(Opcodes.POP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (method == read) {
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, initialize, "()" + desc, false));
            } else {
                code.add(new TypeInsnNode(Opcodes.NEW, list));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, list, "<init>", "()V", false));
                code.add(new InsnNode(Opcodes.DUP_X1));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "overloadCache", desc));
            }
            code.add(ready);
            code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {list}));
            code.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(method);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static byte[] transformRhinoJavaMembers(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Class".equals(min.owner)
                        && "getDeclaredMethods".equals(min.name)
                        && "()[Ljava/lang/reflect/Method;".equals(min.desc)) {
                    min.setOpcode(Opcodes.INVOKESTATIC);
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastGetDeclaredMethods";
                    min.desc = "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformBlueprintRemolded(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getResourceFunction".equals(mn.name)
                    && "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/function/Function;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList head = new InsnList();
                LabelNode cont = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new FieldInsnNode(
                        Opcodes.GETFIELD, BLUEPRINT_REMOLDED_CLS, "fileExtensionToEntries", "Ljava/util/HashMap;"));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135827_",
                        "()Ljava/lang/String;",
                        false));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135815_",
                        "()Ljava/lang/String;",
                        false));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "canSkipRemoldedLookup",
                        "(Ljava/util/HashMap;Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                head.add(new InsnNode(Opcodes.ACONST_NULL));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(cont);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
                mn.instructions.insert(head);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformScanDataCompactor(byte[] bytes) {
        return transformScanDataCompactor(null, bytes, true);
    }

    public static byte[] transformScanDataCompactor(Module module, byte[] bytes, boolean canAddMethods) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedCall = false;
        int strHits = 0;
        MethodNode compact0 = null;
        MethodNode compact2 = null;
        MethodNode lambda2 = null;
        MethodNode lambda3 = null;
        boolean hasHelper = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitCompactOne".equals(mn.name)) {
                hasHelper = true;
            } else if ("compact".equals(mn.name) && "()V".equals(mn.desc)) {
                compact0 = mn;
            } else if ("compact".equals(mn.name)
                    && "(Lnet/minecraftforge/forgespi/language/ModFileScanData;Ljava/lang/String;)V".equals(mn.desc)) {
                compact2 = mn;
            } else if ("lambda$compact$2".equals(mn.name)
                    && "(Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;)Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;"
                            .equals(mn.desc)) {
                lambda2 = mn;
            } else if ("lambda$compact$3".equals(mn.name)
                    && "(Lnet/minecraftforge/forgespi/language/ModFileScanData$ClassData;)Lnet/minecraftforge/forgespi/language/ModFileScanData$ClassData;"
                            .equals(mn.desc)) {
                lambda3 = mn;
            }
            if (!"lambda$compact$0".equals(mn.name)
                    || !"(Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;)Z".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "org/objectweb/asm/Type".equals(min.owner)
                        && "getClassName".equals(min.name)
                        && "()Ljava/lang/String;".equals(min.desc)) {
                    min.name = "getDescriptor";
                    patchedCall = true;
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    switch (s) {
                        case "kotlin.jvm." -> { ldc.cst = "Lkotlin/jvm/"; strHits++; }
                        case "scala.reflect." -> { ldc.cst = "Lscala/reflect/"; strHits++; }
                        case "org.spongepowered.asm.mixin." -> { ldc.cst = "Lorg/spongepowered/asm/mixin/"; strHits++; }
                        case "com.llamalad7.mixinextras." -> { ldc.cst = "Lcom/llamalad7/mixinextras/"; strHits++; }
                        case "org.jetbrains.annotations." -> { ldc.cst = "org/jetbrains/annotations/"; strHits++; }
                        case "javax.annotation." -> { ldc.cst = "javax/annotation/"; strHits++; }
                        case "kotlin.Metadata" -> { ldc.cst = "kotlin/Metadata;"; strHits++; }
                        case "net.minecraftforge.api.distmarker.OnlyIn" -> { ldc.cst = "Lnet/minecraftforge/api/distmarker/OnlyIn;"; strHits++; }
                        default -> { }
                    }
                }
            }
        }
        boolean patchedBase = patchedCall && strHits == 8;
        boolean patchedParallel = false;
        if (canAddMethods && !hasHelper && compact0 != null && compact2 != null && lambda2 != null && lambda3 != null
                && patchScanDataParallel(cn, compact0, compact2, lambda2, lambda3)) {
            patchedParallel = true;
        }
        if (!patchedBase && !patchedParallel) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean patchScanDataParallel(
            ClassNode cn,
            MethodNode compact0,
            MethodNode compact2,
            MethodNode lambda2,
            MethodNode lambda3) {
        final String setCls = "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet";
        int replacedAddOrGet = 0;
        for (MethodNode lm : new MethodNode[] {lambda2, lambda3}) {
            if (lm.instructions == null) {
                return false;
            }
            for (AbstractInsnNode insn : lm.instructions.toArray()) {
                if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.GETSTATIC
                        && MF_SCAN_COMPACTOR_CLS.equals(fin.owner)
                        && "TYPES".equals(fin.name)) {
                    AbstractInsnNode a1 = nextRealInsn(fin);
                    AbstractInsnNode a2 = nextRealInsn(a1);
                    AbstractInsnNode a3 = nextRealInsn(a2);
                    if (a1 instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD
                            && a2 instanceof MethodInsnNode getter && getter.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && a3 instanceof MethodInsnNode addOrGet
                            && addOrGet.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && setCls.equals(addOrGet.owner)
                            && "addOrGet".equals(addOrGet.name)
                            && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(addOrGet.desc)) {
                        lm.instructions.remove(fin);
                        addOrGet.setOpcode(Opcodes.INVOKESTATIC);
                        addOrGet.owner = PAYLOAD_CLS;
                        addOrGet.name = "internScanType";
                        addOrGet.itf = false;
                        replacedAddOrGet++;
                    }
                }
            }
        }
        boolean replacedFn = false;
        for (AbstractInsnNode insn : lambda3.instructions.toArray()) {
            if (insn instanceof FieldInsnNode fin
                    && fin.getOpcode() == Opcodes.GETSTATIC
                    && MF_SCAN_COMPACTOR_CLS.equals(fin.owner)
                    && "TYPES".equals(fin.name)) {
                AbstractInsnNode d1 = nextRealInsn(fin);
                AbstractInsnNode req = nextRealInsn(d1);
                AbstractInsnNode pop = nextRealInsn(req);
                AbstractInsnNode indy = nextRealInsn(pop);
                if (d1 != null && d1.getOpcode() == Opcodes.DUP
                        && req instanceof MethodInsnNode reqM && "requireNonNull".equals(reqM.name)
                        && pop != null && pop.getOpcode() == Opcodes.POP
                        && indy instanceof InvokeDynamicInsnNode indyNode
                        && "(Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;)Ljava/util/function/Function;"
                                .equals(indyNode.desc)) {
                    lmRemoveAndReplaceFn(lambda3.instructions, fin, d1, req, pop, indy);
                    replacedFn = true;
                    break;
                }
            }
        }
        if (replacedAddOrGet != 4 || !replacedFn) {
            return false;
        }

        compact2.access = (compact2.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;

        String modFileInfoCls = "net/minecraftforge/forgespi/language/IModFileInfo";
        String modFileCls = "net/minecraftforge/forgespi/locating/IModFile";
        String scanDataCls = "net/minecraftforge/forgespi/language/ModFileScanData";
        MethodNode compactOne = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlitCompactOne",
                "(Ljava/lang/Object;)V",
                null,
                null);
        LabelNode lTryStart = new LabelNode();
        LabelNode lTryEnd = new LabelNode();
        LabelNode lCatch = new LabelNode();
        LabelNode lRet = new LabelNode();
        InsnList oil = compactOne.instructions;
        oil.add(new VarInsnNode(Opcodes.ALOAD, 0));
        oil.add(new TypeInsnNode(Opcodes.INSTANCEOF, modFileInfoCls));
        oil.add(new JumpInsnNode(Opcodes.IFEQ, lRet));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 0));
        oil.add(new TypeInsnNode(Opcodes.CHECKCAST, modFileInfoCls));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 1));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 1));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileInfoCls, "getFile", "()L" + modFileCls + ";", true));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 2));
        oil.add(lTryStart);
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getScanResult", "()L" + scanDataCls + ";", true));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getFileName", "()Ljava/lang/String;", true));
        oil.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MF_SCAN_COMPACTOR_CLS, "compact", "(L" + scanDataCls + ";Ljava/lang/String;)V", false));
        oil.add(lTryEnd);
        oil.add(new JumpInsnNode(Opcodes.GOTO, lRet));
        oil.add(lCatch);
        oil.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[] {"java/lang/Object", modFileInfoCls, modFileCls},
                1,
                new Object[] {"java/lang/Throwable"}));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 3));
        oil.add(new FieldInsnNode(Opcodes.GETSTATIC, "org/embeddedt/modernfix/ModernFix", "LOGGER", "Lorg/apache/logging/log4j/Logger;"));
        oil.add(new LdcInsnNode("An error occured while compacting {}"));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getFileName", "()Ljava/lang/String;", true));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 3));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true));
        oil.add(lRet);
        oil.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {"java/lang/Object"}, 0, new Object[0]));
        oil.add(new InsnNode(Opcodes.RETURN));
        compactOne.tryCatchBlocks.add(new TryCatchBlockNode(lTryStart, lTryEnd, lCatch, "java/lang/Throwable"));
        cn.methods.add(compactOne);

        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, MF_SCAN_COMPACTOR_CLS, "sunlitCompactOne", "(Ljava/lang/Object;)V", false);
        InsnList c0 = new InsnList();
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/ModList", "get", "()Lnet/minecraftforge/fml/ModList;", false));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/ModList", "getModFiles", "()Ljava/util/List;", false));
        c0.add(new InvokeDynamicInsnNode(
                "accept",
                "()Ljava/util/function/Consumer;",
                bsm,
                Type.getMethodType("(Ljava/lang/Object;)V"),
                impl,
                Type.getMethodType("(Ljava/lang/Object;)V")));
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "parallelForEach", "(Ljava/util/List;Ljava/util/function/Consumer;)V", false));
        c0.add(new FieldInsnNode(Opcodes.GETSTATIC, MF_SCAN_COMPACTOR_CLS, "TYPES", "L" + setCls + ";"));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, setCls, "clear", "()V", false));
        c0.add(new FieldInsnNode(Opcodes.GETSTATIC, MF_SCAN_COMPACTOR_CLS, "TYPES", "L" + setCls + ";"));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, setCls, "trim", "()Z", false));
        c0.add(new InsnNode(Opcodes.POP));
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "clearScanTypes", "()V", false));
        c0.add(new InsnNode(Opcodes.RETURN));
        compact0.instructions = c0;
        if (compact0.tryCatchBlocks != null) {
            compact0.tryCatchBlocks.clear();
        }
        if (compact0.localVariables != null) {
            compact0.localVariables.clear();
        }
        return true;
    }

    private static void lmRemoveAndReplaceFn(
            InsnList list,
            AbstractInsnNode fin,
            AbstractInsnNode d1,
            AbstractInsnNode req,
            AbstractInsnNode pop,
            AbstractInsnNode indy) {
        list.insertBefore(
                indy,
                new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "internScanTypeFn", "()Ljava/util/function/Function;", false));
        list.remove(fin);
        list.remove(d1);
        list.remove(req);
        list.remove(pop);
        list.remove(indy);
    }

    private static AbstractInsnNode nextRealInsn(AbstractInsnNode node) {
        AbstractInsnNode cur = node == null ? null : node.getNext();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    /**
     * Generates {@code com/google/common/collect/SunlitFlatStateMap extends ImmutableMap}.
     * Zero {@code ImmutableMapEntry}, zero hash table, shares {@code keys[]} across all states of a block,
     * and clones {@code vals[]} once per state.
     */
    public static byte[] buildFlatStateMapBytes() {
        String self = "com/google/common/collect/SunlitFlatStateMap";
        String imMap = "com/google/common/collect/ImmutableMap";
        String imSet = "com/google/common/collect/ImmutableSet";
        String imCol = "com/google/common/collect/ImmutableCollection";
        String imList = "com/google/common/collect/ImmutableList";
        String imSetBuilder = "com/google/common/collect/ImmutableSet$Builder";
        String entryCls = "java/util/AbstractMap$SimpleImmutableEntry";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, self, null, imMap, null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "keys", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "vals", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "hash", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "lastKeys", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "lastKeySet", "L" + imSet + ";", null, null).visitEnd();

        // <init>(Object[] keys, Object[] vals)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;[Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, imMap, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[Ljava/lang/Object;", "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // size()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // isPartialView() -> false
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isPartialView", "()Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // get(Object key)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        var getNotNull = new Label();
        var loop1Cond = new Label();
        var loop1End = new Label();
        var loop1Hit = new Label();
        var loop2Cond = new Label();
        var loop2Hit = new Label();
        var notFound = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, getNotNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(getNotNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loop1Cond);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loop1End);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, loop1Hit);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop1Cond);
        mv.visitLabel(loop1Hit);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(loop1End);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loop2Cond);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notFound);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, loop2Hit);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop2Cond);
        mv.visitLabel(loop2Hit);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notFound);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // containsKey(Object key) -> get(key) != null
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "containsKey", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var ckFalse = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitJumpInsn(Opcodes.IFNULL, ckFalse);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(ckFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // containsValue(Object val)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "containsValue", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var cvNonNull = new Label();
        var cvLoop = new Label();
        var cvEnd = new Label();
        var cvHit = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, cvNonNull);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cvNonNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(cvLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, cvEnd);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, cvHit);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, cvHit);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(Opcodes.GOTO, cvLoop);
        mv.visitLabel(cvHit);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cvEnd);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // hashCode() -> cached sum of (keys[i].hashCode() ^ vals[i].hashCode())
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        mv.visitCode();
        var hcCompute = new Label();
        var hcLoop = new Label();
        var hcDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "hash", "I");
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFEQ, hcCompute);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(hcCompute);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(hcLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, hcDone);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitInsn(Opcodes.IXOR);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, hcLoop);
        mv.visitLabel(hcDone);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "hash", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // equals(Object o)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var eqNotSame = new Label();
        var eqFallback = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, eqNotSame);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(eqNotSame);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, self);
        mv.visitJumpInsn(Opcodes.IFEQ, eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, self);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "flatArrayEquals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, imMap, "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createKeySet() -> 1-slot cached ImmutableSet.copyOf(keys)
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createKeySet", "()L" + imSet + ";", null, null);
        mv.visitCode();
        var ksMiss = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, self, "lastKeys", "[Ljava/lang/Object;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, ksMiss);
        mv.visitFieldInsn(Opcodes.GETSTATIC, self, "lastKeySet", "L" + imSet + ";");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, ksMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(ksMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imSet, "copyOf", "([Ljava/lang/Object;)L" + imSet + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, self, "lastKeySet", "L" + imSet + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, self, "lastKeys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createValues() -> ImmutableList.copyOf(vals)
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createValues", "()L" + imCol + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imList, "copyOf", "([Ljava/lang/Object;)L" + imList + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createEntrySet() -> ImmutableSet.Builder with SimpleImmutableEntry
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createEntrySet", "()L" + imSet + ";", null, null);
        mv.visitCode();
        var esLoop = new Label();
        var esDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imSet, "builder", "()L" + imSetBuilder + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitLabel(esLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, esDone);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.NEW, entryCls);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, entryCls, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, imSetBuilder, "add", "(Ljava/lang/Object;)L" + imSetBuilder + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(Opcodes.GOTO, esLoop);
        mv.visitLabel(esDone);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, imSetBuilder, "build", "()L" + imSet + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void ensureReadsExtraPayload(Module module) {
        if (module != null && module.isNamed() && extraPayloadModule != null && extraInst != null
                && !module.canRead(extraPayloadModule)) {
            try {
                extraInst.redefineModule(
                        module,
                        java.util.Set.of(extraPayloadModule),
                        java.util.Map.of(),
                        java.util.Map.of(),
                        java.util.Set.of(),
                        java.util.Map.of());
            } catch (Throwable ignored) {
            }
        }
    }

    public static byte[] transformMoonlightResTransformer(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if (min.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/util/regex/Pattern".equals(min.owner)
                            && "compile".equals(min.name)
                            && "(Ljava/lang/String;)Ljava/util/regex/Pattern;".equals(min.desc)) {
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastCompilePattern";
                        min.itf = false;
                        patched = true;
                    } else if (min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/String".equals(min.owner)
                            && "matches".equals(min.name)
                            && "(Ljava/lang/String;)Z".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringMatches";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)Z";
                        min.itf = false;
                        patched = true;
                    } else if (min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/String".equals(min.owner)
                            && "replaceAll".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceAll";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    }
                }
            }
            if ("replaceFullGenericType".equals(mn.name)
                    && ("(Ljava/lang/String;Lnet/mehvahdjukaar/moonlight/api/set/BlockType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                                    .equals(mn.desc)
                            || "(Ljava/lang/String;Lnet/mehvahdjukaar/moonlight/api/set/BlockType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;"
                                    .equals(mn.desc))) {
                InsnList head = new InsnList();
                LabelNode cont = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ALOAD, 3));
                head.add(new VarInsnNode(Opcodes.ALOAD, 4));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "canSkipMoonlightGenericReplace",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(cont);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
                mn.instructions.insert(head);
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformStringMatches(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(min.owner)) {
                    if ("matches".equals(min.name) && "(Ljava/lang/String;)Z".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringMatches";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)Z";
                        min.itf = false;
                        patched = true;
                    } else if ("replaceAll".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceAll";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("replaceFirst".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceFirst";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("split".equals(min.name)
                            && "(Ljava/lang/String;)[Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringSplit";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("split".equals(min.name)
                            && "(Ljava/lang/String;I)[Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringSplitLimit";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;I)[Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEmberbiumModuleScanner(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"checkModules".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            InsnList head = new InsnList();
            LabelNode cont = new LabelNode();
            head.add(new LdcInsnNode(Type.getObjectType(EMBERBIUM_MODULE_SCANNER_CLS)));
            head.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
            head.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "mayHaveRtssHooksLoaded", "(Ljava/lang/ClassLoader;)Z", false));
            head.add(new JumpInsnNode(Opcodes.IFNE, cont));
            head.add(new InsnNode(Opcodes.RETURN));
            head.add(cont);
            head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
            mn.instructions.insert(head);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] restoreLegacyWindowsBindings(String className, byte[] bytes) {
        ClassNode binding = new ClassNode();
        new ClassReader(bytes).accept(binding, 0);
        boolean changed;
        if (WGL_CLS.equals(className)) {
            changed = addWindowsBindingOverload(binding, "wglGetCurrentContext", "()J");
        } else {
            changed = addWindowsBindingOverload(binding, "SetWindowPos", "(JJIIIII)Z");
            changed |= addWindowsBindingOverload(binding, "SetWindowLongPtr", "(JIJ)J");
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        binding.accept(writer);
        return writer.toByteArray();
    }

    private static boolean addWindowsBindingOverload(ClassNode binding, String name, String legacyDescriptor) {
        String currentDescriptor = "(Ljava/nio/IntBuffer;" + legacyDescriptor.substring(1);
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        boolean hasCurrentMethod = false;
        for (MethodNode method : binding.methods) {
            if (name.equals(method.name)) {
                if (legacyDescriptor.equals(method.desc)) {
                    return false;
                }
                hasCurrentMethod |= currentDescriptor.equals(method.desc) && (method.access & access) == access;
            }
        }
        if (!hasCurrentMethod) {
            return false;
        }

        // Legacy callers use the result but have no buffer for the optional GetLastError output.
        MethodNode bridge = new MethodNode(access, name, legacyDescriptor, null, null);
        bridge.visitCode();
        bridge.visitInsn(Opcodes.ACONST_NULL);
        int local = 0;
        for (Type argument : Type.getArgumentTypes(legacyDescriptor)) {
            bridge.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }
        bridge.visitMethodInsn(Opcodes.INVOKESTATIC, binding.name, name, currentDescriptor, false);
        bridge.visitInsn(Type.getReturnType(legacyDescriptor).getOpcode(Opcodes.IRETURN));
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        binding.methods.add(bridge);
        return true;
    }

    public static byte[] transformWallLanternBlock(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"isValidBlock".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(min.owner)
                        && "contains".equals(min.name)
                        && "(Ljava/lang/Object;)Z".equals(min.desc)) {
                    min.setOpcode(Opcodes.INVOKESTATIC);
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastCachedListContains";
                    min.desc = "(Ljava/util/List;Ljava/lang/Object;)Z";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformKubeJsIconProvider(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "javax/imageio/ImageIO".equals(min.owner)
                        && "write".equals(min.name)
                        && "(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/OutputStream;)Z"
                                .equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastWritePng";
                    min.desc = "(Ljava/lang/Object;Ljava/lang/String;Ljava/io/OutputStream;)Z";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Defers TanukiDecor per-state VoxelShape precalculation from constructor time to first
     * shape query, saving synchronous block-registration startup time.
     */
    public static byte[] transformTanukiBlockShapes(String className, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (TANUKI_PLASMA_CLS.equals(className)) {
            boolean patched = stripCtorPrecalculate(cn);
            if (!patched) {
                return null;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        }
        boolean hasPrecalc = false;
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if ("precalculateShapes".equals(mn.name) && "()V".equals(mn.desc)) {
                    hasPrecalc = true;
                } else if ("sunlitEnsureShapes".equals(mn.name)) {
                    return null;
                }
            }
        }
        if (!hasPrecalc) {
            return null;
        }
        boolean patchedCtor = stripCtorPrecalculate(cn);
        if (!patchedCtor) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitShapesReady",
                "Z",
                null,
                null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitAllowPrecalc",
                "Z",
                null,
                null));

        for (MethodNode mn : cn.methods) {
            if ("precalculateShapes".equals(mn.name) && "()V".equals(mn.desc) && mn.instructions != null) {
                LabelNode lAllow = new LabelNode();
                InsnList preGuard = new InsnList();
                preGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                preGuard.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitAllowPrecalc", "Z"));
                preGuard.add(new JumpInsnNode(Opcodes.IFNE, lAllow));
                preGuard.add(new InsnNode(Opcodes.RETURN));
                preGuard.add(lAllow);
                preGuard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                mn.instructions.insert(preGuard);
                break;
            }
        }

        MethodNode ensure = new MethodNode(Opcodes.ACC_PRIVATE, "sunlitEnsureShapes", "()V", null, null);
        LabelNode lSlow = new LabelNode();
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitShapesReady", "Z"));
        ensure.instructions.add(new JumpInsnNode(Opcodes.IFEQ, lSlow));
        ensure.instructions.add(new InsnNode(Opcodes.RETURN));
        ensure.instructions.add(lSlow);
        ensure.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, className, "sunlitInitShapes", "()V", false));
        ensure.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(ensure);

        MethodNode initShapes = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED, "sunlitInitShapes", "()V", null, null);
        LabelNode lDone = new LabelNode();
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitShapesReady", "Z"));
        initShapes.instructions.add(new JumpInsnNode(Opcodes.IFNE, lDone));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, className, "sunlitAllowPrecalc", "Z"));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, className, "precalculateShapes", "()V", false));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, className, "sunlitShapesReady", "Z"));
        initShapes.instructions.add(lDone);
        initShapes.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        initShapes.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(initShapes);

        boolean patchedGetter = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            boolean match;
            if (TANUKI_ROTATING_CLS.equals(className)) {
                match = "m_5940_".equals(mn.name);
            } else if (TANUKI_TALL_CLS.equals(className)) {
                match = "getMultiblockShape".equals(mn.name);
            } else {
                match = "getBlockShape".equals(mn.name) || "getMultiblockShape".equals(mn.name);
            }
            if (match) {
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, className, "sunlitEnsureShapes", "()V", false));
                mn.instructions.insert(guard);
                patchedGetter = true;
            }
        }
        if (!patchedGetter) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean stripCtorPrecalculate(ClassNode cn) {
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "precalculateShapes".equals(min.name)
                        && "()V".equals(min.desc)) {
                    AbstractInsnNode prev = min.getPrevious();
                    while (prev != null && (prev.getOpcode() < 0)) {
                        prev = prev.getPrevious();
                    }
                    if (prev instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD && vin.var == 0) {
                        mn.instructions.remove(vin);
                        mn.instructions.remove(min);
                        patched = true;
                    }
                }
                insn = next;
            }
        }
        return patched;
    }

    /**
     * Synchronizes {@code EMFResourceCaching.populateCacheIfNeeded} and short-circuits
     * post-population misses under {@code emf/cem/} and {@code optifine/cem/} without re-scanning all packs.
     */
    public static byte[] transformEmfResourceCaching(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasCacheField = false;
        if (cn.fields != null) {
            for (var fn : cn.fields) {
                if ("RESOURCE_EXISTENCE_CACHE".equals(fn.name) && "Ljava/util/Map;".equals(fn.desc)) {
                    hasCacheField = true;
                    break;
                }
            }
        }
        if (!hasCacheField) {
            return null;
        }
        final String rmCls = "net/minecraft/server/packs/resources/ResourceManager";
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        boolean patchedSync = false;
        boolean patchedExists = false;
        for (MethodNode mn : cn.methods) {
            if ("populateCacheIfNeeded".equals(mn.name) && ("(L" + rmCls + ";)V").equals(mn.desc)) {
                mn.access |= Opcodes.ACC_SYNCHRONIZED;
                patchedSync = true;
            } else if ("resourceExists".equals(mn.name) && ("(L" + rmCls + ";L" + rlCls + ";)Z").equals(mn.desc)) {
                InsnList il = new InsnList();
                LabelNode lNotCached1 = new LabelNode();
                LabelNode lNotCached2 = new LabelNode();
                LabelNode lFastFalse = new LabelNode();
                LabelNode lFallbackLookup = new LabelNode();

                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "toString", "()Ljava/lang/String;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, lNotCached1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lNotCached1);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        4,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, EMF_RES_CACHING_CLS, "populateCacheIfNeeded", "(L" + rmCls + ";)V", false));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, lNotCached2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lNotCached2);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        4,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135815_", "()Ljava/lang/String;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new LdcInsnNode("emf/cem/"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFNE, lFastFalse));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new LdcInsnNode("optifine/cem/"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFEQ, lFallbackLookup));

                il.add(lFastFalse);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        5,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean", "java/lang/String"},
                        0,
                        new Object[0]));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lFallbackLookup);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        5,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean", "java/lang/String"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, rmCls, "m_213713_", "(L" + rlCls + ";)Ljava/util/Optional;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
                il.add(new VarInsnNode(Opcodes.ISTORE, 5));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new InsnNode(Opcodes.IRETURN));

                mn.instructions = il;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patchedExists = true;
            }
        }
        if (!patchedSync || !patchedExists) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Offloads ModernFix post-bootstrap classloading hooks off {@code BackgroundWaiter}. */
    public static byte[] transformMinecraftBootstrap(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKESTATIC) {
                    if ("net/minecraft/client/renderer/RenderType".equals(min.owner)
                            && "m_110451_".equals(min.name)
                            && "()Lnet/minecraft/client/renderer/RenderType;".equals(min.desc)) {
                        AbstractInsnNode next = nextRealInsn(min);
                        if (next != null && next.getOpcode() == Opcodes.POP) {
                            mn.instructions.insertBefore(min, new InsnNode(Opcodes.ICONST_1));
                            mn.instructions.insertBefore(min, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    PAYLOAD_CLS,
                                    "schedulePostBootstrapPrewarm",
                                    "(Z)V",
                                    false));
                            mn.instructions.remove(min);
                            mn.instructions.remove(next);
                            patched = true;
                        }
                    } else if ("net/minecraftforge/network/NetworkConstants".equals(min.owner)
                            && "init".equals(min.name)
                            && "()Ljava/lang/String;".equals(min.desc)) {
                        AbstractInsnNode next = nextRealInsn(min);
                        if (next != null && next.getOpcode() == Opcodes.POP) {
                            mn.instructions.insertBefore(min, new InsnNode(Opcodes.ICONST_0));
                            mn.instructions.insertBefore(min, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    PAYLOAD_CLS,
                                    "schedulePostBootstrapPrewarm",
                                    "(Z)V",
                                    false));
                            mn.instructions.remove(min);
                            mn.instructions.remove(next);
                            patched = true;
                        }
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Single-pass wildcard matcher for CarryOn {@code ListHandler.addWithWildcards}. */
    public static byte[] transformCarryOnListHandler(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String desc =
                "(Ljava/util/List;Ljava/util/Set;Lnet/minecraft/core/Registry;Ljava/util/Map;Ljava/util/List;)V";
        for (MethodNode mn : cn.methods) {
            if (!"addWithWildcards".equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode lLoop = new LabelNode();
            LabelNode lNext = new LabelNode();
            LabelNode lEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "net/minecraft/core/Registry",
                    "m_6566_",
                    "()Ljava/util/Set;",
                    true));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PAYLOAD_CLS,
                    "applyCarryOnWildcards",
                    "(Ljava/util/List;Ljava/util/Set;Ljava/util/Set;)V",
                    false));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));

            Object[] loopLocals = new Object[] {
                "java/util/List",
                "java/util/Set",
                "net/minecraft/core/Registry",
                "java/util/Map",
                "java/util/List",
                Opcodes.INTEGER,
                Opcodes.INTEGER
            };
            il.add(lLoop);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new LdcInsnNode("#"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, lNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CARRYON_LIST_HANDLER_CLS,
                    "addTag",
                    "(Ljava/lang/String;Ljava/util/Map;Ljava/util/List;)V",
                    false));

            il.add(lNext);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new IincInsnNode(5, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

            il.add(lEnd);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new InsnNode(Opcodes.RETURN));

            mn.instructions = il;
            if (mn.tryCatchBlocks != null) {
                mn.tryCatchBlocks.clear();
            }
            if (mn.localVariables != null) {
                mn.localVariables.clear();
            }
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEmfJemData(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String desc = "(Ltraben/entity_model_features/models/jem_objects/EMFPartData;Ljava/lang/String;)Ljava/lang/String;";
        final String partCls = "traben/entity_model_features/models/jem_objects/EMFPartData";
        for (MethodNode mn : cn.methods) {
            if (!"processAnimAndKeyString".equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "id", "Ljava/lang/String;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "originalPart", "Ljava/lang/String;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "part", "Ljava/lang/String;"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Objects",
                    "requireNonNullElse",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PAYLOAD_CLS,
                    "fastEmfProcessAnimKey",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    false));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) {
                mn.tryCatchBlocks.clear();
            }
            if (mn.localVariables != null) {
                mn.localVariables.clear();
            }
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
