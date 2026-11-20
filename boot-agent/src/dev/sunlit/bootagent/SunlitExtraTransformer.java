package dev.sunlit.bootagent;

import dev.sunlit.bootagent.payload.BootJarPayload;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

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
            case JDK_ZIPPATH_CLS -> transformZipPath(loader, classfileBuffer);
            case MF_SCAN_COMPACTOR_CLS -> transformScanDataCompactor(classfileBuffer);
            case CLIENT_LANG_CLS -> transformClientLanguage(loader, classfileBuffer);
            case FLW_BACKEND_MGR_CLS -> transformFlywheelBackendManager(classfileBuffer);
            case MOONLIGHT_RES_TX_CLS -> transformMoonlightResTransformer(module, classfileBuffer);
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
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"normalize".equals(mn.name)
                    || !"(Ljava/lang/String;)[B".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
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

    public static byte[] transformScanDataCompactor(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedCall = false;
        int strHits = 0;
        for (MethodNode mn : cn.methods) {
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
        if (!patchedCall || strHits != 8) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
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

    public static byte[] transformMoonlightResTransformer(Module module, byte[] bytes) {
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
                        && "java/util/regex/Pattern".equals(min.owner)
                        && "compile".equals(min.name)
                        && "(Ljava/lang/String;)Ljava/util/regex/Pattern;".equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastCompilePattern";
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
}
