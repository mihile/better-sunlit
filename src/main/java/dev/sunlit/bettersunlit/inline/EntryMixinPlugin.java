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
        Map.entry("de.odysseus.ithaka.digraph.util.fas.AbstractFeedbackArcSetProvider","29a38832590b045249aac9ba183ff74f766e3e238f75fa15a25350c32db5fcc9"),
        Map.entry("de.odysseus.ithaka.digraph.Digraphs","1edfec18f5f01807ed3edbf0f083da6c8ee9127d47aeea51615fca8f791ef8e5"),
        Map.entry("mod.azure.logbegone.LogBegoneMod","0de2bc1570884e7384cbf0ab11adf9fd3ec015d6f7fd3a65716324d8c253571a"),
        Map.entry("mod.azure.logbegone.LogBegoneConfig","412a4582a86a7e7653f5860dd3b1383b68d9f099a28b3e98bb313edbd2ac8fba"),
        Map.entry("mod.azure.logbegone.LogBegoneConfig$Common","d04a3f2103de3a55884e8e34f71f88c13a017f94eeb75179fab0a0e30e038d3b"),
        Map.entry("com.hrznstudio.emojiful.render.EmojiFontRenderer","084b981f62da170bb98de87ac32bb793f033c4661f6da4cbb9a217a3e1d9821a"),
        Map.entry("com.hrznstudio.emojiful.Constants","b8d9e018e6785a7661ca65b939061fa9c4d53b6c6ec7c856d136416033b6db96"),
        Map.entry("com.hrznstudio.emojiful.api.Emoji","6a975922504b9399122de7b8f4ec71945f0677576dc4faa750f6ae3f743e7ecf"),
        Map.entry("com.hrznstudio.emojiful.api.EmojiFromTwitmoji","ab223def54834a544d112b52d98a473dac174c9c676d5bf80effc694de7b91ca"),
        Map.entry("com.hrznstudio.emojiful.api.EmojiFromGithub","c14ad29baaeee0e03ba3f8302b80d878cfe2e7a56a1d84b19dd4acc7f2761d3b"),
        Map.entry("com.hrznstudio.emojiful.api.EmojiFromEmojipedia","b5dbfe003b95703a0918d607e65f5b85d60f7a3bb43d2549f7bbb3e11c344502"),
        Map.entry("snownee.passablefoliage.PassableFoliageCommonConfig","3ab9ddf8c034ded8c18c2c9e88182c7b3166c127447517500d9721089625c420"),
        Map.entry("snownee.passablefoliage.PassableFoliageBlock","b80c9f06d7b30ca6b6983b97a37936db481acd4892191350f4940bb5d8e83109"),
        Map.entry("net.irisshaders.iris.pipeline.PipelineManager","f10f218536e5d96d23f049198728d0ffe3cb44fca000a862fdb66b77b9cb00b2"),
        Map.entry("io.github.douira.glsl_transformer.ast.transform.ASTParser","c01dca22a87a5f8be8082e3aa0af7417f5b2b7ab37a307425f31536fefd399f9"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher","352f0f24560a406769384c492e05ca43ff679959ec8b73d0bfcc4302d5469c58"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher$2","1adb3ae6c90b0262f72d23d43526c618eb20cf2ac548ce10826290b82439e74a"),
        Map.entry("io.github.douira.glsl_transformer.parser.CachingParser","6297e0a4b12a47b3914696cf32c6700dcf7d7dadf24e1f846c0901c449464ef6"),
        Map.entry("io.github.douira.glsl_transformer.ast.data.TypedTreeCache","5dbfbc8ef8c082cc70187167f96de72f1e8077867ae2a16515d6843898956593"),
        Map.entry("mezz.jei.forge.platform.RegistryWrapper","ea5c1c04c8d50b45f2498f9dc1c66264f3d530f6c52e0a3b14d14eb936f84f93"),
        Map.entry("net.raphimc.immediatelyfast.feature.core.BatchableBufferSource","43448e373a68f3c2d74861dc74d2ee651c0187786d1c311f5bce36e59fd07510"),
        Map.entry("net.raphimc.immediatelyfast.compat.IrisCompat","ccb86c53b9c33c9444d60d1d95d0108c0b6b47158c00d0f34d7bcf55d634c46f"),
        Map.entry("net.raphimc.immediatelyfast.feature.core.BufferBuilderPool","9b2dc83205cf9c91a54fe1e62f3a4cb2c9aa14611d380b835f7aa70ca8ed070e"),
        Map.entry("traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory","11f616e07ad2b295994d86eb58f777252ade02d0ec27b6b3422f1d1054b83a38"),
        Map.entry("me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask","d99368baf1bc732371b9794754ddbb6a6c294166ecf422939b599230ee431743"),
        Map.entry("fr.madu59.obe.client.mixin.renderer.compat.sodium.ChunkBuilderMeshingTaskMixin","3ca9e9cba21051a4875a620f415ef445224151128bf260371b4c91002f5fc864"),
        Map.entry("fr.madu59.obe.client.util.meshing.SectionMeshingUtil","d8266f95151a83ffbb555b441c25eb9713dc5ef41b95d6f935642cc2893cf075"),
        Map.entry("team.creative.ambientsounds.block.AmbientBlockGroup","28c49c702455acd8d2332f21b180c75694071e670181d47595d6304d216925c7"),
        Map.entry("team.creative.ambientsounds.block.AmbientBlock","79d58b21129e5e9c3e7ae2eed93d3edf4f023eaf844570d88953d025d66c170a"),
        Map.entry("team.creative.ambientsounds.AmbientSounds","c8bffaa868120515297ca01b7ad2e13c21b008d5c5b58acc9418c74dab0e8659"),
        Map.entry("team.creative.ambientsounds.AmbientSoundsConfig","3e91ae8963d94849ec8105b7fb019529f2efeb02555a7bbee7dc3106204b1d3c"),
        Map.entry("team.creative.ambientsounds.engine.AmbientEngine","839271eb300dc0e03c962cda3ded4a2e1d302acb9c98f27b70f7d57629a3d693"),
        Map.entry("team.creative.ambientsounds.environment.pocket.AirPocket","057e437c44aa419ca7cabec0516f21d20f75360043ee9e6c1c605fa4715a9bc8"),
        Map.entry("team.creative.ambientsounds.environment.pocket.BlockDistribution","81d65b3c029896a6eb0da1daeb2a2542bdb3ac64a38b49236e178751c8fbf39e"),
        Map.entry("team.creative.creativecore.common.util.type.set.QuadBitSet","176b0a887099378c6e013b487b3d37838c4f4d1b8df0613f07417d95cf36178b"),
        Map.entry("team.creative.creativecore.common.util.type.map.HashMapDouble","be8bffb55c5993c1f252aa3d2c6b569eba7bcd5114d59881044877a0bd7fdfc3"),
        Map.entry("traben.entity_model_features.utils.EMFLODHandler","87807d7124575e5f56fb8f47d508911b1616becdaf3cff2960e3e87574f895b7"),
        Map.entry("traben.entity_model_features.config.EMFConfig","5d707a2f263ce7e05d84b551b78cef82c11004b32cf1b093f7007ba8ffb76348"),
        Map.entry("traben.entity_model_features.models.animation.state.EMFState","0c8474303d0943138348fc0df43ad84eb6dcda28757c151b6d7752e5bfa3d5cb"),
        Map.entry("traben.entity_model_features.models.animation.state.EMFEntityRenderState","472c455b0b42a2688c2c6ef34d3631d2b94eb7a744949db1fcf50d0194352e6e"),
        Map.entry("traben.entity_model_features.mod_compat.IrisShadowPassDetection","582755901c9491f1d579a6557de68cb1404fb99db18ad4c0e492e8948a30591a"),
        Map.entry("traben.entity_model_features.utils.UEntityTypes","6ea6d05243fb50d83645652dbcb96d1193ea36d0e73ea7e189ce9e91304cc6a8"),
        Map.entry("net.minecraft.client.renderer.texture.TextureManager","07aeee2ffbbe9a35c95cd55a6ddf6e4333fdd5df6a5ef8ae531a187b2b4ae916"),
        Map.entry("net.minecraft.client.renderer.LevelRenderer","81877d130e2b45bf04c694939c52001dbf67bb8ad5d742ba5fe8f0af4fd13442"),
        Map.entry("pigcart.particlerain.config.ConfigManager","a6222b0e56aca602544e182429d42d6c6fee6d1ebdf04eb50afa2771843030f7"),
        Map.entry("pigcart.particlerain.config.ConfigData","fb33da89e89cc71f8fdac21842f8ac2f40bdbcf7429311218b9d865dd0d71962"),
        Map.entry("pigcart.particlerain.config.ConfigData$CompatibilityOptions","fec0dffe10077618120971d79eb243c5b4e8d6e72daeebc6278ad85f914ba71c"),
        Map.entry("pigcart.particlerain.mixin.render.WeatherEffectRendererMixin","ff86e4add6ef81b4c316c2f367b0bd4bb15157b2a39b238f3664051b19aa01db"),
        Map.entry("pigcart.particlerain.config.Whitelist","7db9605abd46d1779d114f2e3efc64efd460f02349be45880329dc82bb6b6d8c"),
        Map.entry("net.minecraft.world.level.chunk.SingleValuePalette","0809b73a849fc3053a12dfe7080989ead6838e35d2b6089d746f367fe6ddc679"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer","80800643eb90559e9d33c154bc91f5c3b873ede6f62d3ea8fd1fed8f06033935"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer$Data","b9e12f1cbe09d5944a789c715028d542e0e23cb9489d8eecc23ce1a8217cf771"),
        Map.entry("net.minecraft.world.level.chunk.Palette","541587aa197255575e66f4849e5fdfcc798434ff60be80a18c2807bb51ba95c4"));
    private static final Map<String,String> ALT_HASHES=Map.ofEntries(
        Map.entry("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase","2d0e63e450caead1710a462bc66ce913083a8c22e25be23e4e1cdaddb5d73e12"),
        Map.entry("net.minecraft.client.renderer.block.BlockModelShaper","2f56551223f6b64b195d9cb898877019cc7ca084aaa83a5eca60381cbd80936d"),
        Map.entry("com.mojang.blaze3d.vertex.PoseStack","eab986aad817df3afd87407a3d45fe82e9a5602a275926dc01ac67b774e00e8b"),
        Map.entry("net.minecraft.client.renderer.texture.TextureManager","f3d654e4d0f5628d8d7350b6f6c890f6cff9075f3431cc0806e115b80d73df01"),
        Map.entry("net.minecraft.client.renderer.LevelRenderer","c1603c5a96efece8736bcfc9842962ce5ffb3580331d0baf4d1e0f9984fecb3b"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer","7c0f3481565606795266222bfbb26cea6e925ee55b8704eb3c81a3a96a046088"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer$Data","87e522635c87f7cedfbe1d8a2090ffaf95cd9fe5da55e315ec031eecf8aee966"));
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
                 "PerfIrisDimensionMixin",
                 "PerfJeiSortingConfigMixin", "PerfEmfMathParserMixin",
                 "PerfJeiRegistryWrapperMixin", "PerfEmfModelPartVariableMixin",
                 "PerfTextureManagerMixin", "PerfWhitelistMixin",
                 "PerfSingleValuePaletteMixin" -> matches(target);
            case "PerfPalettedContainerMixin" ->
                 matches(target)&&matches("net.minecraft.world.level.chunk.PalettedContainer$Data")
                 &&matches("net.minecraft.world.level.chunk.SingleValuePalette")
                 &&matches("net.minecraft.world.level.chunk.Palette");
            case "PerfAmbientBlockGroupMixin" ->
                 matches(target)&&matches("team.creative.ambientsounds.block.AmbientBlock");
            case "PerfAmbientAirPocketScannerMixin" ->
                 matches(target)&&matches("team.creative.ambientsounds.block.AmbientBlockGroup")
                 &&matches("team.creative.ambientsounds.block.AmbientBlock")
                 &&matches("team.creative.ambientsounds.AmbientSounds")
                 &&matches("team.creative.ambientsounds.AmbientSoundsConfig")
                 &&matches("team.creative.ambientsounds.engine.AmbientEngine")
                 &&matches("team.creative.ambientsounds.environment.pocket.AirPocket")
                 &&matches("team.creative.ambientsounds.environment.pocket.BlockDistribution")
                 &&matches("team.creative.creativecore.common.util.type.set.QuadBitSet")
                 &&matches("team.creative.creativecore.common.util.type.map.HashMapDouble");
            case "PerfEmfLodHandlerMixin" ->
                 matches(target)&&matches("traben.entity_model_features.config.EMFConfig")
                 &&matches("traben.entity_model_features.models.animation.state.EMFState")
                 &&matches("traben.entity_model_features.models.animation.state.EMFEntityRenderState")
                 &&matches("traben.entity_model_features.mod_compat.IrisShadowPassDetection")
                 &&matches("traben.entity_model_features.utils.UEntityTypes");
            case "PerfBlockOcclusionMixin" ->
                 matches(target)&&matches("com.copycatsplus.copycats.mixin.foundation.copycat.BlockStateBaseMixin")
                 &&matches("snownee.passablefoliage.mixin.BlockStateMixin")&&matches("snownee.passablefoliage.PassableFoliage")
                 &&matches("snownee.passablefoliage.PassableFoliageCommonConfig")&&matches("snownee.passablefoliage.PassableFoliageBlock");
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
                 matches(target)&&matches("de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider")
                 &&matches("de.odysseus.ithaka.digraph.util.fas.AbstractFeedbackArcSetProvider")
                 &&matches("de.odysseus.ithaka.digraph.Digraphs");
            case "PerfLogBegoneMixin" ->
                 matches(target)&&matches("mod.azure.logbegone.LogBegoneConfig")
                 &&matches("mod.azure.logbegone.LogBegoneConfig$Common");
            case "PerfEmojiFontRendererMixin" ->
                 matches(target)&&matches("com.hrznstudio.emojiful.Constants")
                 &&matches("com.hrznstudio.emojiful.api.Emoji")
                 &&matches("com.hrznstudio.emojiful.api.EmojiFromTwitmoji")
                 &&matches("com.hrznstudio.emojiful.api.EmojiFromGithub")
                 &&matches("com.hrznstudio.emojiful.api.EmojiFromEmojipedia");
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
                 matches(target)&&matches("pigcart.particlerain.particle.WeatherParticle")
                 &&matches("pigcart.particlerain.config.ConfigManager")
                 &&matches("pigcart.particlerain.config.ConfigData")
                 &&matches("pigcart.particlerain.config.ConfigData$CompatibilityOptions");
            case "PerfLevelRendererWeatherMixin" ->
                 matches(target)&&matches("pigcart.particlerain.ParticleRain")
                 &&matches("pigcart.particlerain.config.ConfigManager")
                 &&matches("pigcart.particlerain.config.ConfigData")
                 &&matches("pigcart.particlerain.config.ConfigData$CompatibilityOptions")
                 &&matches("pigcart.particlerain.mixin.render.WeatherEffectRendererMixin");
            case "PerfSeasonColorHandlersMixin" ->
                 matches(target)&&matches("sereneseasons.util.SeasonColorUtil")&&matches("sereneseasons.season.SeasonHandler");
            case "PerfBatchableBufferSourceMixin" ->
                 matches(target)&&matches("net.raphimc.immediatelyfast.compat.IrisCompat")
                 &&matches("net.raphimc.immediatelyfast.feature.core.BufferBuilderPool");
            case "PerfChunkBuilderMeshingTaskMixin" ->
                 matches(target)&&matches("fr.madu59.obe.client.mixin.renderer.compat.sodium.ChunkBuilderMeshingTaskMixin")
                 &&matches("fr.madu59.obe.client.util.meshing.SectionMeshingUtil");
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
            case "PerfEmfModelPartMixin"->applyClientPatch("patchEmfModelPart",targetClass);
            case "PerfEmfMathParserMixin"->applyClientPatch("patchMathExpressionParser",targetClass);
            case "PerfEmojiFontRendererMixin"->applyClientPatch("patchEmojiFontRenderer",targetClass);
            case "PerfChunkBuilderMeshingTaskMixin"->applyClientPatch("patchChunkBuilderMeshingTask",targetClass);
            case "PerfPalettedContainerMixin"->applyClientPatch("patchPalettedContainer",targetClass);
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
