package dev.sunlit.bootagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Early bootstrap {@link ClassFileTransformer} eliminating filesystem stat bottlenecks,
 * O(N^3) JPMS module graph resolution, single-threaded FML candidate scanning,
 * and Mixin lock/reflection hotspots during cold launch.
 */
public final class SunlitBootAgent implements ClassFileTransformer {
    private static final String RESOLVER_CLS = "java/lang/module/Resolver";
    private static final String ZIPFS_CLS = "jdk/nio/zipfs/ZipFileSystem";
    private static final String ZIPFS_ENTRY_CLS = "jdk/nio/zipfs/ZipFileSystem$Entry";
    private static final String UNION_CLS = "cpw/mods/niofs/union/UnionFileSystem";
    private static final String BG_WAITER_CLS = "net/minecraftforge/fml/loading/BackgroundWaiter";
    private static final String SCAN_CLS = "net/minecraftforge/fml/loading/moddiscovery/BackgroundScanHandler";
    private static final String MIXIN_BYTECODE_CLS = "org/spongepowered/asm/util/Bytecode";
    private static final String MIXIN_PLUGIN_CLS = "org/spongepowered/asm/launch/MixinLaunchPluginLegacy";
    private static final String MIXIN_HANDLER_CLS = "org/spongepowered/asm/service/modlauncher/MixinTransformationHandler";
    private static final String MIXIN_TRANSFORMER_CLS = "org/spongepowered/asm/mixin/transformer/MixinTransformer";
    private static final String MIXIN_PROCESSOR_CLS = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    private static final String MIXIN_CONFIG_CLS = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String MIXIN_CP_PASS_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorPassthrough";
    private static final String MIXIN_CP_ACC_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorAccessor";
    private static final String MIXIN_CP_NEST_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorNestHost";
    private static final String MIXIN_CP_SYN_CLS = "org/spongepowered/asm/mixin/transformer/MixinCoprocessorSyntheticInner";
    private static final String MIXIN_PAYLOAD_CLS = "org/spongepowered/asm/mixin/transformer/BootMixinPayload";
    private static final String RESOLVER_PAYLOAD_RES = "/dev/sunlit/bootagent/payload/BootResolverPayload.class";
    private static final String MIXIN_PAYLOAD_RES = "/dev/sunlit/bootagent/payload/BootMixinPayload.class";

    public static void premain(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new SunlitBootAgent(), true);
        retransformIfLoaded(inst, "java.lang.module.Resolver", null);
        retransformIfLoaded(inst, "jdk.nio.zipfs.ZipFileSystem$Entry", ClassLoader.getPlatformClassLoader());
    }

    /**
     * Post-exit helper entry point invoked when {@code config/sunlit-boot.jar} is locked on
     * Windows by the active JVM's {@code -javaagent} handle.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !"--sync".equals(args[0])) {
            throw new IllegalArgumentException("Usage: --sync <pid> <staged> <target> <sha256>");
        }
        long pid = Long.parseLong(args[1]);
        Path staged = Path.of(args[2]).toAbsolutePath().normalize();
        Path target = Path.of(args[3]).toAbsolutePath().normalize();
        String expectedSha = args[4];
        if (!expectedSha.matches("[a-f0-9]{64}")
                || Files.isSymbolicLink(staged)
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(ph -> {
            if (ph.isAlive()) {
                try {
                    ph.onExit().get(24, TimeUnit.HOURS);
                } catch (Exception ignored) {
                }
            }
        });
        if (!sha256File(staged).equals(expectedSha)) {
            return;
        }
        Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent)) {
            return;
        }
        Files.createDirectories(parent);
        for (int attempt = 0; attempt < 25; attempt++) {
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && sha256File(target).equals(expectedSha)) {
                return;
            }
            Path tmp = parent.resolve(".sunlit-boot.jar.tmp-" + UUID.randomUUID());
            try {
                Files.copy(staged, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                Thread.sleep(200L);
            }
        }
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) != -1; ) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
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

    @Override
    public byte[] transform(
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
                case ZIPFS_CLS -> transformZipFileSystem(loader, classfileBuffer);
                case ZIPFS_ENTRY_CLS -> transformZipFileSystemEntry(loader, classfileBuffer);
                case UNION_CLS -> transformUnionFileSystem(loader, classfileBuffer);
                case BG_WAITER_CLS -> transformBackgroundWaiter(classfileBuffer);
                case SCAN_CLS -> transformBackgroundScanHandler(loader, classfileBuffer);
                case MIXIN_BYTECODE_CLS -> transformMixinBytecode(loader, classfileBuffer);
                case MIXIN_PLUGIN_CLS -> transformMixinLaunchPlugin(classfileBuffer);
                case MIXIN_HANDLER_CLS -> transformMixinHandler(loader, classfileBuffer);
                case MIXIN_TRANSFORMER_CLS -> widenClass(classfileBuffer, Set.of("processor"), Set.of());
                case MIXIN_PROCESSOR_CLS -> transformMixinProcessor(loader, classfileBuffer);
                case MIXIN_CONFIG_CLS -> widenClass(classfileBuffer, Set.of("mixinMapping"), Set.of());
                case MIXIN_CP_PASS_CLS -> widenClass(classfileBuffer, Set.of("loadable"), Set.of("registerLoadable"));
                case MIXIN_CP_ACC_CLS -> widenClass(classfileBuffer, Set.of("accessorMixins"), Set.of("registerAccessor"));
                case MIXIN_CP_NEST_CLS -> widenClass(classfileBuffer, Set.of("nestHosts"), Set.of("registerNestMember"));
                case MIXIN_CP_SYN_CLS -> widenClass(classfileBuffer, Set.of("syntheticInnerClasses"), Set.of("registerSyntheticInner"));
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
        for (MethodNode mn : cn.methods) {
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
        return patched ? writeNodeWithFrames(cn, loader) : null;
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
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
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
        return true;
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
        ClassNode cn = readNode(bytes);
        boolean patched = false;
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
            }
        }
        return patched ? writeNodeMaxs(cn) : null;
    }

    public static byte[] transformResolver(ClassLoader loader, byte[] bytes) {
        ClassNode cn = readNode(bytes);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"makeGraph".equals(mn.name) || !"(Ljava/lang/module/Configuration;)Ljava/util/Map;".equals(mn.desc)) {
                continue;
            }
            AbstractInsnNode newListStart = null;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof TypeInsnNode tin
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
                        newListStart = tin;
                        break;
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

            ClassNode payloadCn = loadPayloadClass(RESOLVER_PAYLOAD_RES);
            MethodNode closeGraph = findMethod(payloadCn, "closeGraph", "(Ljava/util/Map;Ljava/util/Map;)V");
            LabelNode afterClose = new LabelNode();
            InsnList spliced = cloneStaticPayloadBody(closeGraph, new int[]{3, 4}, maxVar, afterClose, null, null);
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
                hookedApply = true;
            }
        }
        return (hookedSelect && hookedApply) ? writeNodeWithFrames(cn, loader) : null;
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
        try (InputStream is = SunlitBootAgent.class.getResourceAsStream(resourcePath)) {
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

    private static byte[] writeNodeWithFrames(ClassNode cn, ClassLoader loader) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    ClassLoader cl = loader != null ? loader : ClassLoader.getPlatformClassLoader();
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
}
