package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.BackpackShapePerf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.RegistryObject;
import net.p3pp3rf1y.sophisticatedbackpacks.SophisticatedBackpacks;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock;
import net.p3pp3rf1y.sophisticatedbackpacks.client.render.ClientBackpackShapeProvider;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deduplicates {@code ClientBackpackShapeProvider.rebuildShapes()} lookups across properties that
 * do not affect baked backpack geometry ({@code OPEN}, {@code WATERLOGGED}). Canonical shapes are
 * computed on the caller so lazy model loading retains Forge's expected class-loader context.
 */
@Mixin(value = ClientBackpackShapeProvider.class, remap = false)
public abstract class PerfBackpackShapeMixin {
    @Shadow
    private volatile Map<BlockState, VoxelShape> shapeCache;

    @Shadow
    private volatile boolean warnedRendererMissing;

    @Invoker("computeShapeFromLoadedModel")
    protected abstract VoxelShape sunlit$computeShapeFromLoadedModel(Minecraft mc, BlockState state);

    @Inject(method = "rebuildShapes", at = @At("HEAD"), cancellable = true)
    private void sunlit$rebuildDeduplicatedShapes(CallbackInfo ci) {
        ci.cancel();
        Minecraft mc = Minecraft.m_91087_();
        if (mc.m_91289_() == null) {
            if (!this.warnedRendererMissing) {
                this.warnedRendererMissing = true;
                SophisticatedBackpacks.LOGGER.warn(
                        "Backpack block renderer is not ready yet, using base model-derived shapes until client models are available");
            }
            this.shapeCache = Map.of();
            return;
        }
        this.warnedRendererMissing = false;
        List<RegistryObject<BackpackBlock>> blocks = List.of(
                ModBlocks.BACKPACK,
                ModBlocks.COPPER_BACKPACK,
                ModBlocks.IRON_BACKPACK,
                ModBlocks.GOLD_BACKPACK,
                ModBlocks.DIAMOND_BACKPACK,
                ModBlocks.NETHERITE_BACKPACK);
        List<BlockState> allStates = new ArrayList<>(768);
        for (RegistryObject<BackpackBlock> supplier : blocks) {
            Block block = supplier.get();
            allStates.addAll(block.m_49965_().m_61056_());
        }
        this.shapeCache = BackpackShapePerf.buildDeduplicatedShapes(
                allStates,
                PerfBackpackShapeMixin::sunlit$canonicalizeState,
                canonical -> this.sunlit$computeShapeFromLoadedModel(mc, canonical));
    }

    private static BlockState sunlit$canonicalizeState(BlockState state) {
        BlockState canonical = state;
        if (canonical.m_61138_(BackpackBlock.OPEN)) {
            canonical = canonical.m_61124_(BackpackBlock.OPEN, Boolean.FALSE);
        }
        if (canonical.m_61138_(BlockStateProperties.f_61362_)) {
            canonical = canonical.m_61124_(BlockStateProperties.f_61362_, Boolean.FALSE);
        }
        return canonical;
    }
}
