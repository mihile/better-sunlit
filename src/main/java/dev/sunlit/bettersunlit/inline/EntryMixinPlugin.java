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
        Map.entry("team.creative.ambientsounds.environment.BiomeEnvironment$BiomeArea","7b65c18cc21181edc85049855cc918497db466ba3115e0ec9896f7a879e7aa16"),
        Map.entry("net.irisshaders.iris.Iris","f608dc588a5e4777c6d51c8f3f3d1ac1c60356dfca2565df1a1e26190fe278af"),
        Map.entry("pigcart.particlerain.ParticleRain","93fcfbc14cb40d37ab95120aa1aa81a61abb000dfde524388ad06fc76c8a4867"),
        Map.entry("pigcart.particlerain.WindManager","a34806f8de69a5fe985ec0cedd162b5397d956631e185bb4f00587e5c6ff6f1c"),
        Map.entry("team.creative.ambientsounds.environment.pocket.AirPocketScanner","4a58a866bcbfffe54bb8a6b6db04dcfaf5052ee796cc705d6eea1329e23c4787"),
        Map.entry("mezz.jei.common.config.sorting.SortingConfig","b0f856dc16f6af095200ec8c3ce79aa44045ab730b35c81a1dc403669f11b171"),
        Map.entry("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase","00895a1c899f4cd5437a5602557ff00a60a26c34e6956a110ebdeb6898b75b12"),
        Map.entry("com.copycatsplus.copycats.mixin.foundation.copycat.BlockStateBaseMixin","9e9af4d259522c7ba274007665956046971cd2013edfd618769c6b357a4facca"),
        Map.entry("snownee.passablefoliage.mixin.BlockStateMixin","2f9c42959a1181ea080c550109e0d4676dc4011a66d3e90e36221c5346c4ffc6"),
        Map.entry("snownee.passablefoliage.PassableFoliage","095b2c7263bd79e75c65726fef2a0572f4f1dd515dc29611e1350e0ed96c281c"),
        Map.entry("net.minecraft.client.renderer.block.BlockModelShaper","d7cc3d4652e1d1273763c9ab6e8636a871950f7af735e65c5d8dd57b4c9a8851"),
        Map.entry("fr.madu59.obe.client.mixin.BlockStateModelSetMixin","79e9f000cc50b3c160f39377396b2e80d647599cc6d0b2fdc4019eab07a53880"),
        Map.entry("net.minecraft.client.model.geom.ModelPart$Cube","d81dc7b3eb4bf2a17aa1c000f0a3a3181b0b4016d37bc21dd6db6fb11444a6f6"),
        Map.entry("traben.entity_model_features.models.parts.EMFModelPart","25ddbe5b9a9d277fc02744752f6d4abacaa48041ec4d26989dde4e47900939ed"),
        Map.entry("traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube","f2877a9bc5c18e512f76907ca821fec0e4ae28ce5c734a620b9be1ef5f2924f8"),
        Map.entry("traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser","76f852b5f05ff5636b4351a01b7ffffe1987b10a8ee18dbb53eb70f1ca792137"),
        Map.entry("com.mojang.blaze3d.vertex.PoseStack","024fb38d5ededca7a9ff55685fd96a6126a8cd7b6419e35af1caede9b1f0d800"),
        Map.entry("org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack","a11e33f91d79da3ec0f13a6b62cc9127d7afe3545376e05f60074bf85c86c432"),
        Map.entry("net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager","090aa4ca8e7c9f764f5c1c810c66dc067b6d747f09cfe44817566d2dc0d98be6"),
        Map.entry("de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider","39ae004f99785e45c160a91876f911da948409046f1924cfb704b23f9a20f197"),
        Map.entry("mod.azure.logbegone.LogBegoneMod","0de2bc1570884e7384cbf0ab11adf9fd3ec015d6f7fd3a65716324d8c253571a"),
        Map.entry("mod.azure.logbegone.LogBegoneConfig","412a4582a86a7e7653f5860dd3b1383b68d9f099a28b3e98bb313edbd2ac8fba"),
        Map.entry("mod.azure.logbegone.LogBegoneConfig$Common","d04a3f2103de3a55884e8e34f71f88c13a017f94eeb75179fab0a0e30e038d3b"),
        Map.entry("com.hrznstudio.emojiful.render.EmojiFontRenderer","084b981f62da170bb98de87ac32bb793f033c4661f6da4cbb9a217a3e1d9821a"),
        Map.entry("com.hrznstudio.emojiful.Constants","b8d9e018e6785a7661ca65b939061fa9c4d53b6c6ec7c856d136416033b6db96"),
        Map.entry("com.hrznstudio.emojiful.api.Emoji","6a975922504b9399122de7b8f4ec71945f0677576dc4faa750f6ae3f743e7ecf"),
        Map.entry("net.irisshaders.iris.pipeline.PipelineManager","f10f218536e5d96d23f049198728d0ffe3cb44fca000a862fdb66b77b9cb00b2"),
        Map.entry("io.github.douira.glsl_transformer.ast.transform.ASTParser","c01dca22a87a5f8be8082e3aa0af7417f5b2b7ab37a307425f31536fefd399f9"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher","352f0f24560a406769384c492e05ca43ff679959ec8b73d0bfcc4302d5469c58"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher$2","1adb3ae6c90b0262f72d23d43526c618eb20cf2ac548ce10826290b82439e74a"),
        Map.entry("io.github.douira.glsl_transformer.parser.CachingParser","6297e0a4b12a47b3914696cf32c6700dcf7d7dadf24e1f846c0901c449464ef6"),
        Map.entry("io.github.douira.glsl_transformer.ast.data.TypedTreeCache","5dbfbc8ef8c082cc70187167f96de72f1e8077867ae2a16515d6843898956593"),
        Map.entry("mezz.jei.forge.platform.RegistryWrapper","ea5c1c04c8d50b45f2498f9dc1c66264f3d530f6c52e0a3b14d14eb936f84f93"));
    private static final Map<String,String> ALT_HASHES=Map.ofEntries(
        Map.entry("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase","2d0e63e450caead1710a462bc66ce913083a8c22e25be23e4e1cdaddb5d73e12"),
        Map.entry("net.minecraft.client.renderer.block.BlockModelShaper","2f56551223f6b64b195d9cb898877019cc7ca084aaa83a5eca60381cbd80936d"),
        Map.entry("com.mojang.blaze3d.vertex.PoseStack","eab986aad817df3afd87407a3d45fe82e9a5602a275926dc01ac67b774e00e8b"));
    private final Map<String,Boolean> checked=new HashMap<>();
    public boolean matches(String name) {
        return checked.computeIfAbsent(name,n->{
            String expected=HASHES.get(n);
            if(expected==null)return false;
            try(var in=EntryMixinPlugin.class.getClassLoader().getResourceAsStream(n.replace('.','/')+".class")) {
                if(in==null)return false;
                String actual=InlineBytecode.sha256(in.readAllBytes());
                return actual.equals(expected)||actual.equals(ALT_HASHES.get(n));
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
                 "PerfSeasonColorUtilMixin", "PerfAmbientBiomeAreaMixin",
                 "PerfAmbientAirPocketScannerMixin", "PerfIrisDimensionMixin",
                 "PerfJeiSortingConfigMixin", "PerfEmfMathParserMixin",
                 "PerfJeiRegistryWrapperMixin" -> matches(target);
            case "PerfBlockOcclusionMixin" ->
                 matches(target)&&matches("com.copycatsplus.copycats.mixin.foundation.copycat.BlockStateBaseMixin")
                 &&matches("snownee.passablefoliage.mixin.BlockStateMixin")&&matches("snownee.passablefoliage.PassableFoliage");
            case "PerfObeModelMixin" ->
                 matches(target)&&matches("fr.madu59.obe.client.mixin.BlockStateModelSetMixin");
            case "PerfModelPartCubeMixin" ->
                 matches(target)&&matches("com.mojang.blaze3d.vertex.PoseStack");
            case "PerfEmfCubeMixin" ->
                 matches(target)&&matches("net.minecraft.client.model.geom.ModelPart$Cube")
                 &&matches("com.mojang.blaze3d.vertex.PoseStack");
            case "PerfEmfModelPartMixin" ->
                 matches(target)&&matches("com.mojang.blaze3d.vertex.PoseStack")
                 &&matches("org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack");
            case "PerfGraphOrderMixin" ->
                 matches(target)&&matches("de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider");
            case "PerfLogBegoneMixin" ->
                 matches(target)&&matches("mod.azure.logbegone.LogBegoneConfig")
                 &&matches("mod.azure.logbegone.LogBegoneConfig$Common");
            case "PerfEmojiFontRendererMixin" ->
                 matches(target)&&matches("com.hrznstudio.emojiful.Constants")
                 &&matches("com.hrznstudio.emojiful.api.Emoji");
            case "PerfPipelineManagerMixin", "PerfTransformPatcherMixin" ->
                 matches(target)&&matches("io.github.douira.glsl_transformer.ast.transform.ASTParser")
                 &&matches("net.irisshaders.iris.pipeline.transform.TransformPatcher")
                 &&matches("net.irisshaders.iris.pipeline.transform.TransformPatcher$2")
                 &&matches("io.github.douira.glsl_transformer.parser.CachingParser")
                 &&matches("io.github.douira.glsl_transformer.ast.data.TypedTreeCache");
            case "PerfWindManagerMixin" ->
                 matches(target)&&matches("pigcart.particlerain.ParticleRain");
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
    @Override public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){
        if(targetClass==null||mixinClassName==null)return;
        String shortName=mixinClassName.substring(mixinClassName.lastIndexOf('.')+1);
        switch(shortName){
            case "PerfBlockOcclusionMixin"->EntryPostApplyPatches.patchBlockOcclusion(targetClass);
            case "PerfObeModelMixin"->applyClientPatch("patchBlockModelShaper",targetClass);
            case "PerfModelPartCubeMixin"->applyClientPatch("patchModelPartCube",targetClass);
            case "PerfEmfMathParserMixin"->applyClientPatch("patchMathExpressionParser",targetClass);
            case "PerfEmojiFontRendererMixin"->applyClientPatch("patchEmojiFontRenderer",targetClass);
            default->{}
        }
    }
    private static void applyClientPatch(String method,ClassNode targetClass){
        try{
            Class.forName("dev.sunlit.bettersunlit.client.ClientPostApplyPatches")
                    .getMethod(method,ClassNode.class)
                    .invoke(null,targetClass);
        }catch(ReflectiveOperationException e){
            throw new RuntimeException("Failed applying client postApply patch "+method,e);
        }
    }
}
