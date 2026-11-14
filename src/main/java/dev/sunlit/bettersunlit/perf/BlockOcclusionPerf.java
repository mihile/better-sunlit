package dev.sunlit.bettersunlit.perf;

import com.simibubi.create.content.decoration.bracket.BracketBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TagsUpdatedEvent;
import snownee.passablefoliage.PassableFoliage;
import snownee.passablefoliage.PassableFoliageBlock;
import snownee.passablefoliage.PassableFoliageCommonConfig;

/**
 * Lock-free versioned occlusion cache backing {@code BlockBehaviour.BlockStateBase.m_60815_()}
 * to eliminate Copycats+ / PassableFoliage {@code CallbackInfoReturnable} allocations and
 * repeated tag lookups on chunk meshing threads.
 */
public final class BlockOcclusionPerf {
    public static final int MODE_FALSE = 1;
    public static final int MODE_TRUE = 2;
    public static final int MODE_PASSABLE_CHECK = 3;
    public static final int FLAG_UNBOUND = 4;

    private static final ResourceLocation COPYCAT_BASE = new ResourceLocation("create", "copycat_base");

    /** Monotonic cache generation; bumped on tag updates. Never zero. */
    public static volatile int generation = 1;

    private BlockOcclusionPerf() {}

    /** Forge event hook invalidating cached occlusion decisions after tags bind/reload. */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        incrementGeneration();
    }

    /** Advances the global cache generation (zeroes skipped so initial 0 fields always miss). */
    public static void incrementGeneration() {
        int next = (generation + 1) & 0x3FFFFFFF;
        if (next == 0) {
            next = 1;
        }
        generation = next;
    }

    /**
     * Evaluates Copycats+ and PassableFoliage occlusion rules for a block state whose vanilla
     * {@code f_60601_} flag is {@code true}. Returns a {@code MODE_*} constant ORed with
     * {@link #FLAG_UNBOUND} when holder tags are not yet bound so early bootstrap queries are not
     * pinned across tag binding.
     */
    public static int computeMode(BlockStateBase state) {
        boolean unbound = false;
        try {
            if (state.m_222976_().m_203373_(COPYCAT_BASE)) {
                return MODE_FALSE;
            }
        } catch (IllegalStateException ignored) {
            unbound = true;
        }
        if (state.m_60734_() instanceof BracketBlock) {
            return unbound ? (MODE_FALSE | FLAG_UNBOUND) : MODE_FALSE;
        }
        if (state instanceof BlockState blockState
                && state.m_60734_() instanceof PassableFoliageBlock passableBlock
                && passableBlock.pfoliage$isPassable()) {
            // Preserve live suppressPassableCheck & pfoliage$isPassable evaluation on leaves.
            return unbound ? (MODE_PASSABLE_CHECK | FLAG_UNBOUND) : MODE_PASSABLE_CHECK;
        }
        return unbound ? (MODE_TRUE | FLAG_UNBOUND) : MODE_TRUE;
    }

    /** Live occlusion check used for cached passable-foliage states. */
    public static boolean evalPassable(BlockStateBase state) {
        return !isPassableState(state);
    }

    /**
     * Fast allocation-free check equivalent to {@code PassableFoliage.isPassable((BlockState) state)},
     * short-circuiting on {@code pfoliage$isPassable()} before touching the ThreadLocal.
     */
    public static boolean isPassableState(BlockStateBase state) {
        return state instanceof BlockState blockState
                && state.m_60734_() instanceof PassableFoliageBlock passableBlock
                && passableBlock.pfoliage$isPassable()
                && PassableFoliage.isPassable(blockState);
    }

    /** Exact condition for {@code BlockStateMixin.pfoliage_isViewBlocking}. */
    public static boolean shouldCancelViewBlocking(BlockStateBase state) {
        return PassableFoliageCommonConfig.alwaysNotViewBlocking && isPassableState(state);
    }

    /** Exact replacement for {@code BlockStateMixin.pfoliage_getShadeBrightness} when {@link #isPassableState} is true. */
    public static float evalPassableShadeBrightness(BlockStateBase state, BlockGetter level, BlockPos pos) {
        BlockState blockState = (BlockState) state;
        PassableFoliage.setSuppressPassableCheck(true);
        try {
            return blockState.m_60734_().m_180643_(blockState, level, pos) ? 0.2F : 1.0F;
        } finally {
            PassableFoliage.setSuppressPassableCheck(false);
        }
    }
}
