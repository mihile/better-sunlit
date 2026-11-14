package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.inline.EntryPostApplyPatches;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
    private static final String EMOJI_RENDER_PERF = "dev/sunlit/bettersunlit/client/EmojiRenderPerf";
    private static final String EMOJI_INTERNAL = "com/hrznstudio/emojiful/api/Emoji";
    private static final String SODIUM_CONTEXT = "me/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext";
    private static final String SODIUM_CONTEXT_DESC = "L" + SODIUM_CONTEXT + ";";
    private static final String OBE_MESHING_UTIL = "fr/madu59/obe/client/util/meshing/SectionMeshingUtil";

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
            return patchGetEmojiFormattedString(cn);
        }

        if (superRender == null || origWidthStr == null || origWidthSeq == null || origDrawStr == null || origDrawSeq == null
                || fastWidthStr == null || fastWidthSeq == null || fastDrawStr == null || fastDrawSeq == null
                || stubOrigWidthStr == null || stubOrigWidthSeq == null || stubOrigDrawStr == null || stubOrigDrawSeq == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: incomplete Mixin method set", cn.name);
            return false;
        }

        if (!patchGetEmojiFormattedString(cn)) {
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

    /**
     * Guards {@code EmojiFontRenderer.getEmojiFormattedString} with a pre-filter short-circuit
     * and a per-emoji {@link EmojiRenderPerf#mayEmojiMatchClean} guard so strings with no matching
     * triggers skip allocating ~1,000+ {@code Matcher} objects.
     */
    public static boolean patchGetEmojiFormattedString(ClassNode cn) {
        if (cn == null) return false;
        String desc = "(Ljava/lang/String;)Lorg/apache/commons/lang3/tuple/Pair;";
        MethodNode m = EntryPostApplyPatches.findMethod(cn, "getEmojiFormattedString", desc);
        if (m == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: getEmojiFormattedString missing", cn.name);
            return false;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && EMOJI_RENDER_PERF.equals(mi.owner)
                    && "mayEmojiMatchClean".equals(mi.name)) {
                return true;
            }
        }

        // Locate: GETSTATIC Constants.EMOJI_LIST, GOTO loopHead, and CHECKCAST Emoji -> ASTORE emojiVar -> ALOAD emojiVar -> Emoji.getRegex()
        FieldInsnNode emojiListGet = null;
        JumpInsnNode preLoopReturnJump = null;
        LabelNode loopHeadLabel = null;
        MethodInsnNode getRegexCall = null;
        VarInsnNode cleanTextStore = null;
        VarInsnNode emojiLoadBeforeRegex = null;
        int emojiVar = -1;
        int cleanVar = -1;

        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && "net/minecraft/ChatFormatting".equals(mi.owner)
                    && "m_126649_".equals(mi.name)
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(mi.desc)
                    && EntryPostApplyPatches.nextRealInsn(mi) instanceof VarInsnNode v
                    && v.getOpcode() == Opcodes.ASTORE) {
                cleanTextStore = v;
                cleanVar = v.var;
            } else if (insn instanceof MethodInsnNode mi
                    && "io/netty/util/internal/StringUtil".equals(mi.owner)
                    && "isNullOrEmpty".equals(mi.name)
                    && "(Ljava/lang/String;)Z".equals(mi.desc)
                    && preLoopReturnJump == null) {
                AbstractInsnNode nxt = EntryPostApplyPatches.nextRealInsn(mi);
                if (nxt instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFNE && j.label != null) {
                    preLoopReturnJump = j;
                }
            } else if (insn instanceof FieldInsnNode fi
                    && fi.getOpcode() == Opcodes.GETSTATIC
                    && "com/hrznstudio/emojiful/Constants".equals(fi.owner)
                    && "EMOJI_LIST".equals(fi.name)) {
                emojiListGet = fi;
            } else if (insn instanceof MethodInsnNode mi
                    && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && EMOJI_INTERNAL.equals(mi.owner)
                    && "getRegex".equals(mi.name)
                    && "()Ljava/util/regex/Pattern;".equals(mi.desc)) {
                getRegexCall = mi;
                AbstractInsnNode prev1 = prevRealInsn(mi);
                AbstractInsnNode prev2 = prevRealInsn(prev1);
                if (prev1 instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && prev2 instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE
                        && load.var == store.var) {
                    emojiLoadBeforeRegex = load;
                    emojiVar = store.var;
                }
            } else if (insn instanceof JumpInsnNode j
                    && j.getOpcode() == Opcodes.GOTO
                    && j.label != null
                    && emojiListGet != null
                    && getRegexCall != null) {
                // Only update on the outer loop back-edge whose target precedes getRegexCall and invokes Iterator.hasNext
                AbstractInsnNode targetReal = EntryPostApplyPatches.nextRealInsn(j.label);
                AbstractInsnNode targetSecond = EntryPostApplyPatches.nextRealInsn(targetReal);
                if (targetReal instanceof VarInsnNode lv && lv.getOpcode() == Opcodes.ALOAD
                        && targetSecond instanceof MethodInsnNode hasNext
                        && "java/util/Iterator".equals(hasNext.owner)
                        && "hasNext".equals(hasNext.name)) {
                    loopHeadLabel = j.label;
                }
            }
        }

        if (cleanTextStore == null || cleanVar < 0 || preLoopReturnJump == null
                || emojiListGet == null || loopHeadLabel == null
                || getRegexCall == null || emojiLoadBeforeRegex == null || emojiVar < 0) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected getEmojiFormattedString layout", cn.name);
            return false;
        }

        InsnList preGuard = new InsnList();
        preGuard.add(new VarInsnNode(Opcodes.ALOAD, cleanVar));
        preGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EMOJI_RENDER_PERF,
                "mightContainEmoji",
                "(Ljava/lang/CharSequence;)Z",
                false));
        preGuard.add(new JumpInsnNode(Opcodes.IFEQ, preLoopReturnJump.label));
        m.instructions.insertBefore(emojiListGet, preGuard);

        InsnList loopGuard = new InsnList();
        loopGuard.add(new VarInsnNode(Opcodes.ALOAD, emojiVar));
        loopGuard.add(new VarInsnNode(Opcodes.ALOAD, cleanVar));
        loopGuard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EMOJI_RENDER_PERF,
                "mayEmojiMatchClean",
                "(L" + EMOJI_INTERNAL + ";Ljava/lang/String;)Z",
                false));
        loopGuard.add(new JumpInsnNode(Opcodes.IFEQ, loopHeadLabel));
        m.instructions.insertBefore(emojiLoadBeforeRegex, loopGuard);
        m.maxStack = Math.max(m.maxStack, 4);
        return true;
    }

    /**
     * Guards OBE's {@code obe$wrapRenderModel} on Sodium/Embeddium's {@code ChunkBuilderMeshingTask}
     * so unchanged models skip allocating {@code new BlockPos(...)} and re-calling {@code ctx.update(...)}.
     */
    public static boolean patchChunkBuilderMeshingTask(ClassNode cn) {
        if (cn == null) return false;
        MethodNode target = null;
        for (MethodNode m : cn.methods) {
            if (("obe$wrapRenderModel".equals(m.name) || m.name.endsWith("$obe$wrapRenderModel"))
                    && m.desc != null
                    && m.desc.contains(SODIUM_CONTEXT_DESC)
                    && m.desc.contains("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;")) {
                if (target != null) {
                    LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: multiple obe$wrapRenderModel methods", cn.name);
                    return false;
                }
                target = m;
            }
        }
        if (target == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: obe$wrapRenderModel missing", cn.name);
            return false;
        }

        String getCorrectedDesc = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;L"
                + BAKED_MODEL + ";)L" + BAKED_MODEL + ";";
        String updateDesc = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;L"
                + BAKED_MODEL + ";JLnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V";

        MethodInsnNode getCorrectedCall = null;
        MethodInsnNode updateCall = null;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESTATIC
                        && OBE_MESHING_UTIL.equals(mi.owner)
                        && "getCorrectedModel".equals(mi.name)
                        && getCorrectedDesc.equals(mi.desc)) {
                    if (getCorrectedCall != null) {
                        LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: multiple getCorrectedModel calls", cn.name);
                        return false;
                    }
                    getCorrectedCall = mi;
                } else if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && SODIUM_CONTEXT.equals(mi.owner)
                        && "update".equals(mi.name)
                        && updateDesc.equals(mi.desc)) {
                    if (updateCall != null) {
                        LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: multiple BlockRenderContext.update calls", cn.name);
                        return false;
                    }
                    updateCall = mi;
                }
            }
        }
        if (getCorrectedCall == null || updateCall == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing OBE model/update anchors", cn.name);
            return false;
        }

        AbstractInsnNode modelStoreNode = EntryPostApplyPatches.nextRealInsn(getCorrectedCall);
        if (!(modelStoreNode instanceof VarInsnNode modelStore) || modelStore.getOpcode() != Opcodes.ASTORE) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: expected ASTORE after getCorrectedModel", cn.name);
            return false;
        }
        int modelVar = modelStore.var;

        // Idempotency check: already inserted `ALOAD modelVar; ALOAD ctxVar; BlockRenderContext.model(); IF_ACMPEQ`.
        AbstractInsnNode afterStore = EntryPostApplyPatches.nextRealInsn(modelStore);
        AbstractInsnNode s2 = EntryPostApplyPatches.nextRealInsn(afterStore);
        AbstractInsnNode s3 = EntryPostApplyPatches.nextRealInsn(s2);
        AbstractInsnNode s4 = EntryPostApplyPatches.nextRealInsn(s3);
        if (afterStore instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == modelVar
                && s3 instanceof MethodInsnNode mc && SODIUM_CONTEXT.equals(mc.owner) && "model".equals(mc.name)
                && s4 instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ACMPEQ) {
            return true;
        }

        if (!(afterStore instanceof TypeInsnNode newBp
                && newBp.getOpcode() == Opcodes.NEW
                && "net/minecraft/core/BlockPos".equals(newBp.desc))) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected insn after getCorrectedModel ASTORE", cn.name);
            return false;
        }

        // Verify no pre-existing FrameNodes or unexpected origin reuse after updateCall.
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected FrameNode in obe$wrapRenderModel", cn.name);
                return false;
            }
        }

        int originVar = -1;
        for (AbstractInsnNode cur = newBp.getNext(); cur != null && cur != updateCall; cur = cur.getNext()) {
            if (cur instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                originVar = v.var;
            }
        }
        if (originVar <= modelVar) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: invalid origin local {}", cn.name, originVar);
            return false;
        }
        AbstractInsnNode afterUpdate = EntryPostApplyPatches.nextRealInsn(updateCall);
        if (!(afterUpdate instanceof VarInsnNode loadOp && loadOp.getOpcode() == Opcodes.ALOAD)) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: expected ALOAD original after update", cn.name);
            return false;
        }
        for (AbstractInsnNode tail = afterUpdate; tail != null; tail = tail.getNext()) {
            if (tail instanceof VarInsnNode v && v.var == originVar) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: origin local {} used after update", cn.name, originVar);
                return false;
            }
        }

        // Locate ctxVar parameter slot and construct exact self-contained F_FULL frame at afterUpdate.
        boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
        List<Object> fullLocals = new ArrayList<>();
        int slot = 0;
        if (!isStatic) {
            fullLocals.add(cn.name);
            slot = 1;
        }
        int ctxVar = -1;
        for (Type argType : Type.getArgumentTypes(target.desc)) {
            if (SODIUM_CONTEXT_DESC.equals(argType.getDescriptor())) {
                ctxVar = slot;
            }
            fullLocals.add(asmFrameType(argType));
            slot += argType.getSize();
        }
        if (ctxVar < 0 || slot != modelVar) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: param slot mismatch (ctxVar={}, paramSlots={}, modelVar={})",
                    cn.name, ctxVar, slot, modelVar);
            return false;
        }
        fullLocals.add(BAKED_MODEL);

        LabelNode skipUpdateLabel = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, modelVar));
        guard.add(new VarInsnNode(Opcodes.ALOAD, ctxVar));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SODIUM_CONTEXT, "model", "()L" + BAKED_MODEL + ";", false));
        guard.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, skipUpdateLabel));
        target.instructions.insert(modelStore, guard);

        InsnList targetSite = new InsnList();
        targetSite.add(skipUpdateLabel);
        targetSite.add(new FrameNode(Opcodes.F_FULL, fullLocals.size(), fullLocals.toArray(), 0, new Object[0]));
        target.instructions.insertBefore(afterUpdate, targetSite);
        target.maxStack = Math.max(target.maxStack, 4);
        return true;
    }

    private static Object asmFrameType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor();
            default -> type.getInternalName();
        };
    }

    private static AbstractInsnNode prevRealInsn(AbstractInsnNode node) {
        AbstractInsnNode cur = node == null ? null : node.getPrevious();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getPrevious();
        }
        return cur;
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
