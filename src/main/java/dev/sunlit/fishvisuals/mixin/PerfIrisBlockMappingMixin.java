package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.PropertyNameCache;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping", remap = false)
public abstract class PerfIrisBlockMappingMixin {
    @Redirect(method = "createBlockStateIdMap", at = @At(value = "NEW",
            target = "it/unimi/dsi/fastutil/objects/Object2IntOpenHashMap"), require = 1)
    private static Object2IntOpenHashMap<BlockState> sunlit$sizeMaterialMap() {
        // A bounded initial table avoids repeated rehashes without retaining another state cache.
        return new Object2IntOpenHashMap<>(131072);
    }
    @Redirect(method = "checkState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/properties/Property;m_6940_(Ljava/lang/Comparable;)Ljava/lang/String;"), require = 1)
    private static <T extends Comparable<T>> String sunlit$reuseIntegerNames(Property<T> property, T value) {
        // Custom properties keep their own naming logic. IntegerProperty's exact vanilla
        // implementation is Integer.toString; retain its result for the common small values.
        if (property.getClass() == IntegerProperty.class && value instanceof Integer number) {
            return PropertyNameCache.integerName(number);
        }
        return property.m_6940_(value);
    }
}
