package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.IdMapperPerf;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.IForgeRegistryInternal;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pre-sizes {@code GameData.BLOCKSTATE_TO_ID} ({@link IdMapper}) before Forge iterates all blocks
 * and states in {@code BlockCallbacks.onBake}, eliminating repeated {@code Object2IntOpenCustomHashMap}
 * rehashes during startup registry baking.
 */
@Mixin(targets = "net.minecraftforge.registries.GameData$BlockCallbacks", remap = false)
public abstract class PerfGameDataBlockCallbacksMixin {
    @Inject(method = "onBake", at = @At("HEAD"))
    private void sunlit$presizeBlockStateIdMapper(
            IForgeRegistryInternal<Block> owner,
            RegistryManager stage,
            CallbackInfo ci) {
        if (owner == null) {
            return;
        }
        Object slave = owner.getSlaveMap(IdMapperPerf.BLOCKSTATE_TO_ID, IdMapper.class);
        if (!(slave instanceof IdMapper<?> mapper)) {
            return;
        }
        int totalStates = 0;
        for (Block block : owner) {
            if (block != null) {
                totalStates += block.m_49965_().m_61056_().size();
            }
        }
        @SuppressWarnings("unchecked")
        PerfIdMapperAccessor<BlockState> accessor = (PerfIdMapperAccessor<BlockState>) mapper;
        Object2IntMap<BlockState> current = accessor.sunlit$getMap();
        Object2IntMap<BlockState> sized = IdMapperPerf.presizeMap(current, accessor.sunlit$getList(), totalStates);
        if (sized != current) {
            accessor.sunlit$setMap(sized);
        }
    }
}
