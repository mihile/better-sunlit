package dev.sunlit.bettersunlit.inline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-closed post-apply ASM bytecode rewrites applied by {@link EntryMixinPlugin} after all
 * lower-priority Mixins have merged into target classes.
 */
public final class EntryPostApplyPatches {
    private static final Logger LOG = LoggerFactory.getLogger("sunlit_fish_visuals");

    public static final String CIR_INTERNAL = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String BLOCK_STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String OCCLUSION_PERF = "dev/sunlit/bettersunlit/perf/BlockOcclusionPerf";
    private static final String OCC_CACHE_FIELD = "sunlit$occCache";

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
        if (sawAlreadyPatched) return true;
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
        return true;
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
