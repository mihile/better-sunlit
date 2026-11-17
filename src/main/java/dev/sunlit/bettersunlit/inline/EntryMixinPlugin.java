package dev.sunlit.bettersunlit.inline;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Optional patches for reviewed binaries only; never initializes target classes. */
public final class EntryMixinPlugin implements IMixinConfigPlugin {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("net.minecraft.world.level.block.state.properties.IntegerProperty","4185a4b6a6eeffe5e873afb6dbe5f0ece0e5742cbf4cc5fecac422b4c089d820"),
        Map.entry("com.simibubi.create.compat.jei.category.BlockCuttingCategory","5a9225b09d6d618adb9cd3569c7a4e6387df7015a654954a196dec3424be9767"),
        Map.entry("com.simibubi.create.compat.jei.category.BlockCuttingCategory$CondensedBlockCuttingRecipe","639d235f4f28d56a4d4216dc801af1acaf72b77b1a4b3c15a961206080eed407"),
        Map.entry("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping","317f12fbb3c8f88d31861c3c48394a26a3ff9c13759ff597c0b5aa826c5546f8"),
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
        Map.entry("com.cicada.longwings.block.RenderedItems$RenderedButterflyItem$1","1b395e9f597159abccbce1b454402511f58fbc7279cf5ba2fe2eca66852d8187"),
        Map.entry("com.cicada.longwings.block.RenderedItems$RenderedMothItem$1","b668c7c1eabe6cfa6ea9f77bd641280a0dd81148cba077ecb4c35d63106a96ab"),
        Map.entry("com.cicada.longwings.block.RenderedItems$RenderedCatchingNetItem$1","f42a1304c4a870b6c19c4ccdb9bd86173f1a4e4b5aadea84da0d164c28c7d805"),
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
        Map.entry("net.minecraft.world.level.chunk.Palette","541587aa197255575e66f4849e5fdfcc798434ff60be80a18c2807bb51ba95c4"),
        Map.entry("org.violetmoon.zetaimplforge.event.ForgeZetaEventBus","ac733daf7d44811759117364dca49d1e0284ec2488f255bbf8cf994c3a1af378"),
        Map.entry("org.violetmoon.zetaimplforge.event.ForgeEventsRemapper","c9641ec662ad5931dd00351f995822bb1924d221a0117189ef5cd7f56d9dc6d3"),
        Map.entry("net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement","c4ad250470ec6eeeb28ca87ea5951b395d4d26b53db3dff5bf84e20eb59facdb"),
        Map.entry("com.github.spacemex.skillsexpnotifier.client.ClientLoginHandler","8ce63c02235c9cf587323fb243d4597edcc82b58c1d2b97e8befc3e0c01e10fe"),
        Map.entry("com.github.spacemex.skillsexpnotifier.client.SearchForSkills","a7888feab2427bf8b263dd6c37ff47f86a9c01a4a929837ac82caa002fec7862"),
        Map.entry("dev.ftb.mods.ftbchunks.client.ClientTaskQueue","da66701c7470b3178f8a3f4941cd9a72d604977fa9c2ab52cfd3fd9f31368ff1"),
        Map.entry("dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig","17085d58658730befeefb521c9fcc3445cb03ecf80642280f077c6dd784c4286"),
        Map.entry("dev.ftb.mods.ftbchunks.client.FTBChunksClient","158939e9e1df64f0832fcba3752c2b8587a598e9163c80f0dec0b8809f683366"),
        Map.entry("dev.ftb.mods.ftbchunks.client.map.MapTask","513c4210b2573e7a8656dc725372defe975410bdf44265589d23daca7153617a"),
        Map.entry("dev.ftb.mods.ftbchunks.client.map.MapRegion","1ce498fd44ab22d993e64946f35668953d9c5b934c1c2202687e899ad6e84c4f"),
        Map.entry("dev.ftb.mods.ftbchunks.client.map.MapRegionData","19d995918d9a212d73f12d693901efa62e173b73eb3d4ce619cb5f289a84a421"),
        Map.entry("journeymap.client.texture.ImageUtil","0be50ac84c542fec7b8f40ab7878cf6ac5cba842c283fc163272836437a6685a"),
        Map.entry("journeymap.client.cartography.color.RGB","592d85465605545ab783c9c3fe620f3d3f08529dd76c8d62f02980c40fcb33e3"),
        Map.entry("net.minecraft.client.Minecraft","e693a6b597702229c396382fb199ccf752497e61757c216efb20d72d5a60a3bd"),
        Map.entry("net.minecraft.client.gui.screens.LoadingOverlay","be07e1887cc7233ecbfe08941657c3aaafbc866c65f6a3eba3f978776d8359ee"),
        Map.entry("dev.latvian.mods.kubejs.script.data.GeneratedResourcePack","aa9d65456dadd0f902a0d983c8b5f19c606a8f000165746d0336221fcb870d05"),
        Map.entry("fuzs.puzzleslib.impl.client.event.ForgeClientEventInvokers","c484e68ba4853ed3be12ed9233afe8f19fbf09070a1ae77fd3b48f79520957c1"),
        Map.entry("net.mehvahdjukaar.mysticaloaktree.client.TreeLoreManager","cdfda0747c979cd447f1db38f02315cdfeeb3cd92422c3a7b46f4a01e6103c3a"),
        Map.entry("net.irisshaders.iris.features.FeatureFlags","3b23f1a97703eb18987e99156fd6cefdbf1c620fe4ea5939af47dd53f36b88f5"),
        Map.entry("com.moepus.gbf.molang.compiler.MolangCompiler","27b4dfa88fe893a53d2697814c954be2411bad04a0845fd1485e3305b061ea30"),
        Map.entry("me.cortex.voxy.client.core.gl.AsyncTextureUploader","5ddde94f5a54649012414adc300ff4d5491f18e58db3f629156ed6f95c34f9ed"),
        Map.entry("com.Pink_Cats.createschematicchecker.online.VersionChecker","2632d1b874e9cde9d76530cca3df82f38bff1c089981515e502b2256fad57928"),
        Map.entry("xyz.apex.forge.commonality.trust.TrustedSourceList","ce747596ba4bd29757c55c9199ab956d33822d8bcaba51feaec929d1b07ed019"),
        Map.entry("net.irisshaders.iris.gl.shader.StandardMacros","82c76a34a78abf69d248553c05512ccda7de31f6dbcc86e2523153cb38bea227"),
        Map.entry("net.irisshaders.iris.gl.IrisRenderSystem","533b39ce0a67887a1e113d2c0dbceba7a4620d6b58b71f5e553991b488cc51b2"),
        Map.entry("net.minecraft.client.renderer.texture.SpriteLoader","c19caf36d6fb62ee9217c3abffb2558335e69d7eb96bccebb656fed7868211c2"),
        Map.entry("com.hrznstudio.emojiful.ClientEmojiHandler","f8384a360045d6a6ccc5efa22ecd39d4f6187615e707b9b5834af3d05613736b"),
        Map.entry("dev.isxander.yacl3.gui.image.YACLImageReloadListener","3132b905c1d87239589abc21385be2c8f8eb0865008d87fb69b85c271fb48f29"),
        Map.entry("journeymap.client.io.ThemeLoader","f2c8cb204f09c8dac5bdf4fcdec35b463bbc493b9e66e7abbc8ca5e0ac117a78"),
        Map.entry("org.watermedia.loaders.ForgeMCLoader","a337648220e566ea7986e625c6efda4c9c109b194449d16d00476418b4f01589"),
        Map.entry("org.watermedia.api.player.PlayerAPI","dad94df7ab6eb7c7525fb9a3665da31da96ed32402b81d34360b4d5a04e3ec78"),
        Map.entry("org.watermedia.api.image.ImageAPI","3c0c4440e718c60aadf943c8f228c6090c73639e3a8c88e2d41dc3d695add6cd"),
        Map.entry("org.watermedia.api.cache.CacheAPI","90625c6251eeb3111a466330da5c1c0216c50a03a06b369b6ff876f1063d93b2"),
        Map.entry("dev.latvian.mods.kubejs.client.GeneratedClientResourcePack","f2f9c9af981bf7531fa4c1be06fa92304881cd33eda2f1ba436d7f59922a625a"),
        Map.entry("net.minecraft.client.resources.model.ModelBakery","4c211a2523c6c4592dc18fb398bcff9e74ff9c9bc8228741a438630bbc15558b"),
        Map.entry("org.embeddedt.modernfix.forge.dynresources.ModelBakeEventHelper","1a85c1cb9cd10371a889e32da0de66efefbcacc6d29b39534e8cb97ce9ef976a"),
        Map.entry("com.refinedmods.refinedstorage.render.BakedModelOverrideRegistry","5e879be37646f130b9259f0580be73fd0dcf1b75cd76e17251e6dc871f1d8217"),
        Map.entry("com.refinedmods.refinedstorage.setup.ClientSetup","08b044ee10775c825277a5c2f0b2ba9903c665fb2ece0ee724401d34ffe6ed67"),
        Map.entry("net.minecraft.CrashReport","b4cfe8e4ae7cb0c0160154b0f7653cf9b99cf415dd786aafe74c85963ce616d8"),
        Map.entry("net.minecraft.SystemReport","849ea2abf0c66580440489c1d024862beb94a523a962055425aab99aeb6f60e4"),
        Map.entry("net.p3pp3rf1y.sophisticatedbackpacks.client.render.ClientBackpackShapeProvider","a327ac777cede514c4a8be02f985e95659b4c862a8ab665c5f6ffbccd6db933e"),
        Map.entry("software.bernie.geckolib.core.molang.MolangParser","16bd435826408fdb60c81a56a0f17fd7480473619e6f2a65a92a43498563fffb"),
        Map.entry("net.minecraft.server.packs.resources.ReloadableResourceManager","7148ba66f24f4cb676d102dddbdfc290e06cae09681052a0ca3c5747bfc7844f"),
        Map.entry("net.minecraft.client.resources.language.ClientLanguage","3bad5419eb6fe8693c8bdd6b1a52d108cb2e99a8b5d413bf39c4d4f60380d0f8"),
        Map.entry("com.github.alexthe666.citadel.web.WebHelper","1ca138c9ed07e9a6c03fc431f1659ed8419438b9de209d2cf4f0a00f25be65b9"),
        Map.entry("me.lucko.spark.common.ws.TrustedKeyStore","ac8305f2a84346991923fb04ea24034f12028a08a8f0b645332fc5ab5ea67c43"),
        Map.entry("com.simibubi.create.foundation.model.ModelSwapper","2b8fc2b1e53424457fd45f4d00c9f91278d0e2652627d75ba479c8ebfec2901f"),
        Map.entry("net.minecraftforge.client.loading.ClientModLoader","f5a1ebd0557c4a622e60e265f627c6dcece3593b2f394dbeda42cbb6d682be57"),
        Map.entry("de.keksuccino.fancymenu.util.mcef.MCEFUtil","134d802bf7b5fd34b07b74dc7267c7bccd6e082fb3e19c27e1493d95994d0331"),
        Map.entry("com.teamabnormals.blueprint.client.RewardHandler","9f162609b6919116fbd3d2f0cb9cbb6aae90c0bf761470e53e88b3f1ffdc153a"),
        Map.entry("net.minecraftforge.registries.GameData","17d80637236c7b621161681fd573e8a14961469dc8cbcbc1ba329496fd859406"),
        Map.entry("dev.uncandango.alltheleaks.leaks.common.mods.forge.Issue10684","fdac9efcc8ab5d8613320992292157a5ba7261fd0b1161fba992a1ee54ddb06d"),
        Map.entry("net.minecraftforge.client.ForgeHooksClient","56a62dfb8dfcd68dfc45451b1a8c51b414f3462cb1e16b2e9171ad571d3ad955"),
        Map.entry("org.embeddedt.modernfix.dynamicresources.DynamicBakedModelProvider","a442acaf98896e5b976d6590769d5aa760d8b35c810514aadf0a5b6a6faacf04"),
        Map.entry("net.minecraft.client.model.geom.EntityModelSet","5b66679a5ba96b215c131fec5bc9c7862a668188d3f9fd3465d513f21132a74f"),
        Map.entry("traben.entity_model_features.EMFManager","819d94e3fc64b04cd8cfcebb73f1b894ff836e3a97be29762a60babe7c714d40"),
        Map.entry("traben.entity_texture_features.features.ETFManager","a7523f13ca3fce508cf3721cde3737ca0f960beb7c1c63b73226af3b53041049"),
        Map.entry("net.minecraft.world.level.block.state.StateDefinition","2e8774b1d81626b33294439e528981b1a935718c9ea154fc1d9266acd97a2a2c"),
        Map.entry("me.jellysquid.mods.lithium.common.reflection.ReflectionUtil","9d59dafe68452f1b5028a84e15cb7232aa1b84b5f60ddbce454bd69819d9e437"),
        Map.entry("net.minecraft.client.renderer.texture.MipmapGenerator","1e47bdf5d77703579e216da562934fb4e9d5cf9540e88429a0a00dd1fd3ae9c0"),
        Map.entry("net.minecraft.client.renderer.texture.SpriteContents","8b9017c17646d3fdd314e3779f319af3706a968b66882c3cb3f0e8b8427398f2"),
        Map.entry("com.mojang.blaze3d.platform.NativeImage","24f84cf9b9c0358de8812c572565abe63559b7233fb03013c742d2c78d8d7689"),
        Map.entry("me.jellysquid.mods.sodium.client.util.color.ColorSRGB","b48afd04f6461be8bdbe3b375156dc2e068e62b2d23d0563f4f334f504ca2f76"),
        Map.entry("me.jellysquid.mods.sodium.mixin.features.textures.mipmaps.SpriteContentsMixin","963ac5b9d50d987a79a0e1158685d916bca3d9607da669c9f98a809f8339a234"),
        Map.entry("net.irisshaders.iris.mixin.bettermipmaps.MixinMipmapGenerator","814594606a3ab92e06d006595ee999903c4ce5368a28c5d4271fd58389d474b3"),
        Map.entry("net.irisshaders.iris.helpers.ColorSRGB","0d428a754f753cc18bc62110c6e890d80a1cf73c4b6001922eae7f3db2854887"),
        Map.entry("org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel","865ab5a6d9aa19111a26896a177c6d6b5a901497c9a0a1360e7c1c46800f94a1"),
        Map.entry("dev.djefrey.colorwheel.compile.transform.ClrwlTransformPatcher","f233bf6c71f929bf1fb298b58c1a8c5b32f641264360253989b1f8a1665219d9"),
        Map.entry("dev.djefrey.colorwheel.compile.transform.ClrwlComputeTransformPatcher","12c69b06ee869d467e874a540a47ac1760a297dcc435a6c69d95a715ff5c6403"),
        Map.entry("dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput","49991cc885a953beaf5a10c2352679c2550e586dd69053b47d1ac7c739b8eb73"),
        Map.entry("net.minecraft.world.entity.Entity","4701eaf96a2590af14040385e0c90c0e8b3ef47d74c12c582cb3d489b5864f08"),
        Map.entry("io.github.mortuusars.horseman.mixin.fits_in_boat.EntityMixin","000488d4b99c86e649bcc277ad0415e00bf61c7dced096f41e27164d5b6deeec"));
    private static final Map<String,String> ALT_HASHES=Map.ofEntries(
        Map.entry("traben.entity_model_features.EMFManager","1d94bd8653848282b1782a2808018b13a1979beea0e177ceb41db0734459233f"),
        Map.entry("traben.entity_texture_features.features.ETFManager","e49c730554048390b908a1ad668499f522a769f40fe52bfb2a07c1f2d13f35eb"),
        Map.entry("net.minecraft.world.entity.Entity","6e12e82aa70dd496466481e185a947d4f76c374592b10610fb957c4ca64cea14"),
        Map.entry("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase","2d0e63e450caead1710a462bc66ce913083a8c22e25be23e4e1cdaddb5d73e12"),
        Map.entry("net.minecraft.client.renderer.block.BlockModelShaper","2f56551223f6b64b195d9cb898877019cc7ca084aaa83a5eca60381cbd80936d"),
        Map.entry("com.mojang.blaze3d.vertex.PoseStack","eab986aad817df3afd87407a3d45fe82e9a5602a275926dc01ac67b774e00e8b"),
        Map.entry("net.minecraft.client.renderer.texture.TextureManager","f3d654e4d0f5628d8d7350b6f6c890f6cff9075f3431cc0806e115b80d73df01"),
        Map.entry("net.minecraft.client.renderer.LevelRenderer","c1603c5a96efece8736bcfc9842962ce5ffb3580331d0baf4d1e0f9984fecb3b"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer","7c0f3481565606795266222bfbb26cea6e925ee55b8704eb3c81a3a96a046088"),
        Map.entry("net.minecraft.world.level.chunk.PalettedContainer$Data","87e522635c87f7cedfbe1d8a2090ffaf95cd9fe5da55e315ec031eecf8aee966"),
        Map.entry("net.minecraft.client.Minecraft","e19f6b95ab7620a1b42cd04b8c41d91089fb4556d8eb53efefc2f810653b7f01"),
        Map.entry("net.minecraft.client.gui.screens.LoadingOverlay","23349ec2d2664f71b077f9f2543ca15d3d8e460f99400407687bd991e784b507"),
        Map.entry("net.minecraft.client.renderer.texture.SpriteLoader","efeabc5c9c824e9f1ad5a1a7ad06cec1cb22dbc0d5c531daf6f245e5e7a05cde"),
        Map.entry("net.minecraft.client.resources.model.ModelBakery","4be0d2eff8a85facb65e338472593c9aa02a6f37a201810b9f66c8b163af82ec"),
        Map.entry("net.minecraft.client.renderer.texture.MipmapGenerator","1c4e452d34cff6c168e4cfba8098219571d6e4bff6ea750f4323c99b772db6f8"),
        Map.entry("net.minecraft.client.renderer.texture.SpriteContents","505ab0e45498c9cb8ff81a6b7f219e1e736843cd466e893d61b09145b4bbaae9"),
        Map.entry("net.minecraft.CrashReport","8cdc6f8dc0bb3ba60a8c5055979162e2e813ad02bb7cac18bde751040f1c521f"));
    private static final Map<String,String> BASE_PACK_HASHES=Map.ofEntries(
        Map.entry("org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack","a11e33f91d79da3ec0f13a6b62cc9127d7afe3545376e05f60074bf85c86c432"),
        Map.entry("traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube","0a72d7f428c9e3ca699b850878743a89b91a882a8bbd78902da3e77fbdd07b45"),
        Map.entry("traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory","4fe90eba3e6f27fd370d6d06b660114383cc946dcbf303cc31e35c5572576e96"),

        Map.entry("pigcart.particlerain.particle.WeatherParticle","e9e5abc0befee19e84540dcfaab564c8f7a6b3a0f349894d0ca2355ee8eea71e"),
        Map.entry("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping","b4bf5457956aa53577497261b854c4e3782dc672ddec65ab90f1d51028b6d4f6"),
        Map.entry("net.irisshaders.iris.Iris","6443dc3ec3985a486b7b3668b066da58a3dcb59a5f92755574a5837d9ce70ab7"),
        Map.entry("net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager","6c25cc023db9092ea51f7c6fe5d411c11dad8d10ff01762be1e45a4f89b5f70b"),
        Map.entry("net.irisshaders.iris.pipeline.PipelineManager","5d2819cc104ed11aff2b77c2777cb208c40caa0058b2c80909fc84104f681d6b"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher","566012709c946994a42ef65b236069981004b6c278383559a8e8c23b45c38319"),
        Map.entry("net.irisshaders.iris.gl.shader.StandardMacros","0c36651e198832bb184d53741e9d811b14d2c6b185eabadd542ca0e5aa5a8604"),
        Map.entry("net.irisshaders.iris.gl.IrisRenderSystem","7c33c255ee78762039be84cdef06724a7b8674bc1848ea20c02b7fec06ede83e"),
        Map.entry("net.irisshaders.iris.pipeline.transform.TransformPatcher$2","1adb3ae6c90b0262f72d23d43526c618eb20cf2ac548ce10826290b82439e74a"),
        Map.entry("io.github.douira.glsl_transformer.ast.transform.ASTParser","c01dca22a87a5f8be8082e3aa0af7417f5b2b7ab37a307425f31536fefd399f9"),
        Map.entry("io.github.douira.glsl_transformer.parser.CachingParser","6297e0a4b12a47b3914696cf32c6700dcf7d7dadf24e1f846c0901c449464ef6"),
        Map.entry("io.github.douira.glsl_transformer.ast.data.TypedTreeCache","5dbfbc8ef8c082cc70187167f96de72f1e8077867ae2a16515d6843898956593"),
        Map.entry("de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider","39ae004f99785e45c160a91876f911da948409046f1924cfb704b23f9a20f197"),
        Map.entry("de.odysseus.ithaka.digraph.util.fas.AbstractFeedbackArcSetProvider","29a38832590b045249aac9ba183ff74f766e3e238f75fa15a25350c32db5fcc9"),
        Map.entry("de.odysseus.ithaka.digraph.Digraphs","1edfec18f5f01807ed3edbf0f083da6c8ee9127d47aeea51615fca8f791ef8e5"));
    private static boolean hasWrapMethodApi() {
        try {
            Class.forName("com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod",false,EntryMixinPlugin.class.getClassLoader());
            return true;
        } catch(ClassNotFoundException | LinkageError e) { return false; }
    }
    private final Map<String,Boolean> checked=new HashMap<>();
    private boolean matchesLegacy(String name, String expected) {
        return checked.computeIfAbsent(name+"#"+expected, key -> {
            try(var in=EntryMixinPlugin.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
                return in!=null && expected.equals(InlineBytecode.sha256(in.readAllBytes()));
            } catch(java.io.IOException e) { return false; }
        });
    }
    public boolean matches(String name) {
        return checked.computeIfAbsent(name,n->{
            String expected=HASHES.get(n);
            if(expected==null)return false;
            try(var in=EntryMixinPlugin.class.getClassLoader().getResourceAsStream(n.replace('.','/')+".class")) {
                if(in==null)return false;
                String actual=InlineBytecode.sha256(in.readAllBytes());
                return actual.equals(expected)||actual.equals(ALT_HASHES.get(n))||actual.equals(BASE_PACK_HASHES.get(n));
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
            case "PerfEmbeddiumVertexSortMixin" -> matchesLegacy(target,"5a50905acec917b1c8f070e088adfaaa8f311cef36800570bc851cf5faab6afa")
                 && matchesLegacy("me.jellysquid.mods.sodium.client.util.sorting.MergeSort","155209dc3a68097f9bcb33b4da6a530c6c26330a0f8d994642b79a29b1a83ffa")
                 && matchesLegacy("me.jellysquid.mods.sodium.client.util.sorting.InsertionSort","e915be27015c00a7b10fc9f2e98f7de416e7e8517e394f86c7b70c766408484d");
            case "PerfOculusUniformLoopsMixin" -> matchesLegacy(target,"8b2da77c80f58b7bd5031ca17eb32875f3653647a831e798306f9c4251dfc592")
                 && matchesLegacy("net.irisshaders.iris.gl.state.ValueUpdateNotifier","5e91d814140e4db643f9af65a7f7b6af9a62fce26666395db9f0e1558a442e93");
            case "PerfOculusSamplerLoopsMixin" -> matchesLegacy(target,"58179e9852536e385ec7c61079cf68e23af92f541fe5da0bfe014f6b1ec5f625")
                 && matchesLegacy("net.irisshaders.iris.gl.sampler.SamplerBinding","657b4df206fd2cbbfc5d0ea2a83cbb0014114ebe3e0507d994bdbcb62d79c643")
                 && matchesLegacy("net.irisshaders.iris.gl.state.ValueUpdateNotifier","5e91d814140e4db643f9af65a7f7b6af9a62fce26666395db9f0e1558a442e93");
            case "PerfOculusSamplerUnbindMixin" -> matchesLegacy(target,"7c33c255ee78762039be84cdef06724a7b8674bc1848ea20c02b7fec06ede83e");
            case "LegacyEmfModelPartMixin" -> matchesLegacy(target,"8b0f4741818980e7d8704bb86f42e46c98efda1c7d103fd4ebbae41d8994ed90") && matches("com.mojang.blaze3d.vertex.PoseStack") && matches("org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack");
            case "LegacyParticleSpawnerMixin" -> hasWrapMethodApi() && matchesLegacy(target,"1f5457b859c9996f8941980a077fd068971b8c07c47b60f34dc328ec2d4f0750");
            case "LegacyCustomParticleMixin" -> matchesLegacy(target,"2f3f8e3d43f975a9d9177f4f2d18609beccfb1b00c54161e4e087a3b8ffc868a");
            case "LegacyLevelRendererWeatherMixin" -> matches(target) && matchesLegacy("pigcart.particlerain.config.ConfigData$CompatibilityOptions","bf2636131b1742d927d9e42f40b0ef4691c8b7ef33672a1058d0a8edd1e38e5a");

            case "LegacyClrwlTransformPatcherMixin" -> matchesLegacy(target,"cc26715a2954be723a793b095e4e54404df8d9c213878719f6f43b62762316f4") && matchesLegacy("dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput","49991cc885a953beaf5a10c2352679c2550e586dd69053b47d1ac7c739b8eb73");
            case "PerfEntityModelSetMixin" -> matches(target)
                 && (matches("traben.entity_model_features.EMFManager")
                     || matchesLegacy("traben.entity_model_features.EMFManager",
                         "1d94bd8653848282b1782a2808018b13a1979beea0e177ceb41db0734459233f"))
                 && (matches("traben.entity_texture_features.features.ETFManager")
                     || matchesLegacy("traben.entity_texture_features.features.ETFManager",
                         "e49c730554048390b908a1ad668499f522a769f40fe52bfb2a07c1f2d13f35eb"));
            case "PerfCreateBlockCuttingMixin" -> matches(target)
                 &&matches("com.simibubi.create.compat.jei.category.BlockCuttingCategory$CondensedBlockCuttingRecipe");
            case "PerfIrisBlockMappingMixin" -> matches(target)
                 &&matches("net.minecraft.world.level.block.state.properties.IntegerProperty");
            case "EntryJeiTypedCacheMixin", "PerfButterflyVariantMixin", "PerfButterflyEntityMixin",
                 "PerfButterflyModelMixin", "PerfButterflyItemRendererMixin", "PerfMothItemRendererMixin",
                 "PerfRenderedButterflyItemExtMixin", "PerfRenderedMothItemExtMixin",
                 "PerfRenderedCatchingNetItemExtMixin",
                 "PerfHandbookScreenMixin", "PerfHandbookIndexButtonMixin", "PerfHandbookToastMixin",
                 "PerfParticleSpawnerMixin", "PerfWeatherParticleMixin", "PerfSeasonsConfigMixin",
                 "PerfSeasonHandlerMixin", "PerfSeasonHandlerClientMixin", "PerfSeasonHooksMixin",
                 "PerfSeasonColorUtilMixin", "PerfAmbientBiomeAreaMixin",
                 "PerfIrisDimensionMixin",
                 "PerfJeiSortingConfigMixin", "PerfEmfMathParserMixin",
                 "PerfJeiRegistryWrapperMixin", "PerfEmfModelPartVariableMixin",
                 "PerfTextureManagerMixin", "PerfWhitelistMixin",
                 "PerfSingleValuePaletteMixin",
                 "PerfForgeZetaEventBusMixin", "PerfForgeEventsRemapperMixin",
                 "PerfRandomSpreadStructurePlacementMixin" -> matches(target);
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
            case "PerfSkillsLoginScanMixin" ->
                 matches(target)&&matches("com.github.spacemex.skillsexpnotifier.client.SearchForSkills");
            case "PerfFtbChunksTaskQueueMixin" ->
                 matches(target)&&matches("dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig")
                 &&matches("dev.ftb.mods.ftbchunks.client.map.MapTask");
            case "PerfFtbChunksRegionDataMixin" ->
                 matches(target)&&matches("dev.ftb.mods.ftbchunks.client.map.MapRegion")
                 &&matches("dev.ftb.mods.ftbchunks.client.FTBChunksClient");
            case "PerfJourneyMapImageUtilMixin", "PerfJourneyMapRgbMixin" ->
                 matches(target)&&matches("journeymap.client.cartography.color.RGB");
            case "PerfClrwlTransformPatcherMixin" ->
                 matches(target)&&matches("dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput");
            case "PerfClrwlComputeTransformPatcherMixin" ->
                 matches(target);
            case "PerfEntityHorsemanMixin" ->
                 matches(target)&&matches("io.github.mortuusars.horseman.mixin.fits_in_boat.EntityMixin");
            case "PerfIrisLoadingCompleteMixin", "PerfLoadingOverlayMixin", "PerfKubeJsPackMixin",
                 "PerfPuzzlesModelEventsMixin", "PerfMysticalTreeLoreMixin",
                 "PerfTrustedSourceListMixin", "PerfCscVersionCheckerMixin",
                 "PerfIrisAsyncLoadMixin", "PerfIrisFeatureFlagsMixin",
                 "PerfVoxyAsyncUploaderMixin", "PerfSearchForSkillsMixin",
                 "PerfGbfMolangCompilerMixin", "PerfIrisStandardMacrosMixin",
                 "PerfIrisRenderSystemMixin",
                 "PerfSpriteLoaderMipmapMixin", "PerfEmojifulClientHandlerMixin",
                 "PerfYaclImageReloadMixin", "PerfJourneymapThemeMixin",
                 "PerfWaterMediaLoaderMixin", "PerfWaterMediaPlayerMixin",
                 "PerfWaterMediaImageMixin", "PerfWaterMediaCacheMixin",
                 "PerfKubeJsClientPackMixin",
                 "PerfModelBakeryPrewarmMixin", "PerfModelBakeEventHelperMixin",
                 "PerfBakedModelOverrideRegistryAccessor", "PerfRefinedStorageBakeMixin",
                 "PerfCrashReportPreloadMixin", "PerfSystemReportPreloadMixin",
                 "PerfBackpackShapeMixin", "PerfMolangParserMixin",
                 "PerfCitadelWebHelperMixin", "PerfSparkTrustedKeyStoreMixin",
                 "PerfClientLanguageMixin", "PerfCreateModelSwapperMixin",
                 "PerfClientModLoaderMixin", "PerfMcefUtilMixin",
                 "PerfBlueprintRewardMixin", "PerfGameDataMixin",
                 "PerfAllTheLeaksIssue10684Mixin", "PerfForgeLayerDefinitionsMixin",
                 "PerfDynamicBakedModelProviderMixin", "PerfEmfManagerInitMixin",
                 "PerfEtfManagerInitMixin",
                 "PerfStateDefinitionMixin", "PerfRadiumReflectionMixin",
                 "PerfNativeImageAccessor" ->
                 matches(target);
            case "PerfMipmapFastMixin" ->
                 matches(target)
                 && matches("com.mojang.blaze3d.platform.NativeImage")
                 && matches("me.jellysquid.mods.sodium.client.util.color.ColorSRGB")
                 && matches("net.irisshaders.iris.mixin.bettermipmaps.MixinMipmapGenerator")
                 && matches("net.irisshaders.iris.helpers.ColorSRGB");
            case "PerfSpriteContentsTransparencyMixin" ->
                 matches(target)
                 && matches("com.mojang.blaze3d.platform.NativeImage")
                 && matches("me.jellysquid.mods.sodium.client.util.color.ColorSRGB")
                 && matches("me.jellysquid.mods.sodium.mixin.features.textures.mipmaps.SpriteContentsMixin")
                 && matches("org.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel");
            case "PerfMinecraftStartupTickMixin" ->
                 matches(target)&&matches("net.minecraft.client.gui.screens.LoadingOverlay");
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
            case "PerfStateDefinitionMixin"->EntryPostApplyPatches.patchStateDefinition(targetClass);
            case "PerfEntityHorsemanMixin"->EntryPostApplyPatches.patchEntityHorseman(targetClass);
            case "PerfMipmapFastMixin"->applyClientPatch("patchMipmapGenerator",targetClass);
            case "PerfSpriteContentsTransparencyMixin"->applyClientPatch("patchSpriteContents",targetClass);
            case "PerfObeModelMixin"->applyClientPatch("patchBlockModelShaper",targetClass);
            case "PerfModelPartCubeMixin"->applyClientPatch("patchModelPartCube",targetClass);
            case "PerfEmfModelPartMixin", "LegacyEmfModelPartMixin"->applyClientPatch("patchEmfModelPart",targetClass);
            case "PerfEmfMathParserMixin"->applyClientPatch("patchMathExpressionParser",targetClass);
            case "PerfEmojiFontRendererMixin"->applyClientPatch("patchEmojiFontRenderer",targetClass);
            case "PerfChunkBuilderMeshingTaskMixin"->applyClientPatch("patchChunkBuilderMeshingTask",targetClass);
            case "PerfPalettedContainerMixin"->applyClientPatch("patchPalettedContainer",targetClass);
            case "PerfGbfMolangCompilerMixin"->applyClientPatch("patchGbfMolangCompiler",targetClass);
            case "PerfLoadingOverlayMixin"->applyClientPatch("patchLoadingOverlay",targetClass);
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
