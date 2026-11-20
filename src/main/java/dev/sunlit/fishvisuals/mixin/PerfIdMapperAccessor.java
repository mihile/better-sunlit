package dev.sunlit.fishvisuals.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = IdMapper.class, remap = false)
public interface PerfIdMapperAccessor<T> {
    @Accessor("f_122654_")
    Object2IntMap<T> sunlit$getMap();

    @Mutable
    @Accessor("f_122654_")
    void sunlit$setMap(Object2IntMap<T> map);

    @Accessor("f_122655_")
    List<T> sunlit$getList();
}
