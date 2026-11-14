package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.inline.EntryPostApplyPatches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
 * Client-only fail-closed post-apply ASM bytecode rewrites applied by {@code EntryMixinPlugin}
 * after all lower-priority Mixins have merged into client target classes. Kept under
 * {@code dev.sunlit.bettersunlit.client} so server JAR builds never contain client references.
 */
public final class ClientPostApplyPatches {
    private static final Logger LOG = LoggerFactory.getLogger("sunlit_fish_visuals");

    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    private static final String POLYGON_ARRAY = "[Lnet/minecraft/client/model/geom/ModelPart$Polygon;";
    private static final String EMF_RENDER_PERF = "dev/sunlit/bettersunlit/client/EmfRenderPerf";

    private ClientPostApplyPatches() {}

    /**
     * Guards Optimised Block Entities' Mixin {@code CallbackInfoReturnable} allocation at the entry of
     * {@code BlockModelShaper.m_110893_(BlockState)} behind {@code state.m_155947_()} ({@code hasBlockEntity()}).
     *
     * @return {@code true} if the guard was inserted (or already present), {@code false} if an
     * unexpected instruction sequence caused a fail-closed abort.
     */
    public static boolean patchBlockModelShaper(ClassNode cn) {
        if (cn == null) return false;
        String desc = "(L" + BLOCK_STATE + ";)L" + BAKED_MODEL + ";";
        MethodNode target = EntryPostApplyPatches.findMethod(cn, "m_110893_", desc);
        if (target == null) {
            LOG.warn("[BetterSunlit-Perf] Skipping BlockModelShaper rewrite: m_110893_ missing on {}", cn.name);
            return false;
        }

        // Collect all CIR instantiations and OBE callback calls fail-closed.
        List<TypeInsnNode> cirNews = new ArrayList<>();
        MethodInsnNode obeCall = null;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && EntryPostApplyPatches.CIR_INTERNAL.equals(t.desc)) {
                cirNews.add(t);
            } else if (insn instanceof MethodInsnNode m) {
                if (BLOCK_STATE.equals(m.owner) && "m_155947_".equals(m.name) && "()Z".equals(m.desc)
                        && cirNews.isEmpty()) {
                    // Guard is already inserted ahead of the first CIR allocation.
                    return true;
                }
                if (cn.name.equals(m.owner)
                        && ("(L" + BLOCK_STATE + ";L" + EntryPostApplyPatches.CIR_INTERNAL + ";)V").equals(m.desc)
                        && (m.name.endsWith("$obe$getBlockStateModel") || "obe$getBlockStateModel".equals(m.name))) {
                    if (obeCall != null) {
                        LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: multiple OBE calls in m_110893_", cn.name);
                        return false;
                    }
                    obeCall = m;
                }
            }
        }
        if (obeCall == null || cirNews.size() != 1) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected OBE/CIR layout (cirNews={}, obeCall={})",
                    cn.name, cirNews.size(), obeCall != null);
            return false;
        }

        TypeInsnNode cirNew = cirNews.get(0);
        // Ensure no real instruction precedes cirNew.
        for (AbstractInsnNode cur = target.instructions.getFirst(); cur != null && cur != cirNew; cur = cur.getNext()) {
            if (cur.getOpcode() >= 0) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: instruction {} precedes CIR allocation",
                        cn.name, cur.getOpcode());
                return false;
            }
        }

        // Locate ASTORE <cirVar> right after CallbackInfoReturnable.<init>, before obeCall.
        int cirVar = -1;
        for (AbstractInsnNode cur = cirNew.getNext(); cur != null && cur != obeCall; cur = cur.getNext()) {
            if (cur instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                cirVar = v.var;
            }
        }
        if (cirVar < 2) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: invalid CIR local slot {}", cn.name, cirVar);
            return false;
        }

        // Locate IFEQ <afterObeLabel> -> ARETURN immediately following obeCall.
        JumpInsnNode ifeqAfterObe = null;
        AbstractInsnNode cur = obeCall.getNext();
        for (int steps = 0; cur != null && steps < 8; cur = cur.getNext(), steps++) {
            if (cur instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFEQ) {
                ifeqAfterObe = j;
                break;
            }
        }
        if (ifeqAfterObe == null || ifeqAfterObe.label == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing IFEQ after OBE callback", cn.name);
            return false;
        }
        boolean sawReturn = false;
        for (AbstractInsnNode r = ifeqAfterObe.getNext(); r != null && r != ifeqAfterObe.label; r = r.getNext()) {
            if (r.getOpcode() == Opcodes.ARETURN) {
                sawReturn = true;
                break;
            }
        }
        if (!sawReturn) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing ARETURN before OBE jump target", cn.name);
            return false;
        }
        // Verify cirVar is dead after the OBE return block.
        for (AbstractInsnNode tail = ifeqAfterObe.label; tail != null; tail = tail.getNext()) {
            if (tail instanceof VarInsnNode v && v.var == cirVar) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: CIR local {} used after OBE block", cn.name, cirVar);
                return false;
            }
        }

        LabelNode lRunObe = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BLOCK_STATE, "m_155947_", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, lRunObe));
        // Assign null before jumping so any pre-existing FrameNode referencing cirVar validates.
        guard.add(new InsnNode(Opcodes.ACONST_NULL));
        guard.add(new VarInsnNode(Opcodes.ASTORE, cirVar));
        guard.add(new JumpInsnNode(Opcodes.GOTO, ifeqAfterObe.label));
        guard.add(lRunObe);
        guard.add(new FrameNode(Opcodes.F_FULL, 2, new Object[] { cn.name, BLOCK_STATE }, 0, new Object[0]));

        target.instructions.insertBefore(cirNew, guard);
        target.maxStack = Math.max(target.maxStack, 4);
        target.maxLocals = Math.max(target.maxLocals, cirVar + 1);
        return true;
    }

    /**
     * Binds {@code sunlit$polygons()Object[]} on {@code ModelPart$Cube} to direct
     * {@code GETFIELD f_104341_} without runtime reflection or compile-time visibility on
     * package-private {@code ModelPart$Polygon}.
     */
    public static boolean patchModelPartCube(ClassNode cn) {
        if (cn == null) return false;
        MethodNode getter = EntryPostApplyPatches.findMethod(cn, "sunlit$polygons", "()[Ljava/lang/Object;");
        if (getter == null || !EntryPostApplyPatches.hasField(cn, "f_104341_", POLYGON_ARRAY)) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing sunlit$polygons or f_104341_", cn.name);
            return false;
        }
        AbstractInsnNode first = firstRealInsn(getter);
        if (first instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
            AbstractInsnNode second = EntryPostApplyPatches.nextRealInsn(first);
            AbstractInsnNode third = EntryPostApplyPatches.nextRealInsn(second);
            if (second instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                    && cn.name.equals(f.owner) && "f_104341_".equals(f.name) && POLYGON_ARRAY.equals(f.desc)
                    && third != null && third.getOpcode() == Opcodes.ARETURN) {
                return true;
            }
        }
        getter.instructions.clear();
        if (getter.tryCatchBlocks != null) getter.tryCatchBlocks.clear();
        if (getter.localVariables != null) getter.localVariables.clear();
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "f_104341_", POLYGON_ARRAY));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        return true;
    }

    /**
     * Replaces {@code expressionString.chars().mapToObj(c -> (char) c).iterator()} inside
     * {@code MathExpressionParser.<init>} with a direct allocation-minimal
     * {@code EmfRenderPerf.charIterator(String)} call.
     */
    public static boolean patchMathExpressionParser(ClassNode cn) {
        if (cn == null) return false;
        MethodNode target = EntryPostApplyPatches.findMethod(
                cn,
                "<init>",
                "(Ljava/lang/String;ZLtraben/entity_model_features/models/animation/AnimSetupContext;Z)V");
        if (target == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: target constructor missing", cn.name);
            return false;
        }
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m
                    && EMF_RENDER_PERF.equals(m.owner)
                    && "charIterator".equals(m.name)) {
                return true;
            }
        }

        MethodInsnNode charsCall = null;
        int matchCount = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m
                    && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "java/lang/String".equals(m.owner)
                    && "chars".equals(m.name)
                    && "()Ljava/util/stream/IntStream;".equals(m.desc)) {
                charsCall = m;
                matchCount++;
            }
        }
        if (matchCount != 1 || charsCall == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected String.chars() count {}", cn.name, matchCount);
            return false;
        }

        AbstractInsnNode n1 = EntryPostApplyPatches.nextRealInsn(charsCall);
        AbstractInsnNode n2 = EntryPostApplyPatches.nextRealInsn(n1);
        AbstractInsnNode n3 = EntryPostApplyPatches.nextRealInsn(n2);
        if (!(n1 instanceof InvokeDynamicInsnNode indy
                && "apply".equals(indy.name)
                && "()Ljava/util/function/IntFunction;".equals(indy.desc))
                || !(n2 instanceof MethodInsnNode mapCall
                && mapCall.getOpcode() == Opcodes.INVOKEINTERFACE
                && "java/util/stream/IntStream".equals(mapCall.owner)
                && "mapToObj".equals(mapCall.name)
                && "(Ljava/util/function/IntFunction;)Ljava/util/stream/Stream;".equals(mapCall.desc))
                || !(n3 instanceof MethodInsnNode iterCall
                && iterCall.getOpcode() == Opcodes.INVOKEINTERFACE
                && "java/util/stream/Stream".equals(iterCall.owner)
                && "iterator".equals(iterCall.name)
                && "()Ljava/util/Iterator;".equals(iterCall.desc))) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: stream iterator chain mismatch", cn.name);
            return false;
        }

        MethodInsnNode directCall = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EMF_RENDER_PERF,
                "charIterator",
                "(Ljava/lang/String;)Ljava/util/Iterator;",
                false);
        target.instructions.set(charsCall, directCall);
        target.instructions.remove(n1);
        target.instructions.remove(n2);
        target.instructions.remove(n3);
        return true;
    }

    /**
     * Rewires {@code EmojiFontRenderer}'s width and batch draw entry points to our allocation-free
     * Mixin fast paths while retaining Emojiful's original implementations as {@code sunlit$orig*}
     * fallbacks and binding {@code sunlit$superRenderText} via {@code INVOKESPECIAL Font.m_271978_}.
     */
    public static boolean patchEmojiFontRenderer(ClassNode cn) {
        if (cn == null || !"net/minecraft/client/gui/Font".equals(cn.superName)) {
            if (cn != null) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected superclass {}", cn.name, cn.superName);
            }
            return false;
        }

        final String dWidthStr = "(Ljava/lang/String;)I";
        final String dWidthSeq = "(Lnet/minecraft/util/FormattedCharSequence;)I";
        final String dDrawStr = "(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F";
        final String dDrawSeq = "(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I";

        MethodNode superRender = EntryPostApplyPatches.findMethod(cn, "sunlit$superRenderText", dDrawStr);
        MethodNode origWidthStr = EntryPostApplyPatches.findMethod(cn, "m_92895_", dWidthStr);
        MethodNode origWidthSeq = EntryPostApplyPatches.findMethod(cn, "m_92724_", dWidthSeq);
        MethodNode origDrawStr = EntryPostApplyPatches.findMethod(cn, "m_271978_", dDrawStr);
        MethodNode origDrawSeq = EntryPostApplyPatches.findMethod(cn, "m_272191_", dDrawSeq);

        MethodNode fastWidthStr = EntryPostApplyPatches.findMethod(cn, "sunlit$fastWidthString", dWidthStr);
        MethodNode fastWidthSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$fastWidthSeq", dWidthSeq);
        MethodNode fastDrawStr = EntryPostApplyPatches.findMethod(cn, "sunlit$fastDrawString", dDrawStr);
        MethodNode fastDrawSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$fastDrawSeq", dDrawSeq);

        MethodNode stubOrigWidthStr = EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthString", dWidthStr);
        MethodNode stubOrigWidthSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthSeq", dWidthSeq);
        MethodNode stubOrigDrawStr = EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawString", dDrawStr);
        MethodNode stubOrigDrawSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawSeq", dDrawSeq);

        if (fastWidthStr == null && fastWidthSeq == null && fastDrawStr == null && fastDrawSeq == null
                && stubOrigWidthStr == null && stubOrigWidthSeq == null
                && stubOrigDrawStr == null && stubOrigDrawSeq == null
                && EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawSeq", dDrawSeq) == null
                && EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthString", dWidthStr) == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing merged fast methods", cn.name);
            return false;
        }
        if (fastWidthStr == null && fastWidthSeq == null && fastDrawStr == null && fastDrawSeq == null
                && EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawSeq", dDrawSeq) != null) {
            return true;
        }

        if (superRender == null || origWidthStr == null || origWidthSeq == null || origDrawStr == null || origDrawSeq == null
                || fastWidthStr == null || fastWidthSeq == null || fastDrawStr == null || fastDrawSeq == null
                || stubOrigWidthStr == null || stubOrigWidthSeq == null || stubOrigDrawStr == null || stubOrigDrawSeq == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: incomplete Mixin method set", cn.name);
            return false;
        }

        superRender.instructions.clear();
        if (superRender.tryCatchBlocks != null) superRender.tryCatchBlocks.clear();
        if (superRender.localVariables != null) superRender.localVariables.clear();
        superRender.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        superRender.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        superRender.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        superRender.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        superRender.instructions.add(new VarInsnNode(Opcodes.ILOAD, 4));
        superRender.instructions.add(new VarInsnNode(Opcodes.ILOAD, 5));
        superRender.instructions.add(new VarInsnNode(Opcodes.ALOAD, 6));
        superRender.instructions.add(new VarInsnNode(Opcodes.ALOAD, 7));
        superRender.instructions.add(new VarInsnNode(Opcodes.ALOAD, 8));
        superRender.instructions.add(new VarInsnNode(Opcodes.ILOAD, 9));
        superRender.instructions.add(new VarInsnNode(Opcodes.ILOAD, 10));
        superRender.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "net/minecraft/client/gui/Font",
                "m_271978_",
                dDrawStr,
                false));
        superRender.instructions.add(new InsnNode(Opcodes.FRETURN));
        superRender.maxStack = 11;
        superRender.maxLocals = 11;

        swapMethodWithFast(cn, origWidthStr, fastWidthStr, stubOrigWidthStr, "m_92895_", "sunlit$origWidthString");
        swapMethodWithFast(cn, origWidthSeq, fastWidthSeq, stubOrigWidthSeq, "m_92724_", "sunlit$origWidthSeq");
        swapMethodWithFast(cn, origDrawStr, fastDrawStr, stubOrigDrawStr, "m_271978_", "sunlit$origDrawString");
        swapMethodWithFast(cn, origDrawSeq, fastDrawSeq, stubOrigDrawSeq, "m_272191_", "sunlit$origDrawSeq");
        return true;
    }

    private static void swapMethodWithFast(
            ClassNode cn,
            MethodNode orig,
            MethodNode fast,
            MethodNode stub,
            String publicName,
            String origRename) {
        cn.methods.remove(stub);
        int origAccess = orig.access;
        orig.name = origRename;
        orig.access = stub.access;
        fast.name = publicName;
        fast.access = origAccess;
    }

    private static AbstractInsnNode firstRealInsn(MethodNode mn) {
        if (mn == null || mn.instructions == null) return null;
        AbstractInsnNode cur = mn.instructions.getFirst();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }
}
