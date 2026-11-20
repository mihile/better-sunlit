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

        patchBlockModelShaperCaching(cn, target);
        return true;
    }

    private static void patchBlockModelShaperCaching(ClassNode cn, MethodNode getBlockModel) {
        String shaperPerf = "dev/sunlit/bettersunlit/client/BlockModelShaperPerf";
        String iModelHolding = "org/embeddedt/modernfix/duck/IModelHoldingBlockState";
        MethodInsnNode setModelCall = null;
        boolean alreadyMarked = false;
        for (AbstractInsnNode insn = getBlockModel.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m) {
                if (shaperPerf.equals(m.owner) && "markModelCached".equals(m.name)) {
                    alreadyMarked = true;
                } else if (iModelHolding.equals(m.owner) && "mfix$setModel".equals(m.name)) {
                    setModelCall = m;
                }
            }
        }
        if (!alreadyMarked && setModelCall != null) {
            InsnList markList = new InsnList();
            markList.add(new VarInsnNode(Opcodes.ALOAD, 1));
            markList.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, shaperPerf, "markCachedState", "(Ljava/lang/Object;)V", false));
            getBlockModel.instructions.insertBefore(setModelCall, markList);
        }

        MethodNode replaceMap = null;
        for (MethodNode m : cn.methods) {
            if ("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V".equals(m.desc)
                    && ("replaceModelMap".equals(m.name) || m.name.endsWith("$replaceModelMap"))) {
                replaceMap = m;
                break;
            }
        }
        if (replaceMap == null) {
            return;
        }
        FieldInsnNode putMapField = null;
        boolean sawRegistryLoop = false;
        for (AbstractInsnNode insn = replaceMap.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m && shaperPerf.equals(m.owner)
                    && ("consumeNeedsClear".equals(m.name) || "consumeStatesToClear".equals(m.name))) {
                return;
            }
            if (putMapField == null && insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                    && cn.name.equals(f.owner) && "f_110877_".equals(f.name)) {
                putMapField = f;
            } else if (putMapField != null && insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                    && "net/minecraft/core/registries/BuiltInRegistries".equals(f.owner)
                    && "f_256975_".equals(f.name)) {
                sawRegistryLoop = true;
            }
        }
        if (putMapField == null || !sawRegistryLoop) {
            return;
        }
        int arrSlot = Math.max(2, replaceMap.maxLocals);
        int idxSlot = arrSlot + 1;
        int lenSlot = arrSlot + 2;
        LabelNode clearAllLabel = new LabelNode();
        LabelNode loopStart = new LabelNode();
        LabelNode loopDone = new LabelNode();
        InsnList fastSkip = new InsnList();
        fastSkip.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, shaperPerf, "consumeStatesToClear", "()[Ljava/lang/Object;", false));
        fastSkip.add(new InsnNode(Opcodes.DUP));
        fastSkip.add(new VarInsnNode(Opcodes.ASTORE, arrSlot));
        fastSkip.add(new JumpInsnNode(Opcodes.IFNULL, clearAllLabel));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, arrSlot));
        fastSkip.add(new InsnNode(Opcodes.ARRAYLENGTH));
        fastSkip.add(new VarInsnNode(Opcodes.ISTORE, lenSlot));
        fastSkip.add(new InsnNode(Opcodes.ICONST_0));
        fastSkip.add(new VarInsnNode(Opcodes.ISTORE, idxSlot));
        fastSkip.add(loopStart);
        fastSkip.add(new FrameNode(
                Opcodes.F_FULL,
                5,
                new Object[] {
                        cn.name,
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                        "[Ljava/lang/Object;",
                        Opcodes.INTEGER,
                        Opcodes.INTEGER
                },
                0,
                new Object[0]));
        fastSkip.add(new VarInsnNode(Opcodes.ILOAD, idxSlot));
        fastSkip.add(new VarInsnNode(Opcodes.ILOAD, lenSlot));
        fastSkip.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopDone));
        fastSkip.add(new VarInsnNode(Opcodes.ALOAD, arrSlot));
        fastSkip.add(new VarInsnNode(Opcodes.ILOAD, idxSlot));
        fastSkip.add(new InsnNode(Opcodes.AALOAD));
        fastSkip.add(new TypeInsnNode(Opcodes.CHECKCAST, iModelHolding));
        fastSkip.add(new InsnNode(Opcodes.ACONST_NULL));
        fastSkip.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                iModelHolding,
                "mfix$setModel",
                "(L" + BAKED_MODEL + ";)V",
                true));
        fastSkip.add(new org.objectweb.asm.tree.IincInsnNode(idxSlot, 1));
        fastSkip.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        fastSkip.add(loopDone);
        fastSkip.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[] { cn.name, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo" },
                0,
                new Object[0]));
        fastSkip.add(new InsnNode(Opcodes.RETURN));
        fastSkip.add(clearAllLabel);
        fastSkip.add(new FrameNode(
                Opcodes.F_FULL,
                2,
                new Object[] { cn.name, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo" },
                0,
                new Object[0]));
        replaceMap.instructions.insert(putMapField, fastSkip);
        replaceMap.maxStack = Math.max(replaceMap.maxStack, 4);
        replaceMap.maxLocals = Math.max(replaceMap.maxLocals, lenSlot + 1);
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
     * Binds {@code sunlit$rawPalette()Object} on {@code PalettedContainer} to lock-free
     * {@code this.f_188032_.f_188102_()} without compile-time visibility on package-private
     * {@code PalettedContainer$Data}.
     */
    public static boolean patchPalettedContainer(ClassNode cn) {
        if (cn == null) return false;
        final String dataDesc = "Lnet/minecraft/world/level/chunk/PalettedContainer$Data;";
        final String dataOwner = "net/minecraft/world/level/chunk/PalettedContainer$Data";
        MethodNode getter = EntryPostApplyPatches.findMethod(cn, "sunlit$rawPalette", "()Ljava/lang/Object;");
        if (getter == null || !EntryPostApplyPatches.hasField(cn, "f_188032_", dataDesc)) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing sunlit$rawPalette or f_188032_", cn.name);
            return false;
        }
        AbstractInsnNode first = firstRealInsn(getter);
        if (first instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
            AbstractInsnNode second = EntryPostApplyPatches.nextRealInsn(first);
            if (second instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                    && cn.name.equals(f.owner) && "f_188032_".equals(f.name) && dataDesc.equals(f.desc)) {
                return true;
            }
        }
        getter.instructions.clear();
        if (getter.tryCatchBlocks != null) getter.tryCatchBlocks.clear();
        if (getter.localVariables != null) getter.localVariables.clear();
        LabelNode isNull = new LabelNode();
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "f_188032_", dataDesc));
        getter.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getter.instructions.add(new JumpInsnNode(Opcodes.IFNULL, isNull));
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getter.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                dataOwner,
                "f_188102_",
                "()Lnet/minecraft/world/level/chunk/Palette;",
                false));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.instructions.add(isNull);
        getter.instructions.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] { dataOwner }, 0, new Object[0]));
        getter.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 2;
        return true;
    }

    /**
     * Binds {@code sunlit$cubes()List} on {@code EMFModelPart} to direct
     * {@code GETFIELD f_104212_} ({@code ModelPart.cubes}) so {@code m_104290_}
     * can traverse cubes by index without allocating an {@code ArrayList$Itr}.
     */
    public static boolean patchEmfModelPart(ClassNode cn) {
        if (cn == null) return false;
        MethodNode cubesGetter = EntryPostApplyPatches.findMethod(cn, "sunlit$cubes", "()Ljava/util/List;");
        if (cubesGetter == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing sunlit$cubes", cn.name);
            return false;
        }
        bindInstanceFieldGetter(cn, cubesGetter, "f_104212_", "Ljava/util/List;");
        MethodNode childrenGetter = EntryPostApplyPatches.findMethod(cn, "sunlit$children", "()Ljava/util/Map;");
        if (childrenGetter != null) {
            bindInstanceFieldGetter(cn, childrenGetter, "f_104213_", "Ljava/util/Map;");
        }
        return true;
    }

    private static void bindInstanceFieldGetter(ClassNode cn, MethodNode getter, String fieldName, String fieldDesc) {
        AbstractInsnNode first = firstRealInsn(getter);
        if (first instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
            AbstractInsnNode second = EntryPostApplyPatches.nextRealInsn(first);
            AbstractInsnNode third = EntryPostApplyPatches.nextRealInsn(second);
            if (second instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                    && cn.name.equals(f.owner) && fieldName.equals(f.name) && fieldDesc.equals(f.desc)
                    && third != null && third.getOpcode() == Opcodes.ARETURN) {
                return;
            }
        }
        getter.instructions.clear();
        if (getter.tryCatchBlocks != null) getter.tryCatchBlocks.clear();
        if (getter.localVariables != null) getter.localVariables.clear();
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, fieldName, fieldDesc));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
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
        return patchMathReadVariableOrConstant(cn);
    }

    private static boolean patchMathReadVariableOrConstant(ClassNode cn) {
        MethodNode m = EntryPostApplyPatches.findMethod(
                cn,
                "readVariableOrConstant",
                "(Ltraben/entity_model_features/models/animation/math/expression_tree/MathExpressionParser$RollingReader;)V");
        if (m == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: readVariableOrConstant missing", cn.name);
            return false;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && EMF_RENDER_PERF.equals(mi.owner)
                    && "parseFloatOrThrowFast".equals(mi.name)) {
                return true;
            }
        }
        MethodInsnNode parseFloatCall = null;
        int count = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && mi.getOpcode() == Opcodes.INVOKESTATIC
                    && "java/lang/Float".equals(mi.owner)
                    && "parseFloat".equals(mi.name)
                    && "(Ljava/lang/String;)F".equals(mi.desc)) {
                parseFloatCall = mi;
                count++;
            }
        }
        if (count != 1 || parseFloatCall == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: unexpected parseFloat count {}", cn.name, count);
            return false;
        }
        m.instructions.set(parseFloatCall, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EMF_RENDER_PERF,
                "parseFloatOrThrowFast",
                "(Ljava/lang/String;)F",
                false));
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
        final String dWidthText = "(Lnet/minecraft/network/chat/FormattedText;)I";
        final String dDrawStr = "(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F";
        final String dDrawSeq = "(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I";

        MethodNode superRender = EntryPostApplyPatches.findMethod(cn, "sunlit$superRenderText", dDrawStr);
        MethodNode origWidthStr = EntryPostApplyPatches.findMethod(cn, "m_92895_", dWidthStr);
        MethodNode origWidthSeq = EntryPostApplyPatches.findMethod(cn, "m_92724_", dWidthSeq);
        MethodNode origWidthText = EntryPostApplyPatches.findMethod(cn, "m_92852_", dWidthText);
        MethodNode origDrawStr = EntryPostApplyPatches.findMethod(cn, "m_271978_", dDrawStr);
        MethodNode origDrawSeq = EntryPostApplyPatches.findMethod(cn, "m_272191_", dDrawSeq);

        MethodNode fastWidthStr = EntryPostApplyPatches.findMethod(cn, "sunlit$fastWidthString", dWidthStr);
        MethodNode fastWidthSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$fastWidthSeq", dWidthSeq);
        MethodNode fastWidthText = EntryPostApplyPatches.findMethod(cn, "sunlit$fastWidthText", dWidthText);
        MethodNode fastDrawStr = EntryPostApplyPatches.findMethod(cn, "sunlit$fastDrawString", dDrawStr);
        MethodNode fastDrawSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$fastDrawSeq", dDrawSeq);

        MethodNode stubOrigWidthStr = EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthString", dWidthStr);
        MethodNode stubOrigWidthSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthSeq", dWidthSeq);
        MethodNode stubOrigWidthText = EntryPostApplyPatches.findMethod(cn, "sunlit$origWidthText", dWidthText);
        MethodNode stubOrigDrawStr = EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawString", dDrawStr);
        MethodNode stubOrigDrawSeq = EntryPostApplyPatches.findMethod(cn, "sunlit$origDrawSeq", dDrawSeq);

        if (fastWidthStr == null && fastWidthSeq == null && fastWidthText == null
                && fastDrawStr == null && fastDrawSeq == null
                && stubOrigWidthStr == null && stubOrigWidthSeq == null && stubOrigWidthText == null
                && stubOrigDrawStr == null && stubOrigDrawSeq == null) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: missing merged fast methods", cn.name);
            return false;
        }
        if (fastWidthStr == null && fastWidthSeq == null && fastWidthText == null
                && fastDrawStr == null && fastDrawSeq == null
                && stubOrigDrawSeq != null) {
            return patchGetEmojiFormattedString(cn);
        }

        boolean hasWidthTextFast = fastWidthText != null || stubOrigWidthText != null;
        if (superRender == null || origWidthStr == null || origWidthSeq == null || origDrawStr == null || origDrawSeq == null
                || fastWidthStr == null || fastWidthSeq == null || fastDrawStr == null || fastDrawSeq == null
                || stubOrigWidthStr == null || stubOrigWidthSeq == null || stubOrigDrawStr == null || stubOrigDrawSeq == null
                || (hasWidthTextFast && (origWidthText == null || fastWidthText == null || stubOrigWidthText == null))) {
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
        if (hasWidthTextFast) {
            swapMethodWithFast(cn, origWidthText, fastWidthText, stubOrigWidthText, "m_92852_", "sunlit$origWidthText");
        }
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
        // Replace `GETSTATIC Constants.EMOJI_LIST` with indexed candidate list so 0-3 emojis are scanned instead of 2,016.
        InsnList candCall = new InsnList();
        candCall.add(new VarInsnNode(Opcodes.ALOAD, cleanVar));
        candCall.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/sunlit/bettersunlit/client/EmojiCandidatePerf",
                "candidatesForClean",
                "(Ljava/lang/String;)Ljava/util/List;",
                false));
        m.instructions.insertBefore(emojiListGet, candCall);
        m.instructions.remove(emojiListGet);

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
        if (ctxVar < 0 || modelVar < slot) {
            LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: param slot mismatch (ctxVar={}, paramSlots={}, modelVar={})",
                    cn.name, ctxVar, slot, modelVar);
            return false;
        }
        for (AbstractInsnNode tail = afterUpdate; tail != null; tail = tail.getNext()) {
            if (tail instanceof VarInsnNode v && v.var >= slot) {
                LOG.warn("[BetterSunlit-Perf] Fail-closed abort on {}: local {} >= paramSlots {} used after update",
                        cn.name, v.var, slot);
                return false;
            }
        }

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

    /**
     * Replaces {@code BytecodeGen.compile(CompiledAst)} inside GBF's
     * {@code MolangCompiler.compile(IValue)} with {@link GbfMolangPerf#wrapLazy(Object, Object)} so
     * runtime expressions compile lazily on first evaluation instead of stalling resource reload.
     */
    public static void patchGbfMolangCompiler(ClassNode cn) {
        if (cn == null) return;
        final String iValueInternal = "com/eliotlash/mclib/math/IValue";
        final String compileDesc = "(L" + iValueInternal + ";)L" + iValueInternal + ";";
        final String genOwner = "com/moepus/gbf/molang/compiler/BytecodeGen";
        final String genDesc = "(Lcom/moepus/gbf/molang/compiler/CompiledAst;)L" + iValueInternal + ";";
        for (MethodNode mn : cn.methods) {
            if (!"compile".equals(mn.name) || !compileDesc.equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKESTATIC
                        && genOwner.equals(m.owner)
                        && "compile".equals(m.name)
                        && genDesc.equals(m.desc)) {
                    InsnList rep = new InsnList();
                    rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    rep.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/sunlit/bettersunlit/client/GbfMolangPerf",
                            "wrapLazy",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            false));
                    rep.add(new TypeInsnNode(Opcodes.CHECKCAST, iValueInternal));
                    mn.instructions.insertBefore(m, rep);
                    mn.instructions.remove(m);
                    mn.maxStack = Math.max(mn.maxStack + 1, 5);
                    LOG.info("[BetterSunlit-Perf] Patched GBF MolangCompiler.compile for lazy bytecode gen");
                    return;
                }
            }
        }
    }

    /**
     * Redirects Drippy Loading Screen's merged {@code loadFontsDrippy()} on {@code LoadingOverlay}
     * to asynchronous {@link DrippyFontPerf#loadFontsAsync()} so {@code FontManager.prepare(...).get()}
     * never blocks the Render thread during startup.
     */
    public static void patchLoadingOverlay(ClassNode cn) {
        if (cn == null) return;
        for (MethodNode mn : cn.methods) {
            if ("()V".equals(mn.desc)
                    && ("loadFontsDrippy".equals(mn.name) || mn.name.endsWith("$loadFontsDrippy"))) {
                mn.instructions.clear();
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/sunlit/bettersunlit/client/DrippyFontPerf",
                        "loadFontsAsync",
                        "()V",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                mn.maxStack = 1;
                mn.maxLocals = 1;
                LOG.info("[BetterSunlit-Perf] Patched {}.{} for async font preparation", cn.name, mn.name);
                return;
            }
        }
    }

    /** Adds a bulk generator only when Iris's exact four-pixel blend is already installed. */
    public static boolean patchMipmapGenerator(ClassNode cn) {
        if (cn == null) return false;
        String imageArray = "[Lcom/mojang/blaze3d/platform/NativeImage;";
        MethodNode target = EntryPostApplyPatches.findMethod(cn, "m_246246_", "(" + imageArray + "I)" + imageArray);
        MethodNode blend = EntryPostApplyPatches.findMethod(cn, "m_118048_", "(IIIIZ)I");
        if (target == null || blend == null || EntryPostApplyPatches.findMethod(cn, "weightedAverageColor", "(II)I") == null) return false;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m && "dev/sunlit/bettersunlit/client/SpriteMipmapPerf".equals(m.owner)
                    && "generateMipLevelsFast".equals(m.name)) return true;
        }
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn = blend.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) code.add(insn);
        }
        int[] opcodes = {Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.INVOKESTATIC, Opcodes.ILOAD,
                Opcodes.ILOAD, Opcodes.INVOKESTATIC, Opcodes.INVOKESTATIC, Opcodes.IRETURN};
        int[] vars = {0, 1, -1, 2, 3, -1, -1, -1};
        if (code.size() != opcodes.length) return false;
        for (int i = 0; i < opcodes.length; i++) {
            AbstractInsnNode insn = code.get(i);
            if (insn.getOpcode() != opcodes[i]) return false;
            if (vars[i] >= 0 && (!(insn instanceof VarInsnNode v) || v.var != vars[i])) return false;
            if (opcodes[i] == Opcodes.INVOKESTATIC && (!(insn instanceof MethodInsnNode m)
                    || !cn.name.equals(m.owner) || !"weightedAverageColor".equals(m.name) || !"(II)I".equals(m.desc))) return false;
        }
        AbstractInsnNode first = target.instructions.getFirst();
        while (first != null && first.getOpcode() < 0) first = first.getNext();
        if (first == null) return false;
        LabelNode fallback = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 1));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/sunlit/bettersunlit/client/SpriteMipmapPerf",
                "generateMipLevelsFast", "(" + imageArray + "I)" + imageArray, false));
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        fast.add(new InsnNode(Opcodes.ARETURN));
        fast.add(fallback);
        fast.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{imageArray}));
        fast.add(new InsnNode(Opcodes.POP));
        target.instructions.insertBefore(first, fast);
        target.maxStack = Math.max(target.maxStack, 2);
        return true;
    }

    /**
     * Prepends a bulk row-major fast path at the entry of Emberbium's
     * {@code embeddium$processTransparentImages(NativeImage, boolean)}.
     */
    public static boolean patchSpriteContents(ClassNode cn) {
        final String levelInternal = "org/embeddedt/embeddium/impl/render/chunk/sprite/SpriteTransparencyLevel";
        final String levelDesc = "L" + levelInternal + ";";
        final String fieldName = "embeddium$transparencyLevel";
        final String methodName = "embeddium$processTransparentImages";
        final String methodDesc = "(Lcom/mojang/blaze3d/platform/NativeImage;Z)V";
        final String perfInternal = "dev/sunlit/bettersunlit/client/SpriteMipmapPerf";

        MethodNode target = EntryPostApplyPatches.findMethod(cn, methodName, methodDesc);
        if (target == null || target.instructions == null || !EntryPostApplyPatches.hasField(cn, fieldName, levelDesc)) {
            return false;
        }
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode m && perfInternal.equals(m.owner) && "processTransparentFast".equals(m.name)) {
                return true;
            }
        }
        AbstractInsnNode firstReal = target.instructions.getFirst();
        while (firstReal != null && firstReal.getOpcode() < 0) {
            firstReal = firstReal.getNext();
        }
        if (firstReal == null) {
            return false;
        }

        LabelNode lFallback = new LabelNode();
        InsnList fast = new InsnList();
        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fast.add(new VarInsnNode(Opcodes.ILOAD, 2));
        fast.add(new MethodInsnNode(Opcodes.INVOKESTATIC, perfInternal, "processTransparentFast",
                "(Lcom/mojang/blaze3d/platform/NativeImage;Z)" + levelDesc, false));
        fast.add(new InsnNode(Opcodes.DUP));
        fast.add(new JumpInsnNode(Opcodes.IFNULL, lFallback));
        fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fast.add(new InsnNode(Opcodes.SWAP));
        fast.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, fieldName, levelDesc));
        fast.add(new InsnNode(Opcodes.RETURN));
        fast.add(lFallback);
        fast.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { levelInternal }));
        fast.add(new InsnNode(Opcodes.POP));

        target.instructions.insertBefore(firstReal, fast);
        target.maxStack = Math.max(target.maxStack, 3);
        return true;
    }
}
