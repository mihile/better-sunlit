package dev.sunlit.bettersunlit.inline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.Opcodes;
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
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed post-apply ASM bytecode rewrites applied by {@link EntryMixinPlugin} after all
 * lower-priority Mixins have merged into target classes.
 */
public final class EntryPostApplyPatches {
    private static final Logger LOG = LoggerFactory.getLogger("sunlit_fish_visuals");

    public static final String CIR_INTERNAL = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String BLOCK_STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK_GETTER = "net/minecraft/world/level/BlockGetter";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";
    private static final String OCCLUSION_PERF = "dev/sunlit/bettersunlit/perf/BlockOcclusionPerf";
    private static final String OCC_CACHE_FIELD = "sunlit$occCache";

    private static final int KIND_COLLISION_FULL_BLOCK = 1;
    private static final int KIND_VIEW_BLOCKING = 2;
    private static final int KIND_SUFFOCATING = 3;
    private static final int KIND_SHADE_BRIGHTNESS = 4;

    private EntryPostApplyPatches() {}

    /**
     * Replaces {@code BlockBehaviour$BlockStateBase.m_60815_()} with a lock-free versioned fast path
     * backed by {@link dev.sunlit.bettersunlit.perf.BlockOcclusionPerf} when and only when the
     * merged bytecode contains solely the expected Copycats+ and PassableFoliage Mixin callbacks.
     *
     * @return {@code true} if the method was rewritten (or already rewritten), {@code false} if an
     * unexpected structure caused a fail-closed abort.
     */
    public static boolean patchBlockOcclusion(ClassNode cn) {
        if (cn == null) return false;
        MethodNode target = findMethod(cn, "m_60815_", "()Z");
        if (target == null) {
            LOG.warn("[BetterSunlit-Perf] Skipping m_60815_ occlusion rewrite: method missing on {}", cn.name);
            return false;
        }
        if (!hasField(cn, "f_60601_", "Z") || !hasField(cn, OCC_CACHE_FIELD, "I")) {
            LOG.warn("[BetterSunlit-Perf] Skipping m_60815_ occlusion rewrite: required fields missing on {}", cn.name);
            return false;
        }

        boolean sawCopycats = false;
        boolean sawPassable = false;
        boolean sawAlreadyPatched = false;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m) {
                if (OCCLUSION_PERF.equals(m.owner)) {
                    if ("computeMode".equals(m.name) && ("(L" + BLOCK_STATE_BASE + ";)I").equals(m.desc)) {
                        sawAlreadyPatched = true;
                        continue;
                    }
                    if ("evalPassable".equals(m.name) && ("(L" + BLOCK_STATE_BASE + ";)Z").equals(m.desc)) {
                        continue;
                    }
                }
                if (CIR_INTERNAL.equals(m.owner)) continue;
                if ("java/lang/Boolean".equals(m.owner) && "booleanValue".equals(m.name)) continue;
                if (cn.name.equals(m.owner)
                        && ("(L" + CIR_INTERNAL + ";)V").equals(m.desc)
                        && (m.name.endsWith("$customOcclusion") || m.name.equals("customOcclusion"))) {
                    sawCopycats = true;
                    continue;
                }
                if (cn.name.equals(m.owner)
                        && ("(L" + CIR_INTERNAL + ";)V").equals(m.desc)
                        && (m.name.endsWith("$pfoliage_canOcclude") || m.name.equals("pfoliage_canOcclude"))) {
                    sawPassable = true;
                    continue;
                }
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected call {}.{}{}", cn.name, m.owner, m.name, m.desc);
                return false;
            }
            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected INVOKEDYNAMIC in m_60815_", cn.name);
                return false;
            }
        }
        if (sawAlreadyPatched) {
            patchPassableFoliageStateMethods(cn);
            return true;
        }
        if (!sawCopycats || !sawPassable) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: expected handlers missing (copycats={}, passable={})",
                    cn.name, sawCopycats, sawPassable);
            return false;
        }

        InsnList out = new InsnList();
        LabelNode lCheckCache = new LabelNode();
        LabelNode lMiss = new LabelNode();
        LabelNode lMaskMode = new LabelNode();
        LabelNode lEval = new LabelNode();
        LabelNode lNotTrue = new LabelNode();
        LabelNode lCheckPassable = new LabelNode();
        LabelNode lStart = new LabelNode();
        LabelNode lEnd = new LabelNode();

        out.add(lStart);
        // if (!this.f_60601_) return false;
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "f_60601_", "Z"));
        out.add(new JumpInsnNode(Opcodes.IFNE, lCheckCache));
        out.add(new InsnNode(Opcodes.ICONST_0));
        out.add(new InsnNode(Opcodes.IRETURN));

        out.add(lCheckCache);
        out.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] { cn.name }, 0, new Object[0]));
        // int gen = BlockOcclusionPerf.generation;
        out.add(new FieldInsnNode(Opcodes.GETSTATIC, OCCLUSION_PERF, "generation", "I"));
        out.add(new VarInsnNode(Opcodes.ISTORE, 1));
        // int cached = this.sunlit$occCache;
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, OCC_CACHE_FIELD, "I"));
        out.add(new VarInsnNode(Opcodes.ISTORE, 2));
        // if ((cached >>> 2) == gen) { mode = cached & 3; goto lEval; }
        out.add(new VarInsnNode(Opcodes.ILOAD, 2));
        out.add(new InsnNode(Opcodes.ICONST_2));
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new VarInsnNode(Opcodes.ILOAD, 1));
        out.add(new JumpInsnNode(Opcodes.IF_ICMPNE, lMiss));
        out.add(new VarInsnNode(Opcodes.ILOAD, 2));
        out.add(new InsnNode(Opcodes.ICONST_3));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new VarInsnNode(Opcodes.ISTORE, 3));
        out.add(new JumpInsnNode(Opcodes.GOTO, lEval));

        // Miss: int mode = BlockOcclusionPerf.computeMode(this);
        out.add(lMiss);
        out.add(new FrameNode(Opcodes.F_FULL, 3, new Object[] { cn.name, Opcodes.INTEGER, Opcodes.INTEGER }, 0, new Object[0]));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OCCLUSION_PERF, "computeMode",
                "(L" + BLOCK_STATE_BASE + ";)I", false));
        out.add(new VarInsnNode(Opcodes.ISTORE, 3));
        // if ((mode & FLAG_UNBOUND) == 0) this.sunlit$occCache = (gen << 2) | (mode & 3);
        out.add(new VarInsnNode(Opcodes.ILOAD, 3));
        out.add(new InsnNode(Opcodes.ICONST_4));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new JumpInsnNode(Opcodes.IFNE, lMaskMode));
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new VarInsnNode(Opcodes.ILOAD, 1));
        out.add(new InsnNode(Opcodes.ICONST_2));
        out.add(new InsnNode(Opcodes.ISHL));
        out.add(new VarInsnNode(Opcodes.ILOAD, 3));
        out.add(new InsnNode(Opcodes.ICONST_3));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new InsnNode(Opcodes.IOR));
        out.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, OCC_CACHE_FIELD, "I"));

        out.add(lMaskMode);
        out.add(new FrameNode(Opcodes.F_FULL, 4,
                new Object[] { cn.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER }, 0, new Object[0]));
        out.add(new VarInsnNode(Opcodes.ILOAD, 3));
        out.add(new InsnNode(Opcodes.ICONST_3));
        out.add(new InsnNode(Opcodes.IAND));
        out.add(new VarInsnNode(Opcodes.ISTORE, 3));

        out.add(lEval);
        out.add(new FrameNode(Opcodes.F_FULL, 4,
                new Object[] { cn.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER }, 0, new Object[0]));
        // if (mode == MODE_TRUE) return true;
        out.add(new VarInsnNode(Opcodes.ILOAD, 3));
        out.add(new InsnNode(Opcodes.ICONST_2));
        out.add(new JumpInsnNode(Opcodes.IF_ICMPNE, lNotTrue));
        out.add(new InsnNode(Opcodes.ICONST_1));
        out.add(new InsnNode(Opcodes.IRETURN));

        out.add(lNotTrue);
        out.add(new FrameNode(Opcodes.F_FULL, 4,
                new Object[] { cn.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER }, 0, new Object[0]));
        // if (mode == MODE_FALSE) return false;
        out.add(new VarInsnNode(Opcodes.ILOAD, 3));
        out.add(new InsnNode(Opcodes.ICONST_1));
        out.add(new JumpInsnNode(Opcodes.IF_ICMPNE, lCheckPassable));
        out.add(new InsnNode(Opcodes.ICONST_0));
        out.add(new InsnNode(Opcodes.IRETURN));

        out.add(lCheckPassable);
        out.add(new FrameNode(Opcodes.F_FULL, 4,
                new Object[] { cn.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER }, 0, new Object[0]));
        // return BlockOcclusionPerf.evalPassable(this);
        out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OCCLUSION_PERF, "evalPassable",
                "(L" + BLOCK_STATE_BASE + ";)Z", false));
        out.add(new InsnNode(Opcodes.IRETURN));
        out.add(lEnd);

        target.instructions.clear();
        target.instructions.add(out);
        if (target.tryCatchBlocks != null) target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.removeIf(lv -> lv.index > 0);
            for (var lv : target.localVariables) {
                lv.start = lStart;
                lv.end = lEnd;
            }
        }
        target.maxStack = 4;
        target.maxLocals = 4;
        patchPassableFoliageStateMethods(cn);
        return true;
    }

    private static void patchPassableFoliageStateMethods(ClassNode cn) {
        patchSinglePassableMethod(cn, "m_60838_", "(L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";)Z",
                "$pfoliage_isCollisionShapeFullBlock", "pfoliage_isCollisionShapeFullBlock",
                KIND_COLLISION_FULL_BLOCK);
        patchSinglePassableMethod(cn, "m_60831_", "(L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";)Z",
                "$pfoliage_isViewBlocking", "pfoliage_isViewBlocking",
                KIND_VIEW_BLOCKING);
        patchSinglePassableMethod(cn, "m_60828_", "(L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";)Z",
                "$pfoliage_isSuffocating", "pfoliage_isSuffocating",
                KIND_SUFFOCATING);
        patchSinglePassableMethod(cn, "m_60792_", "(L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";)F",
                "$pfoliage_getShadeBrightness", "pfoliage_getShadeBrightness",
                KIND_SHADE_BRIGHTNESS);
    }

    /**
     * Rewrites a PassableFoliage {@code @At("HEAD")} cancellable Mixin prologue into an
     * allocation-free fast guard calling {@link dev.sunlit.bettersunlit.perf.BlockOcclusionPerf}.
     * Fails closed per method if any unexpected Mixin handler or layout is detected.
     */
    private static boolean patchSinglePassableMethod(
            ClassNode cn, String methodName, String methodDesc,
            String handlerSuffix, String handlerExact, int kind) {
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            return true;
        }
        String handlerDesc = "(L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";L" + CIR_INTERNAL + ";)V";
        boolean isFloat = kind == KIND_SHADE_BRIGHTNESS;
        int expectedReturnOpcode = isFloat ? Opcodes.FRETURN : Opcodes.IRETURN;

        boolean sawAlreadyPatched = false;
        MethodInsnNode handlerCall = null;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode m)) continue;
            if (OCCLUSION_PERF.equals(m.owner) && ("isPassableState".equals(m.name)
                    || "shouldCancelViewBlocking".equals(m.name)
                    || "evalPassableShadeBrightness".equals(m.name))) {
                sawAlreadyPatched = true;
                continue;
            }
            if ((insn.getOpcode() == Opcodes.INVOKEVIRTUAL || insn.getOpcode() == Opcodes.INVOKESPECIAL)
                    && cn.name.equals(m.owner)
                    && handlerDesc.equals(m.desc)
                    && (m.name.endsWith(handlerSuffix) || m.name.equals(handlerExact))) {
                if (handlerCall != null) {
                    LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}.{}: duplicate PassableFoliage handler",
                            cn.name, methodName);
                    return false;
                }
                handlerCall = m;
                continue;
            }
        }
        if (handlerCall == null) {
            if (sawAlreadyPatched) {
                return true;
            }
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}.{}: PassableFoliage handler missing",
                    cn.name, methodName);
            return false;
        }

        AbstractInsnNode firstReal = firstRealInsn(target.instructions);
        if (!(firstReal instanceof TypeInsnNode newCir) || newCir.getOpcode() != Opcodes.NEW
                || !CIR_INTERNAL.equals(newCir.desc)) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}.{}: expected NEW CIR at HEAD", cn.name, methodName);
            return false;
        }
        AbstractInsnNode cur = nextRealInsn(newCir);
        if (cur == null || cur.getOpcode() != Opcodes.DUP) return failPassable(cn.name, methodName, "DUP");
        cur = nextRealInsn(cur);
        if (!(cur instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String)) {
            return failPassable(cn.name, methodName, "LDC");
        }
        cur = nextRealInsn(cur);
        if (cur == null || cur.getOpcode() != Opcodes.ICONST_1) {
            return failPassable(cn.name, methodName, "ICONST_1");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof MethodInsnNode initCall) || initCall.getOpcode() != Opcodes.INVOKESPECIAL
                || !CIR_INTERNAL.equals(initCall.owner) || !"<init>".equals(initCall.name)
                || !"(Ljava/lang/String;Z)V".equals(initCall.desc)) {
            return failPassable(cn.name, methodName, "CIR.<init>");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof VarInsnNode storeCir) || storeCir.getOpcode() != Opcodes.ASTORE || storeCir.var < 3) {
            return failPassable(cn.name, methodName, "ASTORE cir");
        }
        int cirVar = storeCir.var;

        for (int expectedArg = 0; expectedArg < 4; expectedArg++) {
            cur = nextRealInsn(cur);
            int expectedVar = expectedArg < 3 ? expectedArg : cirVar;
            if (!(cur instanceof VarInsnNode loadArg) || loadArg.getOpcode() != Opcodes.ALOAD
                    || loadArg.var != expectedVar) {
                return failPassable(cn.name, methodName, "ALOAD arg " + expectedArg);
            }
        }
        cur = nextRealInsn(cur);
        if (cur != handlerCall) {
            return failPassable(cn.name, methodName, "handler call order");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof VarInsnNode loadCirCheck) || loadCirCheck.getOpcode() != Opcodes.ALOAD
                || loadCirCheck.var != cirVar) {
            return failPassable(cn.name, methodName, "ALOAD cir isCancelled");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof MethodInsnNode cancelledCall) || cancelledCall.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !CIR_INTERNAL.equals(cancelledCall.owner) || !"isCancelled".equals(cancelledCall.name)
                || !"()Z".equals(cancelledCall.desc)) {
            return failPassable(cn.name, methodName, "CIR.isCancelled");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof JumpInsnNode ifeqAfter) || ifeqAfter.getOpcode() != Opcodes.IFEQ) {
            return failPassable(cn.name, methodName, "IFEQ after");
        }

        cur = nextRealInsn(cur);
        if (!(cur instanceof VarInsnNode loadCirRet) || loadCirRet.getOpcode() != Opcodes.ALOAD
                || loadCirRet.var != cirVar) {
            return failPassable(cn.name, methodName, "ALOAD cir return");
        }
        cur = nextRealInsn(cur);
        if (!(cur instanceof MethodInsnNode retGetter) || retGetter.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !CIR_INTERNAL.equals(retGetter.owner)) {
            return failPassable(cn.name, methodName, "CIR return getter");
        }
        String primGetter = isFloat ? "getReturnValueF" : "getReturnValueZ";
        String primDesc = isFloat ? "()F" : "()Z";
        if (primGetter.equals(retGetter.name) && primDesc.equals(retGetter.desc)) {
            cur = nextRealInsn(cur);
        } else if ("getReturnValue".equals(retGetter.name) && "()Ljava/lang/Object;".equals(retGetter.desc)) {
            String boxedOwner = isFloat ? "java/lang/Float" : "java/lang/Boolean";
            String unboxName = isFloat ? "floatValue" : "booleanValue";
            cur = nextRealInsn(cur);
            if (cur instanceof TypeInsnNode checkCast && checkCast.getOpcode() == Opcodes.CHECKCAST) {
                if (!boxedOwner.equals(checkCast.desc)) {
                    return failPassable(cn.name, methodName, "CHECKCAST " + boxedOwner);
                }
                cur = nextRealInsn(cur);
            }
            if (!(cur instanceof MethodInsnNode unbox) || unbox.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !boxedOwner.equals(unbox.owner) || !unboxName.equals(unbox.name)
                    || !primDesc.equals(unbox.desc)) {
                return failPassable(cn.name, methodName, "unbox " + unboxName);
            }
            cur = nextRealInsn(cur);
        } else {
            return failPassable(cn.name, methodName, "unexpected CIR getter " + retGetter.name + retGetter.desc);
        }

        if (cur == null || cur.getOpcode() != expectedReturnOpcode) {
            return failPassable(cn.name, methodName, "IRETURN/FRETURN");
        }
        AbstractInsnNode retInsn = cur;

        boolean reachedAfterLabel = false;
        for (AbstractInsnNode p = retInsn.getNext(); p != null; p = p.getNext()) {
            if (p == ifeqAfter.label) {
                reachedAfterLabel = true;
                break;
            }
            if (p.getOpcode() >= 0 || p instanceof FrameNode) {
                return failPassable(cn.name, methodName, "insns before IFEQ target");
            }
        }
        if (!reachedAfterLabel) {
            return failPassable(cn.name, methodName, "IFEQ label mismatch");
        }

        for (AbstractInsnNode p = ifeqAfter.label; p != null; p = p.getNext()) {
            if (p instanceof VarInsnNode v && v.var == cirVar) {
                return failPassable(cn.name, methodName, "cirVar used in tail");
            }
            if (p instanceof IincInsnNode ii && ii.var == cirVar) {
                return failPassable(cn.name, methodName, "cirVar iinc in tail");
            }
            if (p instanceof MethodInsnNode m) {
                if (m.name.startsWith("handler$")) {
                    return failPassable(cn.name, methodName,
                            "unexpected tail Mixin handler " + m.owner + "." + m.name + m.desc);
                }
                if (CIR_INTERNAL.equals(m.owner)
                        || "org/spongepowered/asm/mixin/injection/callback/CallbackInfo".equals(m.owner)
                        || (m.desc != null && (m.desc.contains(CIR_INTERNAL)
                                || m.desc.contains("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")))) {
                    return failPassable(cn.name, methodName, "CIR used in tail");
                }
            }
        }

        Set<LabelNode> removedSpanLabels = new HashSet<>();
        for (AbstractInsnNode p = firstReal; p != null && p != ifeqAfter.label; p = p.getNext()) {
            if (p instanceof LabelNode ln) {
                removedSpanLabels.add(ln);
            }
            if (p == retInsn) break;
        }
        if (!removedSpanLabels.isEmpty()) {
            for (AbstractInsnNode p = target.instructions.getFirst(); p != null; p = p.getNext()) {
                if (p instanceof JumpInsnNode j && removedSpanLabels.contains(j.label)) {
                    return failPassable(cn.name, methodName, "jump into removed span");
                }
                if (p instanceof TableSwitchInsnNode ts
                        && (removedSpanLabels.contains(ts.dflt) || ts.labels.stream().anyMatch(removedSpanLabels::contains))) {
                    return failPassable(cn.name, methodName, "tableswitch into removed span");
                }
                if (p instanceof LookupSwitchInsnNode ls
                        && (removedSpanLabels.contains(ls.dflt) || ls.labels.stream().anyMatch(removedSpanLabels::contains))) {
                    return failPassable(cn.name, methodName, "lookupswitch into removed span");
                }
            }
        }
        if (target.tryCatchBlocks != null) {
            Set<LabelNode> replacedWithEndpoints = new HashSet<>(removedSpanLabels);
            for (var tcb : target.tryCatchBlocks) {
                if (replacedWithEndpoints.contains(tcb.start) || replacedWithEndpoints.contains(tcb.end)
                        || replacedWithEndpoints.contains(tcb.handler)) {
                    return failPassable(cn.name, methodName, "tryCatch in prologue");
                }
            }
        }
        if (target.localVariables != null && !removedSpanLabels.isEmpty()) {
            for (var lv : target.localVariables) {
                if (lv.index != cirVar && (removedSpanLabels.contains(lv.start) || removedSpanLabels.contains(lv.end))) {
                    return failPassable(cn.name, methodName, "local var label in prologue");
                }
            }
        }

        boolean hasFrameAfterLabel = false;
        for (AbstractInsnNode p = ifeqAfter.label.getNext(); p != null && p.getOpcode() < 0; p = p.getNext()) {
            if (p instanceof FrameNode) {
                hasFrameAfterLabel = true;
                break;
            }
        }

        InsnList fastGuard = new InsnList();
        if (hasFrameAfterLabel) {
            // Keep existing Mixin StackMapTable frames valid without allocating a CIR object.
            fastGuard.add(new InsnNode(Opcodes.ACONST_NULL));
            fastGuard.add(new VarInsnNode(Opcodes.ASTORE, cirVar));
        }
        fastGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        String guardMethod = kind == KIND_VIEW_BLOCKING ? "shouldCancelViewBlocking" : "isPassableState";
        fastGuard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OCCLUSION_PERF, guardMethod,
                "(L" + BLOCK_STATE_BASE + ";)Z", false));
        fastGuard.add(new JumpInsnNode(Opcodes.IFEQ, ifeqAfter.label));
        if (isFloat) {
            fastGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fastGuard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fastGuard.add(new VarInsnNode(Opcodes.ALOAD, 2));
            fastGuard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OCCLUSION_PERF, "evalPassableShadeBrightness",
                    "(L" + BLOCK_STATE_BASE + ";L" + BLOCK_GETTER + ";L" + BLOCK_POS + ";)F", false));
            fastGuard.add(new InsnNode(Opcodes.FRETURN));
        } else {
            fastGuard.add(new InsnNode(Opcodes.ICONST_0));
            fastGuard.add(new InsnNode(Opcodes.IRETURN));
        }

        target.instructions.insertBefore(firstReal, fastGuard);
        AbstractInsnNode p = firstReal;
        while (p != null) {
            AbstractInsnNode next = p.getNext();
            target.instructions.remove(p);
            if (p == retInsn) break;
            p = next;
        }
        if (!hasFrameAfterLabel) {
            target.instructions.insert(ifeqAfter.label, new FrameNode(
                    Opcodes.F_FULL, 3, new Object[] { cn.name, BLOCK_GETTER, BLOCK_POS }, 0, new Object[0]));
        }
        if (target.localVariables != null) {
            target.localVariables.removeIf(lv -> lv.index == cirVar);
        }
        target.maxStack = Math.max(target.maxStack, 3);
        target.maxLocals = Math.max(target.maxLocals, hasFrameAfterLabel ? cirVar + 1 : 3);
        return true;
    }

    private static boolean failPassable(String className, String methodName, String reason) {
        LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}.{}: {}", className, methodName, reason);
        return false;
    }

    private static AbstractInsnNode firstRealInsn(InsnList insns) {
        AbstractInsnNode cur = insns.getFirst();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    public static AbstractInsnNode nextRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode cur = insn == null ? null : insn.getNext();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    public static MethodNode findMethod(ClassNode cn, String name, String desc) {
        if (cn.methods == null) return null;
        for (MethodNode m : cn.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) return m;
        }
        return null;
    }

    public static boolean hasField(ClassNode cn, String name, String desc) {
        if (cn.fields == null) return false;
        for (FieldNode f : cn.fields) {
            if (name.equals(f.name) && desc.equals(f.desc)) return true;
        }
        return false;
    }
}
