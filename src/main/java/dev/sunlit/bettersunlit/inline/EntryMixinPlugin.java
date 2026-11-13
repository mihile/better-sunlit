package dev.sunlit.bettersunlit.inline;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Optional patches for reviewed binaries only; never initializes target classes. */
public final class EntryMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("mezz.jei.library.plugins.vanilla.VanillaPlugin","81a738a41333fb2eee326522493085fcea843546193c7c580eb60d77e6afc8ac"),
        Map.entry("mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory","50b897b9b97845492dc76da225f5ed365a81fd20f9a04cb1f98864f0ef83c5d0"),
        Map.entry("mezz.jei.library.ingredients.itemStacks.TypedItemStack","d24191eb32f7f9b163fc08e2fbf26969b4dd8d357d5c2d756788b2c044b071cb"),
        Map.entry("com.supermartijn642.core.CoreLib","88dd77f74331ff8f8af1c05710baaa34595c09019bb2c10c21122a8b9d10b347"),
        Map.entry("com.cicada.longwings.entity.ButterflyVariant","76048dd78f8818e1242da0c66826a630f43206b11fdae005b8dcbdd45a43dffb"),
        Map.entry("com.cicada.longwings.entity.ButterflyEntity","54b661838e654952899a104c24bf45a7ff1e48fc22db44436b34cb6ea5b7b627"),
        Map.entry("com.cicada.longwings.client.renderers.ButterflyEntityRenderer","0f230949b3fd30b096b80259f5e0c6c2aa316899f7bde170af1a48545d932ad0"),
        Map.entry("com.cicada.longwings.client.renderers.ButterflyEntityRenderer$MothEntityRenderer","ef95bfe79d8920fab73cdd42098d8eff78dbba706a5aafaa4249544c0f873a90"),
        Map.entry("com.cicada.longwings.client.model.ButterflyModel","072e7bd4310e3d6ab706e0e62ee0e20e252a270051c783fb96801dd85e7aa3dd"),
        Map.entry("com.cicada.longwings.client.renderers.ButterflyItemRenderer","ef6664d48db4b286667f2895e810a11b6003fa6d9228e4ffc000712d0dc33fe9"),
        Map.entry("com.cicada.longwings.client.renderers.ButterflyItemRenderer$MothItemRenderer","81ccad6b7a7c766c704d09c3e04437a7a74b1f712314108e280013823883c441"),
        Map.entry("com.cicada.longwings.client.gui.HandbookScreen","2db49e8e0a64e5975ddb297d72367204a0527f62c6a1b4646e8d853bf61a8c06"),
        Map.entry("com.cicada.longwings.client.gui.HandbookScreen$ButterflyIndexButton","44f7b4b11e8c68613165d8954df70b38fe57517301e77b558d8522b51500eb3c"),
        Map.entry("com.cicada.longwings.client.gui.HandbookToast","220574266d295c8712b10a8cea0a6479b76be78e844d08703ac636c9e46bd0f2"),
        Map.entry("pigcart.particlerain.ParticleSpawner","69234eaa364e6f25f8d59ba9c79e36a3fa7810e8aa96e4464e32bc289137c368"),
        Map.entry("pigcart.particlerain.particle.WeatherParticle","7ae0e44e190e243bb5a38d5ee3c9123283ab3b574539712879f65abbef00295c"),
        Map.entry("pigcart.particlerain.particle.CustomParticle","fb6ee3bf0bceed665a23a44663253484c6eb796f5882b80fa1f7fcc0e5904edd"),
        Map.entry("sereneseasons.config.SeasonsConfig","f1fedb93a81be6b615519308994a395f58d657aab9af0062d4dba8a23c7c6ee8"),
        Map.entry("sereneseasons.season.SeasonHandler","a0b7c38da53d7ad77a03dfdde20f84e2c4137afd2719f783f00afead6fec6d91"),
        Map.entry("sereneseasons.season.SeasonHooks","8490b38f943761329edbd05d6bd2a442b96b8e70bb55dab5e024f0c36db6582f"),
        Map.entry("sereneseasons.season.SeasonColorHandlers","c9e3c63e3f59b1fee2accacd315202799920f703397b00fad9e1af282c61ea1a"),
        Map.entry("sereneseasons.util.SeasonColorUtil","6947644acf68df0def5f1bb93818120d50dfe84410503e37dfb73a18b24812cc"),
        Map.entry("team.creative.ambientsounds.environment.BiomeEnvironment$BiomeArea","7b65c18cc21181edc85049855cc918497db466ba3115e0ec9896f7a879e7aa16"));
    private final Map<String,Boolean> checked=new HashMap<>();
    public boolean matches(String name) {
        return checked.computeIfAbsent(name,n->{
            try(var in=EntryMixinPlugin.class.getClassLoader().getResourceAsStream(n.replace('.','/')+".class")) {
                return in!=null&&InlineBytecode.sha256(in.readAllBytes()).equals(HASHES.get(n));
            }catch(java.io.IOException e){return false;}
        });
    }
    @Override public boolean shouldApplyMixin(String target,String mixin) {
        var tracker=MixinService.getService().getClassTracker();
        if(tracker!=null&&tracker.isClassLoaded(target))return false;
        return canApplyGate(target,mixin);
    }
    public boolean canApplyGate(String target,String mixin) {
        String shortName=mixin.substring(mixin.lastIndexOf('.')+1);
        return switch(shortName) {
            case "EntryJeiTypedCacheMixin", "PerfButterflyVariantMixin", "PerfButterflyEntityMixin",
                 "PerfButterflyModelMixin", "PerfButterflyItemRendererMixin", "PerfMothItemRendererMixin",
                 "PerfHandbookScreenMixin", "PerfHandbookIndexButtonMixin", "PerfHandbookToastMixin",
                 "PerfParticleSpawnerMixin", "PerfWeatherParticleMixin", "PerfSeasonsConfigMixin",
                 "PerfSeasonHandlerMixin", "PerfSeasonHandlerClientMixin", "PerfSeasonHooksMixin",
                 "PerfAmbientBiomeAreaMixin" -> matches(target);
            case "EntryJeiIngredientScopeMixin", "EntryCoreLibCandidatesMixin" ->
                 matches("com.supermartijn642.core.CoreLib")&&matches("mezz.jei.library.plugins.vanilla.VanillaPlugin")&&matches("mezz.jei.library.plugins.vanilla.ingredients.ItemStackListFactory");
            case "PerfButterflyRendererMixin", "PerfMothRendererMixin" ->
                 matches(target)&&matches("com.cicada.longwings.entity.ButterflyEntity");
            case "PerfCustomParticleMixin" ->
                 matches(target)&&matches("pigcart.particlerain.particle.WeatherParticle");
            case "PerfSeasonColorHandlersMixin" ->
                 matches(target)&&matches("sereneseasons.util.SeasonColorUtil")&&matches("sereneseasons.season.SeasonHandler");
            default -> false;
        };
    }
    @Override public void onLoad(String p){}
    @Override public String getRefMapperConfig(){return null;}
    @Override public void acceptTargets(Set<String> a,Set<String>b){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String n,ClassNode c,String m,IMixinInfo i){}
    @Override public void postApply(String n,ClassNode c,String m,IMixinInfo i){}
}
