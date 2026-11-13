package dev.sunlit.fishvisuals.mixin;

import com.supermartijn642.core.registry.Registries;
import dev.sunlit.bettersunlit.client.EntryCreativeCandidates;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.Collection;

@Pseudo
@Mixin(targets="com.supermartijn642.core.CoreLib",remap=false)
public abstract class EntryCoreLibCandidatesMixin {
    @Redirect(method="lambda$new$3(Lnet/minecraftforge/event/BuildCreativeModeTabContentsEvent;)V",at=@At(value="INVOKE",target="Lcom/supermartijn642/core/registry/Registries$Registry;getValues()Ljava/util/Collection;"),require=1,remap=false)
    private static Collection<Item> sunlit$indexedCandidates(Registries.Registry<Item> registry){return EntryCreativeCandidates.get(registry);}
}
