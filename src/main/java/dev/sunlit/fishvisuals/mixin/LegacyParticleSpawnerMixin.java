package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import pigcart.particlerain.ParticleLoader;
import pigcart.particlerain.ParticleSpawner;
import pigcart.particlerain.VersionUtil;
import pigcart.particlerain.config.ConfigData;
import pigcart.particlerain.config.ConfigManager;
import pigcart.particlerain.config.ParticleData;
import pigcart.particlerain.particle.StreakParticle;
import java.util.ArrayList;
@Pseudo
@Mixin(targets="pigcart.particlerain.ParticleSpawner",remap=false)
public abstract class LegacyParticleSpawnerMixin {
    @Shadow @Final private static BlockPos.MutableBlockPos pos;
    @Overwrite(remap = false)
    public static int calculateHeight(ClientLevel level, int x, int z) {
        int y = level.m_6924_(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (y == 0 || y == -1) {
            y = 255;
        }
        BlockPos.MutableBlockPos cursor = ParticleRainClientPerf.scratch().cursor;
        cursor.m_122178_(x, y, z);
        int minY = -64;
        while (y > minY) {
            BlockState state = level.m_8055_(cursor);
            boolean emptyFluid = state.m_60819_().m_76178_();
            if (!emptyFluid || (!state.m_60812_(level, pos).m_83281_() && !ParticleSpawner.isIgnored(state))) {
                break;
            }
            y--;
            cursor.m_142448_(y);
        }
        return y + 1;
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="tickBlockFX")
    private static void sunlit$blockScope(BlockPos.MutableBlockPos origin,BlockState state,RandomSource random,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try(var scope=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()){original.call(origin,state,random);}
    }
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="tickSkyFX")
    private static void sunlit$skyScope(ClientLevel level,Vec3 pos,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try(var scope=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()){original.call(level,pos);}
    }
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="tickSurfaceFX")
    private static void sunlit$surfaceScope(ClientLevel level,Vec3 pos,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try(var scope=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()){original.call(level,pos);}
    }
    @org.spongepowered.asm.mixin.injection.Redirect(method={"tickBlockFX","tickSkyFX","tickSurfaceFX"},at=@org.spongepowered.asm.mixin.injection.At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;m_204166_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),require=1)
    private static Holder<Biome> sunlit$reuseColumnBiome(ClientLevel level,BlockPos pos) {
        return dev.sunlit.bettersunlit.client.LegacyBiomeCache.get(level,pos);
    }
}
