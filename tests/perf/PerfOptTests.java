package dev.sunlit.bettersunlit.tests.perf;

import com.cicada.longwings.entity.ButterflyVariant;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.hrznstudio.emojiful.Constants;
import com.hrznstudio.emojiful.api.Emoji;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.sun.management.ThreadMXBean;
import de.odysseus.ithaka.digraph.Digraph;
import de.odysseus.ithaka.digraph.Digraphs;
import de.odysseus.ithaka.digraph.MapDigraph;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSet;
import de.odysseus.ithaka.digraph.util.fas.FeedbackArcSetPolicy;
import de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider;
import dev.sunlit.bettersunlit.client.AmbientSoundsClientPerf;
import dev.sunlit.bettersunlit.client.ClientPostApplyPatches;
import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import dev.sunlit.bettersunlit.client.EmojiRenderPerf;
import dev.sunlit.bettersunlit.client.GraphOrderPerf;
import dev.sunlit.bettersunlit.client.IrisAstCachePerf;
import dev.sunlit.bettersunlit.client.IrisDimensionPerf;
import dev.sunlit.bettersunlit.client.JeiRegistryPerf;
import dev.sunlit.bettersunlit.client.JeiSortingPerf;
import dev.sunlit.bettersunlit.client.LongwingsClientPerf;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.SeasonColorPerf;
import dev.sunlit.bettersunlit.inline.EntryMixinPlugin;
import dev.sunlit.bettersunlit.inline.EntryPostApplyPatches;
import dev.sunlit.bettersunlit.perf.BlockOcclusionPerf;
import dev.sunlit.bettersunlit.perf.LogBegonePerf;
import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import dev.sunlit.bettersunlit.perf.SeasonPerf;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import mezz.jei.common.platform.IPlatformRegistry;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.AxisAngle4d;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import sereneseasons.season.SeasonColorHandlers;
import sereneseasons.season.SeasonTime;
import sereneseasons.util.SeasonColorUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PerfOptTests {
    private static final ThreadMXBean THREAD_MX = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static final List<String> ALL_NEW_TARGETS = List.of(
            "com.cicada.longwings.entity.ButterflyVariant",
            "com.cicada.longwings.entity.ButterflyEntity",
            "com.cicada.longwings.client.renderers.ButterflyEntityRenderer",
            "com.cicada.longwings.client.renderers.ButterflyEntityRenderer$MothEntityRenderer",
            "com.cicada.longwings.client.model.ButterflyModel",
            "com.cicada.longwings.client.renderers.ButterflyItemRenderer",
            "com.cicada.longwings.client.renderers.ButterflyItemRenderer$MothItemRenderer",
            "com.cicada.longwings.client.gui.HandbookScreen",
            "com.cicada.longwings.client.gui.HandbookScreen$ButterflyIndexButton",
            "com.cicada.longwings.client.gui.HandbookToast",
            "pigcart.particlerain.ParticleRain",
            "pigcart.particlerain.ParticleSpawner",
            "pigcart.particlerain.WindManager",
            "pigcart.particlerain.particle.WeatherParticle",
            "pigcart.particlerain.particle.CustomParticle",
            "sereneseasons.config.SeasonsConfig",
            "sereneseasons.season.SeasonHandler",
            "sereneseasons.season.SeasonHooks",
            "sereneseasons.season.SeasonColorHandlers",
            "sereneseasons.util.SeasonColorUtil",
            "team.creative.ambientsounds.environment.BiomeEnvironment$BiomeArea",
            "team.creative.ambientsounds.environment.pocket.AirPocketScanner",
            "net.irisshaders.iris.Iris",
            "mezz.jei.common.config.sorting.SortingConfig",
            "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
            "com.copycatsplus.copycats.mixin.foundation.copycat.BlockStateBaseMixin",
            "snownee.passablefoliage.mixin.BlockStateMixin",
            "snownee.passablefoliage.PassableFoliage",
            "net.minecraft.client.renderer.block.BlockModelShaper",
            "fr.madu59.obe.client.mixin.BlockStateModelSetMixin",
            "net.minecraft.client.model.geom.ModelPart$Cube",
            "traben.entity_model_features.models.parts.EMFModelPart",
            "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube",
            "traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser",
            "com.mojang.blaze3d.vertex.PoseStack",
            "org.embeddedt.embeddium.render.matrix_stack.CachingPoseStack",
            "net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager",
            "de.odysseus.ithaka.digraph.util.fas.SimpleFeedbackArcSetProvider",
            "mod.azure.logbegone.LogBegoneMod",
            "mod.azure.logbegone.LogBegoneConfig",
            "mod.azure.logbegone.LogBegoneConfig$Common",
            "com.hrznstudio.emojiful.render.EmojiFontRenderer",
            "com.hrznstudio.emojiful.Constants",
            "com.hrznstudio.emojiful.api.Emoji",
            "net.irisshaders.iris.pipeline.PipelineManager",
            "io.github.douira.glsl_transformer.ast.transform.ASTParser",

            "net.irisshaders.iris.pipeline.transform.TransformPatcher",
            "net.irisshaders.iris.pipeline.transform.TransformPatcher$2",
            "io.github.douira.glsl_transformer.parser.CachingParser",
            "io.github.douira.glsl_transformer.ast.data.TypedTreeCache",
            "mezz.jei.forge.platform.RegistryWrapper",
            "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource",
            "net.raphimc.immediatelyfast.compat.IrisCompat",
            "traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory",
            "me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
            "fr.madu59.obe.client.mixin.renderer.compat.sodium.ChunkBuilderMeshingTaskMixin",
            "fr.madu59.obe.client.util.meshing.SectionMeshingUtil"
    );

    private static final List<String> ALL_PERF_MIXINS = List.of(
            "dev.sunlit.fishvisuals.mixin.PerfButterflyVariantMixin",
            "dev.sunlit.fishvisuals.mixin.PerfButterflyEntityMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonsConfigMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHandlerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHooksMixin",
            "dev.sunlit.fishvisuals.mixin.PerfBlockOcclusionMixin",
            "dev.sunlit.fishvisuals.mixin.PerfLogBegoneMixin",
            "dev.sunlit.fishvisuals.mixin.PerfButterflyRendererMixin",
            "dev.sunlit.fishvisuals.mixin.PerfMothRendererMixin",
            "dev.sunlit.fishvisuals.mixin.PerfButterflyModelMixin",
            "dev.sunlit.fishvisuals.mixin.PerfButterflyItemRendererMixin",
            "dev.sunlit.fishvisuals.mixin.PerfMothItemRendererMixin",
            "dev.sunlit.fishvisuals.mixin.PerfHandbookScreenMixin",
            "dev.sunlit.fishvisuals.mixin.PerfHandbookIndexButtonMixin",
            "dev.sunlit.fishvisuals.mixin.PerfHandbookToastMixin",
            "dev.sunlit.fishvisuals.mixin.PerfParticleSpawnerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfWeatherParticleMixin",
            "dev.sunlit.fishvisuals.mixin.PerfCustomParticleMixin",
            "dev.sunlit.fishvisuals.mixin.PerfWindManagerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHandlerClientMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonColorHandlersMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonColorUtilMixin",
            "dev.sunlit.fishvisuals.mixin.PerfAmbientBiomeAreaMixin",
            "dev.sunlit.fishvisuals.mixin.PerfAmbientAirPocketScannerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfIrisDimensionMixin",
            "dev.sunlit.fishvisuals.mixin.PerfJeiSortingConfigMixin",
            "dev.sunlit.fishvisuals.mixin.PerfObeModelMixin",
            "dev.sunlit.fishvisuals.mixin.PerfModelPartCubeMixin",
            "dev.sunlit.fishvisuals.mixin.PerfEmfCubeMixin",
            "dev.sunlit.fishvisuals.mixin.PerfEmfModelPartMixin",
            "dev.sunlit.fishvisuals.mixin.PerfEmfMathParserMixin",
            "dev.sunlit.fishvisuals.mixin.PerfGraphOrderMixin",
            "dev.sunlit.fishvisuals.mixin.PerfEmojiFontRendererMixin",
            "dev.sunlit.fishvisuals.mixin.PerfPipelineManagerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfTransformPatcherMixin",
            "dev.sunlit.fishvisuals.mixin.PerfJeiRegistryWrapperMixin",
            "dev.sunlit.fishvisuals.mixin.PerfBatchableBufferSourceMixin",
            "dev.sunlit.fishvisuals.mixin.PerfEmfModelPartVariableMixin",
            "dev.sunlit.fishvisuals.mixin.PerfChunkBuilderMeshingTaskMixin"
    );

    public static void main(String[] args) throws Exception {
        testEntryMixinPluginHashesAndGates();
        testMixinBytecodeContractsAgainstModJars();
        testLongwingsVariantPoolAndMatches();
        testLongwingsTextureCacheAndReload();
        testParticleRainMathAndZeroAlloc();
        testSereneSeasonsHelpers();
        testAmbientSoundsWildcards();
        testIrisDimensionCanonicalization();
        testJeiSortingPerf();
        testArea1BlockOcclusionAndModelShaperPatches();
        testArea1ChunkBuilderMeshingTaskPatch();
        testArea2EmfRenderPerfAndPatches();
        testArea2GraphOrderPerf();
        testArea3LogBegonePerf();
        testArea3EmojiRenderPerfAndPatch();
        testArea4IrisAstCachePerf();
        testArea4JeiRegistryPerf();
        ReviewRegressionTests.run();
        System.out.println("PerfOptTests: ALL PASSED");
    }

    private static void testEntryMixinPluginHashesAndGates() throws Exception {
        EntryMixinPlugin plugin = new EntryMixinPlugin();
        for (String target : ALL_NEW_TARGETS) {
            assertTrue(plugin.matches(target), "Hash mismatch for target: " + target);
        }
        assertFalse(plugin.matches("com.example.NonExistentClass"), "Unknown class should not match");

        // Verify tamper fail-closed via isolated ClassLoader where WeatherParticle bytes are altered.
        TamperPluginLoader tamperedLoader = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(),
                "pigcart/particlerain/particle/WeatherParticle.class");
        Class<?> tamperedPluginCls = tamperedLoader.loadClass(EntryMixinPlugin.class.getName());
        Object tamperedPlugin = tamperedPluginCls.getConstructor().newInstance();
        java.lang.reflect.Method matchesMethod = tamperedPluginCls.getMethod("matches", String.class);
        java.lang.reflect.Method gateMethod = tamperedPluginCls.getMethod("canApplyGate", String.class, String.class);
        assertFalse((Boolean) matchesMethod.invoke(tamperedPlugin, "pigcart.particlerain.particle.WeatherParticle"),
                "Tampered WeatherParticle bytes must fail hash check");
        assertTrue((Boolean) matchesMethod.invoke(tamperedPlugin, "pigcart.particlerain.particle.CustomParticle"),
                "Untampered CustomParticle still matches its own hash");
        assertFalse((Boolean) gateMethod.invoke(tamperedPlugin, "pigcart.particlerain.particle.WeatherParticle",
                "dev.sunlit.fishvisuals.mixin.PerfWeatherParticleMixin"),
                "Tampered WeatherParticle must gate PerfWeatherParticleMixin");
        assertFalse((Boolean) gateMethod.invoke(tamperedPlugin, "pigcart.particlerain.particle.CustomParticle",
                "dev.sunlit.fishvisuals.mixin.PerfCustomParticleMixin"),
                "Tampered WeatherParticle must fail-closed PerfCustomParticleMixin cross-class gate");

        // Verify remaining cross-class dependency gates fail closed when dependencies are tampered.
        TamperPluginLoader seasonTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "sereneseasons/util/SeasonColorUtil.class");
        Object seasonPlugin = seasonTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) seasonPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                seasonPlugin, "sereneseasons.season.SeasonColorHandlers",
                "dev.sunlit.fishvisuals.mixin.PerfSeasonColorHandlersMixin"),
                "Tampered SeasonColorUtil must fail-closed PerfSeasonColorHandlersMixin");

        TamperPluginLoader butterflyTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "com/cicada/longwings/entity/ButterflyEntity.class");
        Object butterflyPlugin = butterflyTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        java.lang.reflect.Method bfGate = butterflyPlugin.getClass().getMethod("canApplyGate", String.class, String.class);
        assertFalse((Boolean) bfGate.invoke(butterflyPlugin, "com.cicada.longwings.client.renderers.ButterflyEntityRenderer",
                "dev.sunlit.fishvisuals.mixin.PerfButterflyRendererMixin"),
                "Tampered ButterflyEntity must fail-closed PerfButterflyRendererMixin");
        assertFalse((Boolean) bfGate.invoke(butterflyPlugin, "com.cicada.longwings.client.renderers.ButterflyEntityRenderer$MothEntityRenderer",
                "dev.sunlit.fishvisuals.mixin.PerfMothRendererMixin"),
                "Tampered ButterflyEntity must fail-closed PerfMothRendererMixin");

        assertFalse(plugin.canApplyGate("com.cicada.longwings.entity.ButterflyVariant",
                "dev.sunlit.fishvisuals.mixin.UnknownMixin"),
                "Unknown mixin name must fail closed");

        TamperPluginLoader poseTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "com/mojang/blaze3d/vertex/PoseStack.class");
        Object posePlugin = poseTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        java.lang.reflect.Method poseGate = posePlugin.getClass().getMethod("canApplyGate", String.class, String.class);
        assertFalse((Boolean) poseGate.invoke(posePlugin, "net.minecraft.client.model.geom.ModelPart$Cube",
                "dev.sunlit.fishvisuals.mixin.PerfModelPartCubeMixin"),
                "Tampered PoseStack must fail-closed PerfModelPartCubeMixin");
        assertFalse((Boolean) poseGate.invoke(posePlugin, "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube",
                "dev.sunlit.fishvisuals.mixin.PerfEmfCubeMixin"),
                "Tampered PoseStack must fail-closed PerfEmfCubeMixin");
        assertFalse((Boolean) poseGate.invoke(posePlugin, "traben.entity_model_features.models.parts.EMFModelPart",
                "dev.sunlit.fishvisuals.mixin.PerfEmfModelPartMixin"),
                "Tampered PoseStack must fail-closed PerfEmfModelPartMixin");

        TamperPluginLoader obeTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "fr/madu59/obe/client/mixin/BlockStateModelSetMixin.class");
        Object obePlugin = obeTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) obePlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                obePlugin, "net.minecraft.client.renderer.block.BlockModelShaper",
                "dev.sunlit.fishvisuals.mixin.PerfObeModelMixin"),
                "Tampered OBE mixin must fail-closed PerfObeModelMixin");

        TamperPluginLoader pfTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "snownee/passablefoliage/PassableFoliage.class");
        Object pfPlugin = pfTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) pfPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                pfPlugin, "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase",
                "dev.sunlit.fishvisuals.mixin.PerfBlockOcclusionMixin"),
                "Tampered PassableFoliage must fail-closed PerfBlockOcclusionMixin");

        TamperPluginLoader fasTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "de/odysseus/ithaka/digraph/util/fas/SimpleFeedbackArcSetProvider.class");
        Object fasPlugin = fasTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) fasPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                fasPlugin, "net.irisshaders.batchedentityrendering.impl.ordering.GraphTranslucencyRenderOrderManager",
                "dev.sunlit.fishvisuals.mixin.PerfGraphOrderMixin"),
                "Tampered SimpleFeedbackArcSetProvider must fail-closed PerfGraphOrderMixin");

        TamperPluginLoader logTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "mod/azure/logbegone/LogBegoneConfig$Common.class");
        Object logPlugin = logTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) logPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                logPlugin, "mod.azure.logbegone.LogBegoneMod",
                "dev.sunlit.fishvisuals.mixin.PerfLogBegoneMixin"),
                "Tampered LogBegoneConfig$Common must fail-closed PerfLogBegoneMixin");

        TamperPluginLoader emojiTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "com/hrznstudio/emojiful/api/Emoji.class");
        Object emojiPlugin = emojiTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) emojiPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                emojiPlugin, "com.hrznstudio.emojiful.render.EmojiFontRenderer",
                "dev.sunlit.fishvisuals.mixin.PerfEmojiFontRendererMixin"),
                "Tampered Emoji must fail-closed PerfEmojiFontRendererMixin");

        TamperPluginLoader astTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "io/github/douira/glsl_transformer/ast/transform/ASTParser.class");
        Object astPlugin = astTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) astPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                astPlugin, "net.irisshaders.iris.pipeline.PipelineManager",
                "dev.sunlit.fishvisuals.mixin.PerfPipelineManagerMixin"),
                "Tampered ASTParser must fail-closed PerfPipelineManagerMixin");

        for (String dependency : List.of(
                "net/irisshaders/iris/pipeline/transform/TransformPatcher.class",
                "net/irisshaders/iris/pipeline/transform/TransformPatcher$2.class",
                "io/github/douira/glsl_transformer/parser/CachingParser.class",
                "io/github/douira/glsl_transformer/ast/data/TypedTreeCache.class")) {
            TamperPluginLoader loader = new TamperPluginLoader(EntryMixinPlugin.class.getClassLoader(), dependency);
            Object gated = loader.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
            for (String mixin : List.of("PerfPipelineManagerMixin", "PerfTransformPatcherMixin")) {
                String target = mixin.equals("PerfPipelineManagerMixin") ? "net.irisshaders.iris.pipeline.PipelineManager"
                        : "net.irisshaders.iris.pipeline.transform.TransformPatcher";
                assertFalse((Boolean)gated.getClass().getMethod("canApplyGate", String.class, String.class)
                        .invoke(gated, target, "dev.sunlit.fishvisuals.mixin." + mixin), "AST owner dependency must fail closed: " + dependency);
            }
        }

        TamperPluginLoader irisCompatTampered = new TamperPluginLoader(
                EntryMixinPlugin.class.getClassLoader(), "net/raphimc/immediatelyfast/compat/IrisCompat.class");
        Object irisCompatPlugin = irisCompatTampered.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
        assertFalse((Boolean) irisCompatPlugin.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                irisCompatPlugin, "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource",
                "dev.sunlit.fishvisuals.mixin.PerfBatchableBufferSourceMixin"),
                "Tampered IrisCompat must fail-closed PerfBatchableBufferSourceMixin");

        for (String dependency : List.of(
                "fr/madu59/obe/client/mixin/renderer/compat/sodium/ChunkBuilderMeshingTaskMixin.class",
                "fr/madu59/obe/client/util/meshing/SectionMeshingUtil.class")) {
            TamperPluginLoader loader = new TamperPluginLoader(EntryMixinPlugin.class.getClassLoader(), dependency);
            Object gated = loader.loadClass(EntryMixinPlugin.class.getName()).getConstructor().newInstance();
            assertFalse((Boolean) gated.getClass().getMethod("canApplyGate", String.class, String.class).invoke(
                    gated, "me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",
                    "dev.sunlit.fishvisuals.mixin.PerfChunkBuilderMeshingTaskMixin"),
                    "OBE dependency must fail-closed PerfChunkBuilderMeshingTaskMixin: " + dependency);
        }

        for (String mixinClass : ALL_PERF_MIXINS) {
            ClassNode node = readClassNode(mixinClass);
            String target = extractMixinTarget(node);
            assertTrue(plugin.matches(target), "Plugin must match Mixin target " + target + " for " + mixinClass);
            assertTrue(plugin.canApplyGate(target, mixinClass), "Plugin gate must allow " + mixinClass);
        }
    }

    private static void testMixinBytecodeContractsAgainstModJars() throws Exception {
        Map<String, ClassNode> targetCache = new HashMap<>();
        for (String mixinName : ALL_PERF_MIXINS) {
            ClassNode mixin = readClassNode(mixinName);
            String targetName = extractMixinTarget(mixin);
            assertNotNull(targetName, "Missing @Mixin target in " + mixinName);
            ClassNode target = targetCache.computeIfAbsent(targetName, n -> {
                try {
                    return readClassNode(n);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            for (FieldNode f : mixin.fields) {
                AnnotationNode shadow = findAnnotation(f.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
                if (shadow == null) continue;
                FieldNode tf = target.fields.stream()
                        .filter(x -> x.name.equals(f.name) && x.desc.equals(f.desc))
                        .findFirst().orElse(null);
                assertNotNull(tf, mixinName + " @Shadow field missing on " + targetName + ": " + f.name + " " + f.desc);
                boolean mStatic = (f.access & 0x0008) != 0;
                boolean tStatic = (tf.access & 0x0008) != 0;
                assertEquals(tStatic, mStatic, mixinName + " @Shadow field static mismatch: " + f.name);
            }

            for (MethodNode m : mixin.methods) {
                AnnotationNode shadow = findAnnotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
                if (shadow != null) {
                    MethodNode tm = findMethod(target, m.name + m.desc);
                    assertNotNull(tm, mixinName + " @Shadow method missing on " + targetName + ": " + m.name + m.desc);
                    assertEquals((tm.access & 0x0008) != 0, (m.access & 0x0008) != 0,
                            mixinName + " @Shadow method static mismatch: " + m.name + m.desc);
                }

                AnnotationNode inject = findAnnotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
                if (inject != null) {
                    boolean handlerStatic = (m.access & 0x0008) != 0;
                    @SuppressWarnings("unchecked")
                    List<String> targets = (List<String>) annValue(inject, "method");
                    assertNotNull(targets, mixinName + " @Inject missing method list");
                    for (String sig : targets) {
                        MethodNode tm = findMethod(target, sig);
                        assertNotNull(tm, mixinName + " @Inject target missing on " + targetName + ": " + sig);
                        assertEquals((tm.access & 0x0008) != 0, handlerStatic,
                                mixinName + " @Inject static mismatch for " + sig + " -> " + m.name);
                    }
                }

                AnnotationNode redirect = findAnnotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
                if (redirect != null) {
                    boolean handlerStatic = (m.access & 0x0008) != 0;
                    @SuppressWarnings("unchecked")
                    List<String> targets = (List<String>) annValue(redirect, "method");
                    AnnotationNode at = (AnnotationNode) annValue(redirect, "at");
                    String atTarget = (String) annValue(at, "target");
                    Integer require = (Integer) annValue(redirect, "require");
                    int expectedCount = require != null ? require : 1;
                    int totalHits = 0;
                    for (String sig : targets) {
                        MethodNode tm = findMethod(target, sig);
                        assertNotNull(tm, mixinName + " @Redirect target missing on " + targetName + ": " + sig);
                        assertEquals((tm.access & 0x0008) != 0, handlerStatic,
                                mixinName + " @Redirect static mismatch for " + sig + " -> " + m.name);
                        totalHits += countMethodInvoke(tm, atTarget);
                    }
                    assertEquals(expectedCount, totalHits,
                            mixinName + " @Redirect invoke count mismatch for " + atTarget);
                }

                AnnotationNode overwrite = findAnnotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Overwrite;");
                if (overwrite != null) {
                    MethodNode tm = findMethod(target, m.name + m.desc);
                    assertNotNull(tm, mixinName + " @Overwrite target missing on " + targetName + ": " + m.name + m.desc);
                    assertEquals((tm.access & 0x0008) != 0, (m.access & 0x0008) != 0,
                            mixinName + " @Overwrite static mismatch for " + m.name + m.desc);
                }
            }

            if ("dev.sunlit.fishvisuals.mixin.PerfWeatherParticleMixin".equals(mixinName)) {
                assertNotNull(findMethod(mixin, "m_6355_(F)I"),
                        "PerfWeatherParticleMixin must override m_6355_(F)I");
                assertEquals(null, findMethod(target, "m_6355_(F)I"),
                        "WeatherParticle must not already declare m_6355_(F)I");
                ClassNode mcParticle = readClassNode("net.minecraft.client.particle.Particle");
                assertNotNull(findMethod(mcParticle, "m_6355_(F)I"),
                        "Particle superclass must declare m_6355_(F)I");
            }
        }
    }

    private static void testLongwingsVariantPoolAndMatches() throws Exception {
        LongwingsVariantPerf.clearCachesForTests();
        ArrayList<ButterflyVariant> raw = new ArrayList<>();
        java.lang.reflect.Field bField = ButterflyVariant.class.getDeclaredField("butterflyVariants");
        bField.setAccessible(true);
        java.lang.reflect.Field mField = ButterflyVariant.class.getDeclaredField("mothVariants");
        mField.setAccessible(true);
        ButterflyVariant[] butterflies = (ButterflyVariant[]) bField.get(null);
        ButterflyVariant[] moths = (ButterflyVariant[]) mField.get(null);
        raw.addAll(Arrays.asList(butterflies));
        raw.addAll(Arrays.asList(moths));
        assertTrue(raw.size() >= 70, "Expected Longwings built-in variant pool to be populated, saw " + raw.size());

        ArrayList<ButterflyVariant> first = LongwingsVariantPerf.cachedAllVariantPool(butterflies, moths);
        ArrayList<ButterflyVariant> second = LongwingsVariantPerf.cachedAllVariantPool(butterflies, moths);
        assertSame(first, second, "cachedAllVariantPool must return identical cached list instance");
        assertEquals(raw.size(), first.size(), "Pooled size mismatch");
        for (int i = 0; i < raw.size(); i++) {
            assertSame(raw.get(i), first.get(i), "Variant mismatch at index " + i);
        }

        // Unmodifiable enforcement on mutators
        assertThrows(UnsupportedOperationException.class, () -> first.add(butterflies[0]));
        assertThrows(UnsupportedOperationException.class, () -> first.set(0, butterflies[0]));
        assertThrows(UnsupportedOperationException.class, () -> first.remove(0));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> first.subList(0, 2).set(0, butterflies[0]));
        assertThrows(UnsupportedOperationException.class, () -> first.replaceAll(x -> x));

        // Cache invalidates if backing array changes
        ButterflyVariant[] altMoths = Arrays.copyOf(moths, Math.max(0, moths.length - 1));
        ArrayList<ButterflyVariant> changed = LongwingsVariantPerf.cachedAllVariantPool(butterflies, altMoths);
        assertTrue(changed != first, "Expected new snapshot when array identity changes");
        assertEquals(butterflies.length + altMoths.length, changed.size(), "Changed pool size mismatch");
        LongwingsVariantPerf.clearCachesForTests();

        // Allocation check on pool lookup
        for (int i = 0; i < 500; i++) LongwingsVariantPerf.cachedAllVariantPool(butterflies, moths);
        long allocPool = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 5000; i++) {
                acc += LongwingsVariantPerf.cachedAllVariantPool(butterflies, moths).size();
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocPool, "cachedAllVariantPool hit should allocate 0 bytes, got " + allocPool);

        // Pattern matching equivalence across ALL real variants + literal & regex patterns
        List<String> patterns = List.of(
                "monarch", "painted_lady", "painted", "luna_moth", "cabbage_white",
                ".*", "[a-z_]+", "(monarch|atlas)", "monarch|cabbage_white", "foo.bar",
                "^mon.*", ".*moth$", "Monarch", "", "a+b", "common_blue"
        );
        for (ButterflyVariant v : first) {
            String id = v.id();
            for (String p : patterns) {
                assertEquals(id.matches(p), LongwingsVariantPerf.matches(id, p),
                        "Mismatch on id=" + id + " pattern=" + p);
            }
            // Literal self-match & miss
            assertTrue(LongwingsVariantPerf.matches(id, id), "Exact id literal must match");
            assertFalse(LongwingsVariantPerf.matches(id, id + "_x"), "Extended literal must not match");
        }
        assertFalse(LongwingsVariantPerf.matches(null, "monarch"), "Null candidate must return false");

        // Zero allocation on literal pattern match
        for (int i = 0; i < 1000; i++) LongwingsVariantPerf.matches("monarch", "painted_lady");
        long allocLiteral = measureAllocBytes(() -> {
            boolean b = false;
            for (int i = 0; i < 10_000; i++) {
                b ^= LongwingsVariantPerf.matches("monarch", (i & 1) == 0 ? "monarch" : "atlas");
            }
            if (b && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocLiteral, "Literal matches() should allocate 0 bytes, got " + allocLiteral);
    }

    private static void testLongwingsTextureCacheAndReload() {
        AtomicInteger probes = new AtomicInteger();
        java.util.function.Predicate<ResourceLocation> probe = loc -> {
            probes.incrementAndGet();
            return loc.m_135815_().contains("monarch") || loc.m_135815_().contains("luna_moth");
        };

        LongwingsClientPerf.onResourceReload(null);
        long epoch0 = LongwingsClientPerf.reloadEpoch();

        ResourceLocation monarch1 = LongwingsClientPerf.resolveWithPresenceCheck("monarch", false, probe);
        ResourceLocation monarch2 = LongwingsClientPerf.resolveWithPresenceCheck("monarch", false, probe);
        assertEquals(new ResourceLocation("longwings", "textures/entity/butterfly/monarch.png"), monarch1, "monarch texture");
        assertSame(monarch1, monarch2, "Cached texture instance should match");
        assertEquals(1, probes.get(), "Second lookup should hit cache without probing ResourceManager");

        ResourceLocation fallback = LongwingsClientPerf.resolveWithPresenceCheck("unknown_var", false, probe);
        assertEquals(new ResourceLocation("longwings", "textures/entity/butterfly/painted_lady.png"), fallback, "unknown butterfly fallback");
        LongwingsClientPerf.resolveWithPresenceCheck("unknown_var", false, probe);
        assertEquals(2, probes.get(), "Absent texture fallback should also be cached");

        ResourceLocation nullB = LongwingsClientPerf.resolveWithPresenceCheck(null, false, probe);
        assertEquals(new ResourceLocation("longwings", "textures/entity/butterfly/painted_lady.png"), nullB, "null butterfly fallback");

        ResourceLocation mothFallback = LongwingsClientPerf.resolveWithPresenceCheck("unknown_moth", true, probe);
        assertEquals(new ResourceLocation("longwings", "textures/entity/moth/small_white_wave.png"), mothFallback, "unknown moth fallback");

        // Simulate resource pack reload (F3+T)
        LongwingsClientPerf.onResourceReload(null);
        assertTrue(LongwingsClientPerf.reloadEpoch() > epoch0, "Reload must advance epoch");
        int beforeReloadHit = probes.get();
        LongwingsClientPerf.resolveWithPresenceCheck("monarch", false, probe);
        assertEquals(beforeReloadHit + 1, probes.get(), "Reload must invalidate cache and re-probe");
    }

    private static void testParticleRainMathAndZeroAlloc() {
        ParticleRainClientPerf.Scratch scratch = ParticleRainClientPerf.scratch();
        float[][] positions = {
            {0.0f, 0.0f, 0.0f}, {1.25f, -3.5f, 4.75f}, {-12.0f, 8.2f, -0.3f}, {0.01f, 0.01f, -0.01f}
        };
        float[][] velocities = {
            {0.0f, 0.0f, 0.0f}, {0.0f, -1.0f, 0.0f}, {0.25f, -0.85f, -0.15f}, {1.0e-12f, 0.0f, 0.0f}, {-0.6f, 0.1f, 0.4f}
        };
        float[] rolls = {0.0f, 0.45f, -1.2f};
        float[] partialTicks = {0.0f, 0.37f, 1.0f};

        RecordingVertexConsumer expectedVc = new RecordingVertexConsumer();
        RecordingVertexConsumer actualVc = new RecordingVertexConsumer();

        for (float[] pos : positions) {
            float x = pos[0], y = pos[1], z = pos[2];
            for (float roll : rolls) {
                for (float pt : partialTicks) {
                    float oRoll = roll * 0.5f;

                    // 1. VerticalQuad
                    expectedVc.reset();
                    actualVc.reset();
                    refVerticalQuad(expectedVc, x, y, z, oRoll, roll, pt, 0.15f, 0.1f, 0.5f, 0.25f, 0.75f, 0.2f, 0.4f, 0.6f, 0.8f, 15728880);
                    sVerticalQuad(actualVc, scratch, x, y, z, oRoll, roll, pt, 0.15f, 0.1f, 0.5f, 0.25f, 0.75f, 0.2f, 0.4f, 0.6f, 0.8f, 15728880);
                    assertVerticesBitIdentical(expectedVc, actualVc, "VerticalQuad");

                    // 2. CameraCopyQuad
                    Quaternionf camRot = new Quaternionf().rotationXYZ(0.3f, -1.1f, 0.2f);
                    expectedVc.reset();
                    actualVc.reset();
                    refCameraCopyQuad(expectedVc, camRot, x, y, z, oRoll, roll, pt, 0.15f, 0.1f, 0.5f, 0.25f, 0.75f, 0.2f, 0.4f, 0.6f, 0.8f, 240);
                    sCameraCopyQuad(actualVc, scratch, camRot, x, y, z, oRoll, roll, pt, 0.15f, 0.1f, 0.5f, 0.25f, 0.75f, 0.2f, 0.4f, 0.6f, 0.8f, 240);
                    assertVerticesBitIdentical(expectedVc, actualVc, "CameraCopyQuad");

                    // 3. HorizontalQuad
                    expectedVc.reset();
                    actualVc.reset();
                    refHorizontalQuad(expectedVc, x, y, z, oRoll, roll, pt, 0.2f, 0.1f, 0.5f, 0.25f, 0.75f, 0.9f, 0.8f, 0.7f, 0.6f, 15728640);
                    sHorizontalQuad(actualVc, scratch, x, y, z, oRoll, roll, pt, 0.2f, 0.1f, 0.5f, 0.25f, 0.75f, 0.9f, 0.8f, 0.7f, 0.6f, 15728640);
                    assertVerticesBitIdentical(expectedVc, actualVc, "HorizontalQuad");

                    // 4. VelocityQuad (with & without collision anim)
                    for (float[] velArr : velocities) {
                        Vector3f vel = new Vector3f(velArr[0], velArr[1], velArr[2]);
                        for (boolean doAnim : new boolean[]{false, true}) {
                            expectedVc.reset();
                            actualVc.reset();
                            refVelocityQuad(expectedVc, vel, x, y, z, doAnim, pt, 0.1f, 0.35f, 0.25f, 0.1f, 0.5f, 0.25f, 0.75f, 1f, 1f, 1f, 1f, 15728880);
                            sVelocityQuad(actualVc, scratch, vel, x, y, z, doAnim, pt, 0.1f, 0.35f, 0.25f, 0.1f, 0.5f, 0.25f, 0.75f, 1f, 1f, 1f, 1f, 15728880);
                            assertVerticesBitIdentical(expectedVc, actualVc, "VelocityQuad");
                        }
                    }
                }
            }
        }

        // Zero-allocation check on hot quad loops
        RecordingVertexConsumer sinkVc = new RecordingVertexConsumer();
        Vector3f testVel = new Vector3f(0.25f, -0.9f, 0.1f);
        Quaternionf testCam = new Quaternionf().rotationY(0.7f);
        for (int i = 0; i < 1000; i++) {
            sinkVc.reset();
            sVerticalQuad(sinkVc, scratch, 1.5f, 2.5f, -3.5f, 0.1f, 0.2f, 0.5f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
            sinkVc.reset();
            sVelocityQuad(sinkVc, scratch, testVel, 1.5f, 2.5f, -3.5f, true, 0.5f, 0.2f, 0.4f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
            sinkVc.reset();
            sCameraCopyQuad(sinkVc, scratch, testCam, 1.5f, 2.5f, -3.5f, 0, 0, 0.5f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
        }
        long allocQuads = measureAllocBytes(() -> {
            for (int i = 0; i < 5000; i++) {
                sinkVc.reset();
                sVerticalQuad(sinkVc, scratch, 1.5f, 2.5f, -3.5f, 0.1f, 0.2f, 0.5f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
                sinkVc.reset();
                sVelocityQuad(sinkVc, scratch, testVel, 1.5f, 2.5f, -3.5f, true, 0.5f, 0.2f, 0.4f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
                sinkVc.reset();
                sCameraCopyQuad(sinkVc, scratch, testCam, 1.5f, 2.5f, -3.5f, 0, 0, 0.5f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
                sinkVc.reset();
                sHorizontalQuad(sinkVc, scratch, 1.5f, 2.5f, -3.5f, 0.1f, 0.2f, 0.5f, 0.1f, 0, 1, 0, 1, 1, 1, 1, 1, 240);
            }
        });
        assertEquals(0L, allocQuads, "ParticleRain quad emission must allocate 0 bytes, got " + allocQuads);

        float[] windCoords = {-125.5f, -1.25f, 0.0f, 17.75f, 4096.25f};
        float[] windTimes = {0.0f, 12.5f, 2500.75f};
        for (float coord : windCoords) {
            for (float time : windTimes) {
                float expected = -(Mth.m_14031_(coord * 0.05f + time)
                        * Mth.m_14031_(coord * 0.05f * 0.5f + time * 0.5f) * 0.4f + 0.4f);
                float actual = ParticleRainClientPerf.computeOneWind(coord, 0.05f, time, 0.4f);
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
                        "computeOneWind mismatch coord=" + coord + " time=" + time);
            }
        }
    }

    private static void testSereneSeasonsHelpers() throws Exception {
        // 1. Whitelist checks
        java.lang.reflect.Method createKey = ResourceKey.class.getDeclaredMethod(
                "m_135790_", ResourceLocation.class, ResourceLocation.class);
        createKey.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Registry<Level>> levelReg = (ResourceKey<Registry<Level>>) createKey.invoke(
                null, new ResourceLocation("minecraft", "root"), new ResourceLocation("minecraft", "dimension"));
        ResourceKey<Level> overworld = ResourceKey.m_135785_(levelReg, new ResourceLocation("minecraft", "overworld"));
        ResourceKey<Level> nether = ResourceKey.m_135785_(levelReg, new ResourceLocation("minecraft", "the_nether"));
        ResourceKey<Level> twilight = ResourceKey.m_135785_(levelReg, new ResourceLocation("twilightforest", "twilight_forest"));

        List<String> arrayList = new ArrayList<>(Arrays.asList("minecraft:over", "minecraft:overworldx", "minecraft:overworld"));
        List<String> linked = new LinkedList<>(Arrays.asList(null, "twilightforest:twilight_forest"));

        assertTrue(SeasonPerf.isDimensionWhitelisted(arrayList, overworld), "Overworld should be whitelisted");
        assertFalse(SeasonPerf.isDimensionWhitelisted(arrayList, nether), "Nether should not be whitelisted");
        assertTrue(SeasonPerf.isDimensionWhitelisted(linked, twilight), "LinkedList non-RandomAccess must work");
        assertFalse(SeasonPerf.isDimensionWhitelisted(null, overworld), "Null list must return false");

        // Prefix trap check & live in-place mutation visibility
        List<String> mutable = new ArrayList<>(Arrays.asList("minecraft:overworld_custom"));
        assertFalse(SeasonPerf.isDimensionWhitelisted(mutable, overworld), "Prefix trap must not match");
        mutable.add("minecraft:overworld");
        assertTrue(SeasonPerf.isDimensionWhitelisted(mutable, overworld), "In-place add must be visible");
        mutable.remove("minecraft:overworld");
        assertFalse(SeasonPerf.isDimensionWhitelisted(mutable, overworld), "In-place remove must be visible");

        for (int i = 0; i < 1000; i++) SeasonPerf.isDimensionWhitelisted(arrayList, overworld);
        long allocWl = measureAllocBytes(() -> {
            boolean b = false;
            for (int i = 0; i < 10_000; i++) {
                b ^= SeasonPerf.isDimensionWhitelisted(arrayList, (i & 1) == 0 ? overworld : nether);
            }
            if (b && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocWl, "isDimensionWhitelisted must allocate 0 bytes, got " + allocWl);

        // 2. SeasonTime ring cache checks
        assertSame(SeasonTime.ZERO, SeasonPerf.seasonState(0), "Tick 0 must return SeasonTime.ZERO");
        assertThrows(IllegalArgumentException.class, () -> SeasonPerf.seasonState(-1));
        SeasonTime stA = SeasonPerf.seasonState(12345);
        assertEquals(12345, stA.time, "Exact tick preserved");
        assertSame(stA, SeasonPerf.seasonState(12345), "Ring hit must return cached SeasonTime");

        // Force ring-slot collision and verify no cross-contamination of tick value
        int collidingTick = -1;
        int slotA = (12345 * 0x9E3779B9) >>> 26;
        for (int cand = 1; cand < 1000; cand++) {
            if (((cand * 0x9E3779B9) >>> 26) == slotA && cand != 12345) {
                collidingTick = cand;
                break;
            }
        }
        assertTrue(collidingTick > 0, "Expected ring collision candidate");
        SeasonTime stB = SeasonPerf.seasonState(collidingTick);
        assertEquals(collidingTick, stB.time, "Colliding slot must overwrite and return exact tick");
        assertEquals(12345, SeasonPerf.seasonState(12345).time, "Re-querying old tick must return exact tick");

        for (int i = 0; i < 1000; i++) SeasonPerf.seasonState(12345);
        long allocSt = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 10_000; i++) acc += SeasonPerf.seasonState(12345).time;
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocSt, "seasonState ring hit must allocate 0 bytes, got " + allocSt);

        // 3. ColorOverride Multimap snapshot ordering & invalidation
        SeasonColorPerf.clearCachesForTests();
        HashMultimap<SeasonColorHandlers.ResolverType, SeasonColorHandlers.ColorOverride> overrides = HashMultimap.create();
        SeasonColorHandlers.ColorOverride g1 = (o, s, cur, b, x, z) -> cur + 1;
        SeasonColorHandlers.ColorOverride g2 = (o, s, cur, b, x, z) -> cur + 2;
        SeasonColorHandlers.ColorOverride f1 = (o, s, cur, b, x, z) -> cur + 3;
        overrides.put(SeasonColorHandlers.ResolverType.GRASS, g1);
        overrides.put(SeasonColorHandlers.ResolverType.FOLIAGE, f1);
        SeasonColorPerf.onOverrideRegistered();

        SeasonColorHandlers.ColorOverride[] grassSnap1 = SeasonColorPerf.getOverridesSnapshot(
                SeasonColorHandlers.ResolverType.GRASS, overrides);
        SeasonColorHandlers.ColorOverride[] foliageSnap1 = SeasonColorPerf.getOverridesSnapshot(
                SeasonColorHandlers.ResolverType.FOLIAGE, overrides);
        assertEquals(1, grassSnap1.length, "Grass snapshot size 1");
        assertSame(g1, grassSnap1[0], "Grass element 0");
        assertSame(f1, foliageSnap1[0], "Foliage element 0");

        overrides.put(SeasonColorHandlers.ResolverType.GRASS, g2);
        SeasonColorPerf.onOverrideRegistered();
        SeasonColorHandlers.ColorOverride[] grassSnap2 = SeasonColorPerf.getOverridesSnapshot(
                SeasonColorHandlers.ResolverType.GRASS, overrides);
        assertEquals(2, grassSnap2.length, "Grass snapshot updated after registration");
        assertEquals(List.copyOf(overrides.get(SeasonColorHandlers.ResolverType.GRASS)), Arrays.asList(grassSnap2),
                "Snapshot must match HashMultimap iteration order");

        for (int i = 0; i < 500; i++) SeasonColorPerf.getOverridesSnapshot(SeasonColorHandlers.ResolverType.GRASS, overrides);
        long allocOv = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 10_000; i++) {
                acc += SeasonColorPerf.getOverridesSnapshot(SeasonColorHandlers.ResolverType.GRASS, overrides).length;
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocOv, "getOverridesSnapshot hit must allocate 0 bytes, got " + allocOv);

        // 4. Bit-exact SeasonColorUtil replacement equivalence & zero allocation
        int[] sampleColors = {
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF,
            0x7FBF3F, 0x3A6EA5, 0xFF8010, 0x808080, 0x597D2F,
            0x123456, 0xABCDEF, 0x8001FE, 0xFE8001, 0x01FE80
        };
        float[] satMults = {0.0f, 0.35f, 0.75f, 1.0f, 1.25f, 1.6f, 2.0f};
        for (int c1 : sampleColors) {
            for (int c2 : sampleColors) {
                assertEquals(SeasonColorUtil.overlayBlend(c1, c2), SeasonColorPerf.overlayBlend(c1, c2),
                        "overlayBlend mismatch " + c1 + " / " + c2);
                assertEquals(SeasonColorUtil.mixColours(c1, c2, 0.75f), SeasonColorPerf.mixColours(c1, c2, 0.75f),
                        "mixColours mismatch " + c1 + " / " + c2);
            }
            for (float mult : satMults) {
                assertEquals(SeasonColorUtil.saturateColour(c1, mult), SeasonColorPerf.saturateColour(c1, mult),
                        "saturateColour mismatch c=" + c1 + " mult=" + mult);
            }
        }
        long allocColor = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 10_000; i++) {
                int blended = SeasonColorPerf.overlayBlend(0x597D2F, 0x7FBF3F);
                int mixed = SeasonColorPerf.mixColours(blended, 0x597D2F, 0.75f);
                acc ^= SeasonColorPerf.saturateColour(mixed, 1.25f);
            }
            if (acc == 0x13579BDF && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocColor, "SeasonColorPerf color math must allocate 0 bytes, got " + allocColor);
    }

    private static void testAmbientSoundsWildcards() {
        AmbientSoundsClientPerf.clearCachesForTests();
        List<String> defs = List.of(
                "plains", "*", "**", "minecraft:*", "*forest*", "*jungle*",
                "terralith:alpha_*", "minecraft:dark_*", "dark*forest",
                "#*overworld*", "#forge:is_wet", "#minecraft:is_deep_ocean",
                "minecraft:birch[._]forest", "foo+bar", "(taiga|spruce)", ""
        );
        List<String> texts = List.of(
                "minecraft:plains", "minecraft:dark_forest", "minecraft:bamboo_jungle",
                "minecraft:birch_forest", "minecraft:birch.forest", "minecraft:is_overworld",
                "forge:is_wet", "minecraft:is_deep_ocean", "terralith:alpha_islands",
                "biomesoplenty:cherry_blossom_grove", "minecraft:taiga", "minecraft:spruce",
                "minecraft:forest_edge", "foo+bar", "fooooobar"
        );
        for (String def : defs) {
            AmbientSoundsClientPerf.BiomeRule rule = AmbientSoundsClientPerf.getRule(def);
            String body = def.startsWith("#") ? def.substring(1) : def;
            String origRegex = ".*" + body.replace("*", ".*") + ".*";
            for (String text : texts) {
                boolean expected = Pattern.compile(origRegex).matcher(text).matches();
                boolean actual = rule.matchesText(text);
                assertEquals(expected, actual, "AmbientSounds mismatch def=" + def + " text=" + text);
            }
        }

        // Cache hit & zero-alloc on Location strings + plain/glob BiomeRule matching
        ResourceLocation sampleLoc = new ResourceLocation("minecraft", "dark_forest");
        AmbientSoundsClientPerf.cachedLocationString(sampleLoc);
        for (int i = 0; i < 1000; i++) {
            AmbientSoundsClientPerf.cachedLocationString(sampleLoc);
            AmbientSoundsClientPerf.getRule("*forest*").matchesText("minecraft:dark_forest");
            AmbientSoundsClientPerf.getRule("plains").matchesText("minecraft:dark_forest");
        }
        long allocAs = measureAllocBytes(() -> {
            boolean b = false;
            for (int i = 0; i < 10_000; i++) {
                String loc = AmbientSoundsClientPerf.cachedLocationString(sampleLoc);
                b ^= AmbientSoundsClientPerf.getRule("*forest*").matchesText(loc);
                b ^= AmbientSoundsClientPerf.getRule("plains").matchesText(loc);
            }
            if (b && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocAs, "AmbientSounds plain & glob cache path must allocate 0 bytes, got " + allocAs);

        Direction[] expectedDirs = Direction.values();
        assertEquals(expectedDirs.length, AmbientSoundsClientPerf.DIRECTIONS.length, "DIRECTIONS length mismatch");
        for (int i = 0; i < expectedDirs.length; i++) {
            assertSame(expectedDirs[i], AmbientSoundsClientPerf.DIRECTIONS[i], "DIRECTIONS[" + i + "] mismatch");
        }
    }

    private static void testIrisDimensionCanonicalization() throws Exception {
        IrisDimensionPerf.clearCachesForTests();
        assertSame(DimensionId.OVERWORLD, IrisDimensionPerf.canonicalDimensionId(null),
                "Null dimension key must canonicalize to DimensionId.OVERWORLD");

        java.lang.reflect.Method createKey = ResourceKey.class.getDeclaredMethod(
                "m_135790_", ResourceLocation.class, ResourceLocation.class);
        createKey.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Registry<Level>> levelReg = (ResourceKey<Registry<Level>>) createKey.invoke(
                null, new ResourceLocation("minecraft", "root"), new ResourceLocation("minecraft", "dimension"));
        ResourceKey<Level> overworld = ResourceKey.m_135785_(levelReg, new ResourceLocation("minecraft", "overworld"));
        ResourceKey<Level> nether = ResourceKey.m_135785_(levelReg, new ResourceLocation("minecraft", "the_nether"));
        ResourceKey<Level> end = ResourceKey.m_135785_(levelReg, new ResourceLocation("minecraft", "the_end"));
        assertSame(DimensionId.OVERWORLD, IrisDimensionPerf.canonicalDimensionId(overworld),
                "minecraft:overworld must canonicalize to DimensionId.OVERWORLD");
        assertSame(DimensionId.NETHER, IrisDimensionPerf.canonicalDimensionId(nether),
                "minecraft:the_nether must canonicalize to DimensionId.NETHER");
        assertSame(DimensionId.END, IrisDimensionPerf.canonicalDimensionId(end),
                "minecraft:the_end must canonicalize to DimensionId.END");

        // Fresh ResourceKey instance with distinct object identity must still canonicalize to DimensionId.OVERWORLD
        @SuppressWarnings("unchecked")
        ResourceKey<Level> distinctOverworld = (ResourceKey<Level>) createKey.invoke(
                null, new ResourceLocation("minecraft", "dimension"), new ResourceLocation("minecraft", "overworld"));
        assertSame(DimensionId.OVERWORLD, IrisDimensionPerf.canonicalDimensionId(distinctOverworld),
                "Distinct ResourceKey for minecraft:overworld must return DimensionId.OVERWORLD by identity");

        ResourceKey<Level> twilight = ResourceKey.m_135785_(levelReg, new ResourceLocation("twilightforest", "twilight_forest"));
        NamespacedId tf1 = IrisDimensionPerf.canonicalDimensionId(twilight);
        NamespacedId tf2 = IrisDimensionPerf.canonicalDimensionId(twilight);
        assertSame(tf1, tf2, "Repeated custom dimension lookups must return identical NamespacedId");
        assertEquals("twilightforest", tf1.getNamespace(), "Custom dimension namespace");
        assertEquals("twilight_forest", tf1.getName(), "Custom dimension path");

        for (int i = 0; i < 1000; i++) {
            IrisDimensionPerf.canonicalDimensionId(overworld);
            IrisDimensionPerf.canonicalDimensionId(twilight);
        }
        long allocDim = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 10_000; i++) {
                NamespacedId id = IrisDimensionPerf.canonicalDimensionId((i & 1) == 0 ? overworld : twilight);
                if (id == DimensionId.OVERWORLD) acc++;
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocDim, "IrisDimensionPerf cached lookup must allocate 0 bytes, got " + allocDim);
    }

    private static void testJeiSortingPerf() {
        List<String> smallOrder = Arrays.asList("dup", "beta", "alpha", "dup");
        List<String> smallItems = new ArrayList<>(Arrays.asList("zeta", "alpha", "dup", "missing", "beta"));
        List<String> smallExpected = new ArrayList<>(smallItems);
        smallExpected.sort(Comparator.comparingInt(v -> JeiSortingPerf.indexOfSort(smallOrder.indexOf(v))));
        smallItems.sort(JeiSortingPerf.comparator(smallOrder, Function.identity()));
        assertEquals(smallExpected, smallItems, "Linear threshold comparator order mismatch");

        List<String> largeOrder = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            largeOrder.add("k_" + i);
        }
        largeOrder.add("k_5"); // duplicate later entry must not overwrite earliest index 5
        largeOrder.add(null);

        List<String> actual = new ArrayList<>();
        for (int i = 70; i >= 0; i--) {
            actual.add("k_" + i);
        }
        actual.add("k_5");
        actual.add("extra_a");
        actual.add(null);
        actual.add("extra_b");

        List<String> expected = new ArrayList<>(actual);
        Comparator<String> refBase = Comparator.comparingInt(v -> JeiSortingPerf.indexOfSort(largeOrder.indexOf(v)));
        expected.sort(refBase);
        actual.sort(JeiSortingPerf.comparator(largeOrder, Function.identity()));
        assertEquals(expected, actual, "Large hash comparator must match List.indexOf ordering");

        List<String> actualFile = new ArrayList<>(Arrays.asList("z", "b", "k_20", "a", "k_1", "k_5"));
        List<String> expectedFile = new ArrayList<>(actualFile);
        expectedFile.sort(refBase.thenComparing(Comparator.naturalOrder()));
        actualFile.sort(JeiSortingPerf.fileOrderThenDefault(Comparator.naturalOrder(), largeOrder));
        assertEquals(expectedFile, actualFile, "fileOrderThenDefault must preserve tie-breaker order");
    }

    private static void testArea1BlockOcclusionAndModelShaperPatches() throws Exception {
        int g0 = BlockOcclusionPerf.generation;
        assertTrue(g0 > 0, "BlockOcclusionPerf initial generation must be non-zero");
        BlockOcclusionPerf.incrementGeneration();
        int g1 = BlockOcclusionPerf.generation;
        assertTrue(g1 > 0 && g1 != g0, "BlockOcclusionPerf generation must advance");
        BlockOcclusionPerf.generation = 0x3FFFFFFF;
        BlockOcclusionPerf.incrementGeneration();
        assertEquals(1, BlockOcclusionPerf.generation, "Generation wraparound must skip zero");
        BlockOcclusionPerf.generation = g1;

        assertFalse(EntryPostApplyPatches.patchBlockOcclusion(null), "Null ClassNode must fail closed");
        ClassNode badOcc = new ClassNode();
        badOcc.version = Opcodes.V17;
        badOcc.access = Opcodes.ACC_PUBLIC;
        badOcc.name = "dev/sunlit/bettersunlit/tests/perf/BadOccState";
        badOcc.superName = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        assertFalse(EntryPostApplyPatches.patchBlockOcclusion(badOcc), "Missing m_60815_ must fail closed");

        ClassNode synOcc = new ClassNode();
        synOcc.version = Opcodes.V17;
        synOcc.access = Opcodes.ACC_PUBLIC;
        synOcc.name = "dev/sunlit/bettersunlit/tests/perf/DynOccState";
        synOcc.superName = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        synOcc.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "f_60601_", "Z", null, null));
        synOcc.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, "sunlit$occCache", "I", null, null));
        MethodNode m60815 = new MethodNode(Opcodes.ACC_PUBLIC, "m_60815_", "()Z", null, null);
        String cirDesc = "(L" + EntryPostApplyPatches.CIR_INTERNAL + ";)V";
        m60815.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m60815.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m60815.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, synOcc.name, "handler$zz1$customOcclusion", cirDesc, false));
        m60815.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m60815.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m60815.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, synOcc.name, "handler$zz2$pfoliage_canOcclude", cirDesc, false));
        m60815.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m60815.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, synOcc.name, "f_60601_", "Z"));
        m60815.instructions.add(new InsnNode(Opcodes.IRETURN));
        synOcc.methods.add(m60815);
        synOcc.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "handler$zz1$customOcclusion", cirDesc, null, null));
        synOcc.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "handler$zz2$pfoliage_canOcclude", cirDesc, null, null));
        for (MethodNode stub : Arrays.asList(synOcc.methods.get(1), synOcc.methods.get(2))) {
            stub.instructions.add(new InsnNode(Opcodes.RETURN));
            stub.maxStack = 1;
            stub.maxLocals = 2;
        }
        assertTrue(EntryPostApplyPatches.patchBlockOcclusion(synOcc), "Valid occlusion structure must patch");
        assertTrue(EntryPostApplyPatches.patchBlockOcclusion(synOcc), "Idempotent occlusion re-patch must succeed");
        ClassWriter occWriter = new ClassWriter(0);
        synOcc.accept(occWriter);
        verifyBytecode(synOcc.name.replace('/', '.'), occWriter.toByteArray());

        // Unexpected third-party callback must abort fail-closed without mutating instructions
        ClassNode unexpectedOcc = new ClassNode();
        unexpectedOcc.version = Opcodes.V17;
        unexpectedOcc.access = Opcodes.ACC_PUBLIC;
        unexpectedOcc.name = "dev/sunlit/bettersunlit/tests/perf/UnexpectedOccState";
        unexpectedOcc.superName = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
        unexpectedOcc.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "f_60601_", "Z", null, null));
        unexpectedOcc.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, "sunlit$occCache", "I", null, null));
        MethodNode badMethod = new MethodNode(Opcodes.ACC_PUBLIC, "m_60815_", "()Z", null, null);
        badMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, unexpectedOcc.name, "otherModHook", "()Z", false));
        badMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        unexpectedOcc.methods.add(badMethod);
        int insnsBefore = badMethod.instructions.size();
        assertFalse(EntryPostApplyPatches.patchBlockOcclusion(unexpectedOcc), "Unexpected call must abort fail-closed");
        assertEquals(insnsBefore, badMethod.instructions.size(), "Aborted patch must leave instructions untouched");

        // BlockModelShaper OBE CIR guard patch + verification
        assertFalse(ClientPostApplyPatches.patchBlockModelShaper(null), "Null BlockModelShaper must fail closed");
        ClassNode synShaper = new ClassNode();
        synShaper.version = Opcodes.V17;
        synShaper.access = Opcodes.ACC_PUBLIC;
        synShaper.name = "dev/sunlit/bettersunlit/tests/perf/DynModelShaper";
        synShaper.superName = "net/minecraft/client/renderer/block/BlockModelShaper";
        String stateDesc = "Lnet/minecraft/world/level/block/state/BlockState;";
        String bakedDesc = "Lnet/minecraft/client/resources/model/BakedModel;";
        String getModelDesc = "(" + stateDesc + ")" + bakedDesc;
        String obeCbDesc = "(" + stateDesc + "L" + EntryPostApplyPatches.CIR_INTERNAL + ";)V";
        MethodNode m110893 = new MethodNode(Opcodes.ACC_PUBLIC, "m_110893_", getModelDesc, null, null);
        LabelNode lAfterObe = new LabelNode();
        m110893.instructions.add(new TypeInsnNode(Opcodes.NEW, EntryPostApplyPatches.CIR_INTERNAL));
        m110893.instructions.add(new InsnNode(Opcodes.DUP));
        m110893.instructions.add(new LdcInsnNode("m_110893_"));
        m110893.instructions.add(new InsnNode(Opcodes.ICONST_1));
        m110893.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, EntryPostApplyPatches.CIR_INTERNAL, "<init>", "(Ljava/lang/String;Z)V", false));
        m110893.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        m110893.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m110893.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m110893.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        m110893.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, synShaper.name, "handler$zz1$obe$getBlockStateModel", obeCbDesc, false));
        m110893.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        m110893.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EntryPostApplyPatches.CIR_INTERNAL, "isCancelled", "()Z", false));
        m110893.instructions.add(new JumpInsnNode(Opcodes.IFEQ, lAfterObe));
        m110893.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        m110893.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EntryPostApplyPatches.CIR_INTERNAL, "getReturnValue", "()Ljava/lang/Object;", false));
        m110893.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/client/resources/model/BakedModel"));
        m110893.instructions.add(new InsnNode(Opcodes.ARETURN));
        m110893.instructions.add(lAfterObe);
        m110893.instructions.add(new FrameNode(Opcodes.F_FULL, 3,
                new Object[] { synShaper.name, "net/minecraft/world/level/block/state/BlockState", EntryPostApplyPatches.CIR_INTERNAL },
                0, new Object[0]));
        m110893.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m110893.instructions.add(new InsnNode(Opcodes.ARETURN));
        m110893.maxStack = 4;
        m110893.maxLocals = 3;
        synShaper.methods.add(m110893);
        MethodNode obeStub = new MethodNode(Opcodes.ACC_PRIVATE, "handler$zz1$obe$getBlockStateModel", obeCbDesc, null, null);
        obeStub.instructions.add(new InsnNode(Opcodes.RETURN));
        obeStub.maxStack = 1;
        obeStub.maxLocals = 3;
        synShaper.methods.add(obeStub);
        assertTrue(ClientPostApplyPatches.patchBlockModelShaper(synShaper), "OBE BlockModelShaper guard must patch");
        assertTrue(ClientPostApplyPatches.patchBlockModelShaper(synShaper), "OBE BlockModelShaper re-patch must be idempotent");
        ClassWriter shaperWriter = new ClassWriter(0);
        synShaper.accept(shaperWriter);
        verifyBytecode(synShaper.name.replace('/', '.'), shaperWriter.toByteArray());
    }

    private static void testArea2EmfRenderPerfAndPatches() throws Exception {
        Set<Direction> allDirs = EnumSet.allOf(Direction.class);
        ModelPart.Cube cubeNormal = new ModelPart.Cube(0, 0, -2.0f, -3.5f, 1.0f, 5.0f, 7.0f, 4.0f,
                0.25f, 0.5f, 0.75f, false, 64.0f, 64.0f, allDirs);
        ModelPart.Cube cubeMirror = new ModelPart.Cube(8, 12, 1.0f, 0.0f, -4.0f, 6.0f, 3.0f, 5.0f,
                0.0f, 0.0f, 0.0f, true, 32.0f, 64.0f, allDirs);
        Field polyField = ModelPart.Cube.class.getDeclaredField("f_104341_");
        polyField.setAccessible(true);
        Object[] polysNormal = (Object[]) polyField.get(cubeNormal);
        Object[] polysMirror = (Object[]) polyField.get(cubeMirror);

        PoseStack stack = new PoseStack();
        PoseStack.Pose[] poses = new PoseStack.Pose[4];
        poses[0] = stack.m_85850_();
        stack.m_85836_();
        stack.m_85850_().m_252922_().translate(1.5f, -2.25f, 0.75f).rotateXYZ(0.3f, -0.9f, 1.2f);
        stack.m_85850_().m_252943_().rotateXYZ(0.3f, -0.9f, 1.2f);
        poses[1] = stack.m_85850_();
        stack.m_85836_();
        stack.m_85850_().m_252922_().scale(1.25f, -0.75f, 2.0f).rotateY(0.75f);
        stack.m_85850_().m_252943_().scale(1.0f, -1.0f, 1.0f).rotateY(0.75f);
        poses[2] = stack.m_85850_();
        stack.m_85836_();
        stack.m_85850_().m_252922_().translate(-12.5f, 48.125f, -0.0625f).rotateYXZ(2.1f, -0.45f, 0.15f);
        stack.m_85850_().m_252943_().rotateYXZ(2.1f, -0.45f, 0.15f);
        poses[3] = stack.m_85850_();

        RecordingVertexConsumer expectedVc = new RecordingVertexConsumer();
        RecordingVertexConsumer actualVc = new RecordingVertexConsumer();
        for (int p = 0; p < poses.length; p++) {
            PoseStack.Pose pose = poses[p];
            expectedVc.reset();
            actualVc.reset();
            cubeNormal.m_171332_(pose, expectedVc, 15728880, 655360, 0.9f, 0.8f, 0.7f, 0.95f);
            EmfRenderPerf.compilePolygons(polysNormal, pose, actualVc, 15728880, 655360, 0.9f, 0.8f, 0.7f, 0.95f);
            assertVerticesBitIdentical(expectedVc, actualVc, "ModelPart.Cube normal pose " + p);

            expectedVc.reset();
            actualVc.reset();
            cubeMirror.m_171332_(pose, expectedVc, 240, 0, 1.0f, 0.5f, 0.25f, 1.0f);
            EmfRenderPerf.compilePolygons(polysMirror, pose, actualVc, 240, 0, 1.0f, 0.5f, 0.25f, 1.0f);
            assertVerticesBitIdentical(expectedVc, actualVc, "ModelPart.Cube mirror pose " + p);
        }

        // Zero-allocation check for EmfRenderPerf.compilePolygons
        RecordingVertexConsumer sinkVc = new RecordingVertexConsumer();
        PoseStack.Pose hotPose = poses[1];
        for (int i = 0; i < 1000; i++) {
            sinkVc.reset();
            EmfRenderPerf.compilePolygons(polysNormal, hotPose, sinkVc, 240, 655360, 1f, 1f, 1f, 1f);
        }
        long allocCube = measureAllocBytes(() -> {
            for (int i = 0; i < 2000; i++) {
                sinkVc.reset();
                EmfRenderPerf.compilePolygons(polysNormal, hotPose, sinkVc, 240, 655360, 1f, 1f, 1f, 1f);
            }
        });
        assertEquals(0L, allocCube, "EmfRenderPerf.compilePolygons must allocate 0 bytes, got " + allocCube);

        // PoseStack helper safety on non-caching PoseStack
        PoseStack plainStack = new PoseStack();
        EmfRenderPerf.pushWithPoseCaching(plainStack);
        assertFalse(plainStack.m_85851_(), "pushWithPoseCaching must push PoseStack");
        EmfRenderPerf.popWithPoseCaching(plainStack);
        assertTrue(plainStack.m_85851_(), "popWithPoseCaching must pop PoseStack");

        // charIterator equivalence vs expr.chars().mapToObj(c -> (char) c).iterator()
        for (String expr : List.of("", "0", "a + b * sin(time / 20)", "x\u3042y")) {
            Iterator<Character> expectedIt = expr.chars().mapToObj(c -> (char) c).iterator();
            Iterator<Character> actualIt = EmfRenderPerf.charIterator(expr);
            while (expectedIt.hasNext()) {
                assertTrue(actualIt.hasNext(), "charIterator ended early on: " + expr);
                assertEquals(expectedIt.next(), actualIt.next(), "charIterator char mismatch on: " + expr);
            }
            assertFalse(actualIt.hasNext(), "charIterator had extra elements on: " + expr);
            assertThrows(NoSuchElementException.class, actualIt::next);
        }

        // Real MathExpressionParser.<init> post-apply ASM rewrite + JVM verification
        ClassNode mathNode = readClassNode("traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser");
        assertTrue(ClientPostApplyPatches.patchMathExpressionParser(mathNode), "patchMathExpressionParser must succeed");
        assertTrue(ClientPostApplyPatches.patchMathExpressionParser(mathNode), "patchMathExpressionParser must be idempotent");
        MethodNode mathCtor = findMethod(mathNode, "<init>(Ljava/lang/String;ZLtraben/entity_model_features/models/animation/AnimSetupContext;Z)V");
        assertNotNull(mathCtor, "MathExpressionParser ctor missing");
        assertEquals(0, countMethodInvoke(mathCtor, "Ljava/util/stream/IntStream;mapToObj(Ljava/util/function/IntFunction;)Ljava/util/stream/Stream;"),
                "IntStream.mapToObj call must be replaced");
        assertEquals(1, countMethodInvoke(mathCtor, "Ldev/sunlit/bettersunlit/client/EmfRenderPerf;charIterator(Ljava/lang/String;)Ljava/util/Iterator;"),
                "EmfRenderPerf.charIterator call must be inserted");
        ClassWriter mathWriter = new ClassWriter(0);
        mathNode.accept(mathWriter);
        verifyBytecode(mathNode.name.replace('/', '.'), mathWriter.toByteArray());

        // Real ModelPart$Cube + sunlit$polygons post-apply ASM rewrite + JVM verification
        ClassNode cubeNode = readClassNode("net.minecraft.client.model.geom.ModelPart$Cube");
        cubeNode.interfaces.add("dev/sunlit/bettersunlit/client/EmfRenderPerf$PolygonHolder");
        MethodNode polyMethod = new MethodNode(Opcodes.ACC_PUBLIC, "sunlit$polygons", "()[Ljava/lang/Object;", null, null);
        polyMethod.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        polyMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        polyMethod.maxStack = 1;
        polyMethod.maxLocals = 1;
        cubeNode.methods.add(polyMethod);
        assertTrue(ClientPostApplyPatches.patchModelPartCube(cubeNode), "patchModelPartCube must succeed");
        assertTrue(ClientPostApplyPatches.patchModelPartCube(cubeNode), "patchModelPartCube must be idempotent");
        ClassWriter cubeWriter = new ClassWriter(0);
        cubeNode.accept(cubeWriter);
        verifyBytecode(cubeNode.name.replace('/', '.'), cubeWriter.toByteArray());

        // EmfRenderPerf.matchesModelPartVariableSyntax parity + 0-alloc check
        Pattern modelPartRef = Pattern.compile("[a-zA-Z0-9_]+\\.([trs][xyz]$|visible$|visible_boxes$)");
        List<String> varSamples = new ArrayList<>(List.of(
                "", ".", ".tx", "a.", "a.t", "a.txx", "a.t x", "a.b.tx", "a.visible_box",
                "a.visible_boxes2", "body-1.rx", "\ud55c\uae00.tx", "render.tx", "render.x",
                "_0Z.rz", "head_1.visible", "wing_L2.visible_boxes", "a.tx_extra", "123.sy"
        ));
        for (char c0 : new char[] { 't', 'r', 's', 'u' }) {
            for (char c1 : new char[] { 'x', 'y', 'z', 'w' }) {
                varSamples.add("part_A9." + c0 + c1);
            }
        }
        assertFalse(EmfRenderPerf.matchesModelPartVariableSyntax(null), "null model part var must be false");
        for (String sample : varSamples) {
            assertEquals(modelPartRef.matcher(sample).matches(), EmfRenderPerf.matchesModelPartVariableSyntax(sample),
                    "Model part variable regex mismatch on: " + sample);
        }
        for (int i = 0; i < 1000; i++) {
            EmfRenderPerf.matchesModelPartVariableSyntax("head.rx");
            EmfRenderPerf.matchesModelPartVariableSyntax("wing_L.visible_boxes");
            EmfRenderPerf.matchesModelPartVariableSyntax("render.shadow_size");
        }
        long allocVarSyntax = measureAllocBytes(() -> {
            boolean acc = false;
            for (int i = 0; i < 5000; i++) {
                acc ^= EmfRenderPerf.matchesModelPartVariableSyntax("head.rx");
                acc ^= EmfRenderPerf.matchesModelPartVariableSyntax("wing_L.visible_boxes");
                acc ^= EmfRenderPerf.matchesModelPartVariableSyntax("invalid-part.tx");
            }
            if (acc && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocVarSyntax, "matchesModelPartVariableSyntax must allocate 0 bytes, got " + allocVarSyntax);
    }

    private static void testArea2GraphOrderPerf() {
        // 1. Digraph interface parity vs MapDigraph
        GraphOrderPerf.PooledRenderDigraph<String> pooled = new GraphOrderPerf.PooledRenderDigraph<>();
        MapDigraph<String> ref = new MapDigraph<>();
        for (int round = 0; round < 3; round++) {
            pooled.clear();
            ref = new MapDigraph<>();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                keys.add("node_" + i);
            }
            for (String k : keys) {
                assertEquals(ref.add(k), pooled.add(k), "add mismatch " + k);
            }
            for (int i = 0; i < 20; i++) {
                String u = keys.get(i);
                String v = keys.get((i + 3) % 20);
                String w = keys.get((i * 7) % 20);
                assertEquals(ref.put(u, v, i + 1), pooled.put(u, v, i + 1), "put u->v mismatch");
                assertEquals(ref.put(u, w, (i % 5) + 1), pooled.put(u, w, (i % 5) + 1), "put u->w mismatch");
            }
            assertEquals(ref.getVertexCount(), pooled.getVertexCount(), "vertexCount mismatch");
            assertEquals(ref.getEdgeCount(), pooled.getEdgeCount(), "edgeCount mismatch");
            for (String u : keys) {
                for (String v : keys) {
                    assertEquals(ref.get(u, v), pooled.get(u, v), "get edge mismatch " + u + "->" + v);
                }
            }
            assertEquals(ref.remove("node_4", "node_7"), pooled.remove("node_4", "node_7"), "edge remove mismatch");
            assertEquals(ref.remove("node_12"), pooled.remove("node_12"), "vertex remove mismatch");
            assertEquals(ref.getVertexCount(), pooled.getVertexCount(), "vertexCount after remove");
            assertEquals(ref.getEdgeCount(), pooled.getEdgeCount(), "edgeCount after remove");

            Digraph<String> refRev = ref.reverse();
            Digraph<String> poolRev = pooled.reverse();
            assertEquals(refRev.getVertexCount(), poolRev.getVertexCount(), "reverse vertexCount");
            assertEquals(refRev.getEdgeCount(), poolRev.getEdgeCount(), "reverse edgeCount");
            for (String u : pooled.vertices()) {
                for (String v : pooled.targets(u)) {
                    assertEquals(refRev.get(v, u), poolRev.get(v, u), "reverse edge mismatch");
                }
            }

            Set<String> subSet = Set.of("node_0", "node_1", "node_3", "node_6", "node_9");
            Digraph<String> refSub = ref.subgraph(subSet);
            Digraph<String> poolSub = pooled.subgraph(subSet);
            assertEquals(refSub.getVertexCount(), poolSub.getVertexCount(), "subgraph vertexCount");
            assertEquals(refSub.getEdgeCount(), poolSub.getEdgeCount(), "subgraph edgeCount");
        }

        // 2. Topological sort on DAG with self-loops + cross edges matches Ithaka Digraphs.toposort validity
        GraphOrderPerf.PooledRenderDigraph<String> dag = new GraphOrderPerf.PooledRenderDigraph<>();
        dag.put("A", "B", 1);
        dag.put("A", "C", 2);
        dag.put("B", "D", 3);
        dag.put("C", "D", 4);
        dag.put("C", "C", 9); // self-loop allowed
        dag.add("ISOLATED");
        List<String> dagOrder = new ArrayList<>();
        GraphOrderPerf.orderPooledGraph(dag, dagOrder);
        assertEquals(dag.getVertexCount(), dagOrder.size(), "DAG order size");
        assertTrue(dagOrder.indexOf("A") < dagOrder.indexOf("B"), "DAG order A < B");
        assertTrue(dagOrder.indexOf("A") < dagOrder.indexOf("C"), "DAG order A < C");
        assertTrue(dagOrder.indexOf("B") < dagOrder.indexOf("D"), "DAG order B < D");
        assertTrue(dagOrder.indexOf("C") < dagOrder.indexOf("D"), "DAG order C < D");

        // 3. Random cyclic graphs with multiple SCCs & unequal weights: every vertex preserved & FAS weight matches
        SimpleFeedbackArcSetProvider fasProv = new SimpleFeedbackArcSetProvider();
        Random rng = new Random(20261114L);
        for (int trial = 0; trial < 40; trial++) {
            int n = 2 + rng.nextInt(14);
            GraphOrderPerf.PooledRenderDigraph<String> pg = new GraphOrderPerf.PooledRenderDigraph<>();
            MapDigraph<String> mg = new MapDigraph<>();
            List<String> verts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String v = "v" + i;
                verts.add(v);
                pg.add(v);
                mg.add(v);
            }
            for (int e = 0; e < n * 2; e++) {
                String u = verts.get(rng.nextInt(n));
                String v = verts.get(rng.nextInt(n));
                if (u.equals(v)) continue;
                int w = 1 + rng.nextInt(20);
                pg.put(u, v, w);
                mg.put(u, v, w);
            }
            Map<String, Map<String,Integer>> originalEdges = new HashMap<>();
            for (String u : verts) {
                Map<String,Integer> row = new HashMap<>();
                for (String v : mg.targets(u)) row.put(v, mg.get(u,v).orElseThrow());
                originalEdges.put(u, row);
            }
            List<String> pOrder = new ArrayList<>(n);
            GraphOrderPerf.orderPooledGraph(pg, pOrder);
            assertEquals(n, pOrder.size(), "Cyclic trial " + trial + " vertex count");
            assertEquals(Set.copyOf(verts), Set.copyOf(pOrder), "Cyclic trial " + trial + " vertex set");

            FeedbackArcSet<String> refFas = fasProv.getFeedbackArcSet(mg, mg, FeedbackArcSetPolicy.MIN_WEIGHT);
            long refFasWeight = 0;
            for (String src : refFas.vertices()) {
                for (String dst : refFas.targets(src)) {
                    refFasWeight += mg.get(src, dst).orElse(0);
                    mg.remove(src, dst);
                }
            }
            assertTrue(mg.isAcyclic(), "Reference graph after FAS removal must be acyclic");
            Map<String, Integer> posMap = new HashMap<>();
            for (int i = 0; i < pOrder.size(); i++) {
                posMap.put(pOrder.get(i), i);
            }
            long pooledFasWeight = 0;
            for (String u : verts) {
                for (var edge : originalEdges.get(u).entrySet()) {
                    String v = edge.getKey();
                    if (!u.equals(v) && posMap.get(u) > posMap.get(v)) {
                        pooledFasWeight += edge.getValue();
                    }
                }
            }
            assertEquals(Digraphs.toposort(mg, false), pOrder, "Native MIN_WEIGHT final order trial " + trial);
            assertTrue(pooledFasWeight <= refFasWeight,
                    "Pooled FAS weight " + pooledFasWeight + " exceeded reference MIN_WEIGHT FAS " + refFasWeight + " on trial " + trial);
        }

        // Mutual 2-cycle must drop the lighter edge so heavier u->v wins
        GraphOrderPerf.PooledRenderDigraph<String> twoCycle = new GraphOrderPerf.PooledRenderDigraph<>();
        twoCycle.put("HEAVY", "LIGHT", 10);
        twoCycle.put("LIGHT", "HEAVY", 1);
        List<String> twoOrder = new ArrayList<>();
        GraphOrderPerf.orderPooledGraph(twoCycle, twoOrder);
        assertEquals(List.of("HEAVY", "LIGHT"), twoOrder, "2-cycle must preserve heavier edge direction");

        // Zero-alloc reuse of PooledRenderDigraph + out list across frames
        GraphOrderPerf.PooledRenderDigraph<String> frameGraph = new GraphOrderPerf.PooledRenderDigraph<>();
        List<String> frameOut = new ArrayList<>(8);
        String[] rt = { "t0", "t1", "t2", "t3", "t4" };
        for (int warm = 0; warm < 500; warm++) {
            frameGraph.clear();
            frameOut.clear();
            frameGraph.put(rt[0], rt[1], 2);
            frameGraph.put(rt[1], rt[2], 3);
            frameGraph.put(rt[2], rt[3], 1);
            frameGraph.put(rt[3], rt[4], 5);
            GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);
        }
        long allocGraph = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 1000; i++) {
                frameGraph.clear();
                frameOut.clear();
                frameGraph.put(rt[0], rt[1], 2);
                frameGraph.put(rt[1], rt[2], 3);
                frameGraph.put(rt[2], rt[3], 1);
                frameGraph.put(rt[3], rt[4], 5);
                GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);
                acc += frameOut.size();
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocGraph, "PooledRenderDigraph frame reuse + toposort must allocate 0 bytes, got " + allocGraph);

        // Zero-alloc 2-node and n>=3 single-mutual-pair cycles with fluctuating weights + self-loops
        for (int warm = 0; warm < 500; warm++) {
            int wA = (warm & 1) == 0 ? 10 + warm : 1;
            int wB = (warm & 1) == 0 ? 2 : 20 + warm;
            frameGraph.clear();
            frameOut.clear();
            frameGraph.put(rt[0], rt[0], 99);
            frameGraph.put(rt[0], rt[1], wA);
            frameGraph.put(rt[1], rt[1], 88);
            frameGraph.put(rt[1], rt[0], wB);
            GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);

            frameGraph.clear();
            frameOut.clear();
            frameGraph.put(rt[0], rt[1], 4);
            frameGraph.put(rt[1], rt[1], 77);
            frameGraph.put(rt[1], rt[2], wA);
            frameGraph.put(rt[2], rt[1], wB);
            frameGraph.put(rt[2], rt[3], 5);
            GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);
        }
        long allocSimpleCycle = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 1000; i++) {
                int wA = (i & 1) == 0 ? 15 + i : 3;
                int wB = (i & 1) == 0 ? 1 : 25 + i;
                frameGraph.clear();
                frameOut.clear();
                frameGraph.put(rt[0], rt[0], 99);
                frameGraph.put(rt[0], rt[1], wA);
                frameGraph.put(rt[1], rt[1], 88);
                frameGraph.put(rt[1], rt[0], wB);
                GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);
                acc += frameOut.size();

                frameGraph.clear();
                frameOut.clear();
                frameGraph.put(rt[0], rt[1], 4);
                frameGraph.put(rt[1], rt[1], 77);
                frameGraph.put(rt[1], rt[2], wA);
                frameGraph.put(rt[2], rt[1], wB);
                frameGraph.put(rt[2], rt[3], 5);
                GraphOrderPerf.orderPooledGraph(frameGraph, frameOut);
                acc += frameOut.size();
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocSimpleCycle, "Unequal 2-cycle and single-mutual-pair cycles must allocate 0 bytes, got " + allocSimpleCycle);

        // Indirect bypass path through a third vertex must decline 2-cycle fast path and match native FAS order
        MapDigraph<String> bypassRef = new MapDigraph<>();
        GraphOrderPerf.PooledRenderDigraph<String> bypassGraph = new GraphOrderPerf.PooledRenderDigraph<>();
        for (String v : List.of("v0", "v1", "v2", "v3")) {
            bypassRef.add(v);
            bypassGraph.add(v);
        }
        bypassRef.put("v1", "v2", 1);
        bypassRef.put("v2", "v1", 10);
        bypassRef.put("v2", "v3", 3);
        bypassRef.put("v3", "v1", 3);
        bypassGraph.put("v1", "v2", 1);
        bypassGraph.put("v2", "v1", 10);
        bypassGraph.put("v2", "v3", 3);
        bypassGraph.put("v3", "v1", 3);
        FeedbackArcSet<String> bypassFas = new SimpleFeedbackArcSetProvider()
                .getFeedbackArcSet(bypassRef, bypassRef, FeedbackArcSetPolicy.MIN_WEIGHT);
        for (String s : bypassFas.vertices()) {
            for (String d : bypassFas.targets(s)) {
                bypassRef.remove(s, d);
            }
        }
        List<String> bypassOut = new ArrayList<>();
        GraphOrderPerf.orderPooledGraph(bypassGraph, bypassOut);
        assertEquals(Digraphs.toposort(bypassRef, false), bypassOut,
                "Bypass path cycle must fall back to native MIN_WEIGHT FAS order");
    }

    private static void testArea3LogBegonePerf() throws Exception {
        LogBegonePerf.invalidate();
        List<String> phrases = new ArrayList<>(Arrays.asList("Ignoring unknown attribute", "OpenGL debug message", null));
        List<String> regexes = new ArrayList<>(Arrays.asList("^\\[WARN\\] Duplicate.*", "ExactOnly"));

        assertTrue(LogBegonePerf.shouldFilter(null, phrases, regexes), "null message must filter (return true)");
        assertTrue(LogBegonePerf.shouldFilter("Prefix: Ignoring unknown attribute foo", phrases, regexes), "phrase substring hit");
        assertTrue(LogBegonePerf.shouldFilter("[WARN] Duplicate model key", phrases, regexes), "regex matches hit");
        assertFalse(LogBegonePerf.shouldFilter("prefix ExactOnly suffix", phrases, regexes), "Matcher.matches() must be full-string");
        assertTrue(LogBegonePerf.shouldFilter("ExactOnly", phrases, regexes), "Full-string regex match");
        assertFalse(LogBegonePerf.shouldFilter("Normal chat message", phrases, regexes), "Unmatched log line");

        // Dynamic config mutation (size change + invalidate)
        phrases.add("Added dynamic phrase");
        assertTrue(LogBegonePerf.shouldFilter("hit Added dynamic phrase here", phrases, regexes), "Dynamic list growth invalidated snapshot");
        regexes.set(0, "^NEW_REGEX$");
        LogBegonePerf.invalidate();
        assertTrue(LogBegonePerf.shouldFilter("NEW_REGEX", phrases, regexes), "Manual invalidate picked up regex edit");
        assertFalse(LogBegonePerf.shouldFilter("[WARN] Duplicate model key", phrases, regexes), "Old regex gone after invalidate");

        // Concurrent thread safety across two threads
        AtomicInteger failures = new AtomicInteger();
        Runnable worker = () -> {
            for (int i = 0; i < 2000; i++) {
                if (!LogBegonePerf.shouldFilter("OpenGL debug message", phrases, regexes)) failures.incrementAndGet();
                if (LogBegonePerf.shouldFilter("Completely fine line", phrases, regexes)) failures.incrementAndGet();
            }
        };
        Thread t1 = new Thread(worker);
        Thread t2 = new Thread(worker);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        assertEquals(0, failures.get(), "LogBegonePerf concurrent filtering failures");

        for (int i = 0; i < 1000; i++) {
            LogBegonePerf.shouldFilter("Clean log entry 12345", phrases, regexes);
        }
        long allocLog = measureAllocBytes(() -> {
            boolean acc = false;
            for (int i = 0; i < 5000; i++) {
                acc ^= LogBegonePerf.shouldFilter("Clean log entry 12345", phrases, regexes);
            }
            if (acc && System.nanoTime() == 0) throw new AssertionError();
        });
        assertEquals(0L, allocLog, "LogBegonePerf hot path must allocate 0 bytes, got " + allocLog);
        LogBegonePerf.invalidate();
    }

    private static void testArea3EmojiRenderPerfAndPatch() throws Exception {
        // 1. Bounded cache eviction cap (<= 4096 entries)
        LoadingCache<String, Pair<String, HashMap<Integer, Emoji>>> cache = EmojiRenderPerf.createBoundedCache();
        Pair<String, HashMap<Integer, Emoji>> dummy = Pair.of("x", new HashMap<>());
        for (int i = 0; i < 5000; i++) {
            cache.put("k_" + i, dummy);
        }
        cache.cleanUp();
        assertTrue(cache.size() <= EmojiRenderPerf.MAX_CACHE_SIZE,
                "RECENT_STRINGS cache size must stay bounded <= 4096, was " + cache.size());

        // 2. EmojiIndex pre-filter & formatting-code transparency
        List<Emoji> saved = new ArrayList<>(Constants.EMOJI_LIST);
        try {
            Constants.EMOJI_LIST.clear();
            EmojiRenderPerf.invalidateIndex();
            assertFalse(EmojiRenderPerf.mightContainEmoji("142 FPS"), "Empty emoji list never triggers");

            Emoji smile = new Emoji();
            smile.strings = List.of(":smile:", ":)");
            Emoji heart = new Emoji();
            heart.strings = List.of("<3", ":heart:");
            Constants.EMOJI_LIST.add(smile);
            Constants.EMOJI_LIST.add(heart);
            EmojiRenderPerf.invalidateIndex();

            assertFalse(EmojiRenderPerf.mightContainEmoji(null), "null text");
            assertFalse(EmojiRenderPerf.mightContainEmoji(""), "empty text");
            assertFalse(EmojiRenderPerf.mightContainEmoji("142 FPS"), "plain FPS HUD string");
            assertFalse(EmojiRenderPerf.mightContainEmoji("Coords: 100, 64, -200"), "plain coords HUD");
            assertFalse(EmojiRenderPerf.mightContainEmoji("hello world without emojis"), "plain prose");
            assertTrue(EmojiRenderPerf.mightContainEmoji(":smile:"), "exact shortcode");
            assertTrue(EmojiRenderPerf.mightContainEmoji("gg :)"), "emoticon suffix");
            assertTrue(EmojiRenderPerf.mightContainEmoji("gg \u00a7a:\u00a7r)"), "formatting code split trigger");
            assertTrue(EmojiRenderPerf.mightContainEmoji("i <3 u"), "ASCII symbol emoticon");

            // FormattedCharSequence ThreadLocal extraction + zero-alloc fast reject
            FormattedCharSequence plainSeq = FormattedCharSequence.m_13714_("Coords: 100, 64", Style.f_131099_);
            FormattedCharSequence emojiSeq = FormattedCharSequence.m_13714_("gg :smile:!", Style.f_131099_);
            for (int i = 0; i < 1000; i++) {
                EmojiRenderPerf.extractIfMightContainEmoji(plainSeq);
                EmojiRenderPerf.mightContainEmoji("Coords: 100, 64");
            }
            assertEquals(null, EmojiRenderPerf.extractIfMightContainEmoji(plainSeq), "Plain seq must return null");
            assertEquals("gg :smile:!", EmojiRenderPerf.extractIfMightContainEmoji(emojiSeq), "Emoji seq candidate mismatch");

            // Nested re-entrancy test for ThreadLocal StringBuilder lease
            FormattedCharSequence reentrantOuter = sink -> {
                assertEquals("inner :heart:", EmojiRenderPerf.extractIfMightContainEmoji(
                        FormattedCharSequence.m_13714_("inner :heart:", Style.f_131099_)), "Reentrant inner extract");
                String txt = "outer :smile:";
                for (int i = 0; i < txt.length(); i++) {
                    if (!sink.m_6411_(i, Style.f_131099_, txt.charAt(i))) return false;
                }
                return true;
            };
            assertEquals("outer :smile:", EmojiRenderPerf.extractIfMightContainEmoji(reentrantOuter),
                    "Outer StringBuilder must remain uncorrupted on nested extract");

            long allocEmoji = measureAllocBytes(() -> {
                boolean acc = false;
                for (int i = 0; i < 5000; i++) {
                    acc ^= EmojiRenderPerf.extractIfMightContainEmoji(plainSeq) != null;
                    acc ^= EmojiRenderPerf.mightContainEmoji("142 FPS");
                }
                if (acc && System.nanoTime() == 0) throw new AssertionError();
            });
            assertEquals(0L, allocEmoji, "Emoji pre-filter non-emoji path must allocate 0 bytes, got " + allocEmoji);

            Emoji wordEmoji = new Emoji();
            wordEmoji.strings = List.of("kappa");
            Constants.EMOJI_LIST.add(wordEmoji);
            EmojiRenderPerf.invalidateIndex();
            assertFalse(EmojiRenderPerf.mightContainEmoji("142 FPS"), "Word-boundary emoji must keep pre-filter active");
            assertTrue(EmojiRenderPerf.mightContainEmoji("gg kappa!"), "Word-boundary emoji trigger must match");

            // Real Emojiful Regex unindexable fallback trigger test (`.` is left unescaped by EmojiUtil)
            Emoji wildcardEmoji = new Emoji();
            wildcardEmoji.strings = List.of("a.b");
            Constants.EMOJI_LIST.add(wildcardEmoji);
            EmojiRenderPerf.invalidateIndex();
            assertTrue(EmojiRenderPerf.mightContainEmoji("anything falls back safely"),
                    "Unindexable regex must fall back conservatively");

            // Korean Hangul strings must not false-trigger when non-ASCII emoji pairs are registered
            Constants.EMOJI_LIST.clear();
            Emoji unicodeEmoji = new Emoji();
            unicodeEmoji.strings = List.of("\u2764\ufe0f", "\ud83d\ude00");
            Constants.EMOJI_LIST.add(unicodeEmoji);
            Emoji asciiEmoji = new Emoji();
            asciiEmoji.strings = List.of(":smile:");
            Constants.EMOJI_LIST.add(asciiEmoji);
            EmojiRenderPerf.invalidateIndex();
            assertFalse(EmojiRenderPerf.mightContainEmoji("\uc120\ub9bf\ubc38\ub9ac \ud14c\uc2a4\ud2b8 123"),
                    "Korean Hangul must not trigger non-ASCII emoji pair filter");
            assertTrue(EmojiRenderPerf.mightContainEmoji("\uc120\ub9bf\ubc38\ub9ac \u2764\ufe0f"),
                    "Actual unicode emoji inside Hangul text must still trigger");

            // >64 emoji list rotating cursor version check + mayEmojiMatchClean pre-check
            Constants.EMOJI_LIST.clear();
            for (int i = 0; i < 80; i++) {
                Emoji e = new Emoji();
                e.strings = List.of(":e" + i + ":");
                Constants.EMOJI_LIST.add(e);
            }
            EmojiRenderPerf.invalidateIndex();
            assertTrue(EmojiRenderPerf.mightContainEmoji("gg :e40:"), "Large emoji list hit");
            assertFalse(EmojiRenderPerf.mightContainEmoji("plain text"), "Large emoji list miss");
            Constants.EMOJI_LIST.get(40).strings = List.of(":e40:", ":mutated40:");
            for (int i = 0; i < 32; i++) {
                EmojiRenderPerf.mightContainEmoji("plain text");
            }
            assertTrue(EmojiRenderPerf.mightContainEmoji("got :mutated40:!"),
                    "Rotating cursor check must detect late in-place mutation in >64 emoji lists");

            assertFalse(EmojiRenderPerf.mayEmojiMatchClean(asciiEmoji, "no trigger here"),
                    "mayEmojiMatchClean must reject clean string lacking shortcode");
            assertTrue(EmojiRenderPerf.mayEmojiMatchClean(asciiEmoji, "hi :smile:"),
                    "mayEmojiMatchClean must allow clean string containing shortcode");
            assertTrue(EmojiRenderPerf.mayEmojiMatchClean(wildcardEmoji, "anything"),
                    "mayEmojiMatchClean must fall back conservatively for unescaped regex metachars");
            Emoji customSubclass = new Emoji() { };
            customSubclass.strings = List.of(":smile:");
            assertTrue(EmojiRenderPerf.mayEmojiMatchClean(customSubclass, "no shortcode"),
                    "mayEmojiMatchClean must fall back conservatively for Emoji subclasses");
        } finally {
            Constants.EMOJI_LIST.clear();
            Constants.EMOJI_LIST.addAll(saved);
            EmojiRenderPerf.invalidateIndex();
        }

        // 3. Real EmojiFontRenderer + PerfEmojiFontRendererMixin merge simulation + patchEmojiFontRenderer
        ClassNode emojiTarget = readClassNode("com.hrznstudio.emojiful.render.EmojiFontRenderer");
        ClassNode emojiMixin = readClassNode("dev.sunlit.fishvisuals.mixin.PerfEmojiFontRendererMixin");
        String mixinInternal = "dev/sunlit/fishvisuals/mixin/PerfEmojiFontRendererMixin";
        for (MethodNode m : emojiMixin.methods) {
            if (m.name.startsWith("sunlit$")) {
                MethodNode copy = new MethodNode(m.access, m.name, m.desc, m.signature, null);
                m.accept(copy);
                for (AbstractInsnNode insn = copy.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode mi && mixinInternal.equals(mi.owner)) {
                        mi.owner = emojiTarget.name;
                    } else if (insn instanceof FieldInsnNode fi && mixinInternal.equals(fi.owner)) {
                        fi.owner = emojiTarget.name;
                    } else if (insn instanceof FrameNode fn) {
                        if (fn.local != null) {
                            for (int i = 0; i < fn.local.size(); i++) {
                                if (mixinInternal.equals(fn.local.get(i))) fn.local.set(i, emojiTarget.name);
                            }
                        }
                        if (fn.stack != null) {
                            for (int i = 0; i < fn.stack.size(); i++) {
                                if (mixinInternal.equals(fn.stack.get(i))) fn.stack.set(i, emojiTarget.name);
                            }
                        }
                    }
                }
                emojiTarget.methods.add(copy);
            }
        }
        assertTrue(ClientPostApplyPatches.patchEmojiFontRenderer(emojiTarget), "patchEmojiFontRenderer must succeed");
        assertTrue(ClientPostApplyPatches.patchEmojiFontRenderer(emojiTarget), "patchEmojiFontRenderer must be idempotent");
        MethodNode superRender = findMethod(emojiTarget,
                "sunlit$superRenderText(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F");
        assertNotNull(superRender, "sunlit$superRenderText missing after patch");
        assertEquals(1, countMethodInvoke(superRender,
                "Lnet/minecraft/client/gui/Font;m_271978_(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)F"),
                "sunlit$superRenderText must bind INVOKESPECIAL Font.m_271978_");
        assertNotNull(findMethod(emojiTarget, "sunlit$origWidthString(Ljava/lang/String;)I"), "origWidthString renamed");
        assertNotNull(findMethod(emojiTarget, "sunlit$origWidthSeq(Lnet/minecraft/util/FormattedCharSequence;)I"), "origWidthSeq renamed");
        assertEquals(null, findMethod(emojiTarget, "sunlit$fastWidthString(Ljava/lang/String;)I"), "fastWidthString swapped into m_92895_");
        MethodNode getFormatted = findMethod(emojiTarget, "getEmojiFormattedString(Ljava/lang/String;)Lorg/apache/commons/lang3/tuple/Pair;");
        assertNotNull(getFormatted, "getEmojiFormattedString missing on EmojiFontRenderer");
        assertEquals(1, countMethodInvoke(getFormatted, "Ldev/sunlit/bettersunlit/client/EmojiRenderPerf;mightContainEmoji(Ljava/lang/CharSequence;)Z"),
                "getEmojiFormattedString must guard loop with EmojiRenderPerf.mightContainEmoji");
        assertEquals(1, countMethodInvoke(getFormatted, "Ldev/sunlit/bettersunlit/client/EmojiRenderPerf;mayEmojiMatchClean(Lcom/hrznstudio/emojiful/api/Emoji;Ljava/lang/String;)Z"),
                "getEmojiFormattedString must guard per-emoji regex match with EmojiRenderPerf.mayEmojiMatchClean");

        ClassWriter emojiWriter = new ClassWriter(0);
        emojiTarget.accept(emojiWriter);
        verifyBytecode(emojiTarget.name.replace('/', '.'), emojiWriter.toByteArray());
    }

    private static void testArea1ChunkBuilderMeshingTaskPatch() throws Exception {
        String origInternal = "fr/madu59/obe/client/mixin/renderer/compat/sodium/ChunkBuilderMeshingTaskMixin";
        String renamedInternal = "dev/sunlit/bettersunlit/tests/perf/SynObeChunkTask";
        ClassNode synTask = readClassNode("fr.madu59.obe.client.mixin.renderer.compat.sodium.ChunkBuilderMeshingTaskMixin");
        synTask.name = renamedInternal;
        synTask.access &= ~Opcodes.ACC_ABSTRACT;
        synTask.visibleAnnotations = null;
        synTask.invisibleAnnotations = null;
        synTask.methods.removeIf(m -> !"<init>".equals(m.name) && !"obe$wrapRenderModel".equals(m.name));
        synTask.fields.clear();
        for (MethodNode m : synTask.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode fi && origInternal.equals(fi.owner)) {
                    fi.owner = renamedInternal;
                } else if (insn instanceof FrameNode fn) {
                    if (fn.local != null) {
                        for (int i = 0; i < fn.local.size(); i++) {
                            if (origInternal.equals(fn.local.get(i))) fn.local.set(i, renamedInternal);
                        }
                    }
                    if (fn.stack != null) {
                        for (int i = 0; i < fn.stack.size(); i++) {
                            if (origInternal.equals(fn.stack.get(i))) fn.stack.set(i, renamedInternal);
                        }
                    }
                }
            }
        }
        assertTrue(ClientPostApplyPatches.patchChunkBuilderMeshingTask(synTask),
                "patchChunkBuilderMeshingTask must succeed on OBE obe$wrapRenderModel");
        assertTrue(ClientPostApplyPatches.patchChunkBuilderMeshingTask(synTask),
                "patchChunkBuilderMeshingTask must be idempotent");
        MethodNode wrap = findMethod(synTask,
                "obe$wrapRenderModel(Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext;Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)V");
        assertNotNull(wrap, "obe$wrapRenderModel missing on SynObeChunkTask");
        assertEquals(2, countMethodInvoke(wrap, "Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext;model()Lnet/minecraft/client/resources/model/BakedModel;"),
                "obe$wrapRenderModel must check ctx.model() against correctedModel");
        int frames = 0;
        boolean hasAcmpeq = false;
        for (AbstractInsnNode insn = wrap.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode fn && fn.type == Opcodes.F_FULL) frames++;
            if (insn.getOpcode() == Opcodes.IF_ACMPEQ) hasAcmpeq = true;
        }
        assertTrue(hasAcmpeq, "obe$wrapRenderModel must branch with IF_ACMPEQ when model unchanged");
        assertEquals(1, frames, "obe$wrapRenderModel must insert 1 F_FULL frame before post-update resume");

        ClassWriter cw = new ClassWriter(0);
        synTask.accept(cw);
        byte[] synBytes = cw.toByteArray();
        String synBinary = "dev.sunlit.bettersunlit.tests.perf.SynObeChunkTask";
        ClassLoader obeLoader = new ClassLoader(PerfOptTests.class.getClassLoader()) {
            private final Map<String, byte[]> extraDefs = new HashMap<>();
            {
                extraDefs.put(synBinary, synBytes);
                extraDefs.put("com.llamalad7.mixinextras.injector.wrapoperation.Operation",
                        buildInterfaceBytes("com/llamalad7/mixinextras/injector/wrapoperation/Operation",
                                "call", "([Ljava/lang/Object;)Ljava/lang/Object;"));
                extraDefs.put("com.llamalad7.mixinextras.sugar.ref.LocalRef",
                        buildInterfaceBytes("com/llamalad7/mixinextras/sugar/ref/LocalRef",
                                "get", "()Ljava/lang/Object;",
                                "set", "(Ljava/lang/Object;)V"));
            }

            private final Map<String, Class<?>> loaded = new HashMap<>();

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                Class<?> existing = loaded.get(name);
                if (existing != null) {
                    if (resolve) resolveClass(existing);
                    return existing;
                }
                byte[] bytes = extraDefs.get(name);
                if (bytes != null) {
                    Class<?> cls = defineClass(name, bytes, 0, bytes.length);
                    loaded.put(name, cls);
                    if (resolve) resolveClass(cls);
                    return cls;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> verified = Class.forName(synBinary, true, obeLoader);
        verified.getDeclaredMethods();
        verified.getDeclaredFields();
        assertNotNull(verified.getDeclaredConstructor().newInstance(), "SynObeChunkTask must instantiate cleanly");
    }

    private static byte[] buildInterfaceBytes(String internalName, String... nameDescPairs) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        for (int i = 0; i < nameDescPairs.length; i += 2) {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, nameDescPairs[i], nameDescPairs[i + 1], null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void testArea4IrisAstCachePerf() {
        IrisAstCachePerf.trimParser(null);
        ASTParser parser = ASTParser._getInternalInstance();
        assertNotNull(parser, "ASTParser._getInternalInstance() must be available");
        IrisAstCachePerf.trimAstCaches();
        IrisAstCachePerf.trimParser(parser);
    }

    private static void testArea4JeiRegistryPerf() throws Exception {
        JeiRegistryPerf.invalidate();
        java.lang.reflect.Method createKey = ResourceKey.class.getDeclaredMethod(
                "m_135790_", ResourceLocation.class, ResourceLocation.class);
        createKey.setAccessible(true);
        @SuppressWarnings("unchecked")
        ResourceKey<Registry<String>> keyReg = (ResourceKey<Registry<String>>) createKey.invoke(
                null, new ResourceLocation("minecraft", "root"), new ResourceLocation("bettersunlit", "test_reg"));
        ResourceKey<String> key1 = ResourceKey.m_135785_(keyReg, new ResourceLocation("bettersunlit", "alpha"));

        Object backingA = new Object();
        Object backingB = new Object();
        AtomicInteger factoryCalls = new AtomicInteger();
        Function<Object, IPlatformRegistry<String>> factory = b -> {
            factoryCalls.incrementAndGet();
            return new IPlatformRegistry<>() {
                @Override public Stream<String> getValues() { return Stream.empty(); }
                @Override public Optional<String> getValue(ResourceLocation rl) { return Optional.empty(); }
                @Override public int getId(String entry) { return -1; }
                @Override public Optional<String> getValue(int id) { return Optional.empty(); }
                @Override public boolean contains(String entry) { return false; }
                @Override public Optional<ResourceLocation> getRegistryName(String entry) { return Optional.empty(); }
            };
        };

        IPlatformRegistry<String> w1 = JeiRegistryPerf.getOrCreate(key1, backingA, factory);
        IPlatformRegistry<String> w2 = JeiRegistryPerf.getOrCreate(key1, backingA, factory);
        assertSame(w1, w2, "Same backing registry instance must return cached wrapper");
        assertEquals(1, factoryCalls.get(), "Second lookup must not invoke factory");

        for (int i = 0; i < 1000; i++) {
            JeiRegistryPerf.getOrCreate(key1, backingA, factory);
        }
        long allocJeiReg = measureAllocBytes(() -> {
            int acc = 0;
            for (int i = 0; i < 5000; i++) {
                if (JeiRegistryPerf.getOrCreate(key1, backingA, factory) != null) acc++;
            }
            if (acc < 0) throw new AssertionError();
        });
        assertEquals(0L, allocJeiReg, "JeiRegistryPerf cache hit must allocate 0 bytes, got " + allocJeiReg);

        IPlatformRegistry<String> w3 = JeiRegistryPerf.getOrCreate(key1, backingB, factory);
        assertTrue(w3 != w1, "Changed backing registry identity must invalidate and rebuild wrapper");
        assertEquals(2, factoryCalls.get(), "Backing change must call factory once");

        JeiRegistryPerf.invalidate();
        assertEquals(0, JeiRegistryPerf.size(), "JeiRegistryPerf.invalidate must clear cache");
    }

    private static void verifyBytecode(String binaryName, byte[] classBytes) throws Exception {
        ClassLoader verifier = new ClassLoader(PerfOptTests.class.getClassLoader()) {
            private Class<?> defined;

            Class<?> loadBytes() {
                defined = defineClass(binaryName, classBytes, 0, classBytes.length);
                return defined;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(binaryName) && defined != null) {
                    if (resolve) resolveClass(defined);
                    return defined;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> cls = verifier.getClass().getDeclaredMethod("loadBytes").invoke(verifier) instanceof Class<?> c ? c : null;
        assertNotNull(cls, "Defined class missing: " + binaryName);
        cls.getDeclaredMethods();
        cls.getDeclaredFields();
    }

    // ---- Reference original ParticleRain computations for exact comparison ----

    private static Quaternionf refTurnBackface(Quaternionf q, Vector3f posToCam) {
        Vector3f normal = new Vector3f(0.0f, 0.0f, 1.0f);
        normal.rotate(q).normalize();
        if (normal.dot(posToCam) > 0.0f) {
            return q.mul(Axis.f_252436_.m_252961_(3.1415927f));
        }
        return q;
    }

    private static void refRenderRotatedQuad(RecordingVertexConsumer vc, Quaternionf q,
                                             float x, float y, float z,
                                             float size, float u0, float u1, float v0, float v1,
                                             float r, float g, float b, float a, int light) {
        q.rotateY(3.1415927f);
        Vector3f[] corners = {
            new Vector3f(-1.0f, -1.0f, 0.0f),
            new Vector3f(-1.0f, 1.0f, 0.0f),
            new Vector3f(1.0f, 1.0f, 0.0f),
            new Vector3f(1.0f, -1.0f, 0.0f)
        };
        for (Vector3f c : corners) {
            c.rotate(q);
            c.mul(size);
            c.add(x, y, z);
        }
        vc.m_5483_(corners[0].x(), corners[0].y(), corners[0].z()).m_7421_(u1, v1).m_85950_(r, g, b, a).m_85969_(light).m_5752_();
        vc.m_5483_(corners[1].x(), corners[1].y(), corners[1].z()).m_7421_(u1, v0).m_85950_(r, g, b, a).m_85969_(light).m_5752_();
        vc.m_5483_(corners[2].x(), corners[2].y(), corners[2].z()).m_7421_(u0, v0).m_85950_(r, g, b, a).m_85969_(light).m_5752_();
        vc.m_5483_(corners[3].x(), corners[3].y(), corners[3].z()).m_7421_(u0, v1).m_85950_(r, g, b, a).m_85969_(light).m_5752_();
    }

    private static void refVerticalQuad(RecordingVertexConsumer vc, float x, float y, float z,
                                        float oRoll, float roll, float pt, float size,
                                        float u0, float u1, float v0, float v1,
                                        float r, float g, float b, float a, int light) {
        Quaternionf q = new Quaternionf();
        q.rotateY(org.joml.Math.atan2(x, z));
        q.rotateZ(Mth.m_14179_(pt, oRoll, roll));
        refTurnBackface(q, new Vector3f(x, y, z));
        refRenderRotatedQuad(vc, q, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light);
    }

    private static void sVerticalQuad(RecordingVertexConsumer vc, ParticleRainClientPerf.Scratch s,
                                      float x, float y, float z,
                                      float oRoll, float roll, float pt, float size,
                                      float u0, float u1, float v0, float v1,
                                      float r, float g, float b, float a, int light) {
        s.q0.identity().rotateY(org.joml.Math.atan2(x, z)).rotateZ(Mth.m_14179_(pt, oRoll, roll));
        s.v0.set(x, y, z);
        ParticleRainClientPerf.turnBackfaceFlipways(s.q0, s.v0, s);
        ParticleRainClientPerf.emitRotatedQuad(vc, s.q0, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light, s.vCorner);
    }

    private static void refCameraCopyQuad(RecordingVertexConsumer vc, Quaternionf camRot,
                                          float x, float y, float z,
                                          float oRoll, float roll, float pt, float size,
                                          float u0, float u1, float v0, float v1,
                                          float r, float g, float b, float a, int light) {
        Quaternionf q = new Quaternionf(camRot);
        if (roll != 0.0f) q.rotateZ(Mth.m_14179_(pt, oRoll, roll));
        q.mul(Axis.f_252436_.m_252961_(3.1415927f));
        refRenderRotatedQuad(vc, q, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light);
    }

    private static void sCameraCopyQuad(RecordingVertexConsumer vc, ParticleRainClientPerf.Scratch s, Quaternionf camRot,
                                        float x, float y, float z,
                                        float oRoll, float roll, float pt, float size,
                                        float u0, float u1, float v0, float v1,
                                        float r, float g, float b, float a, int light) {
        s.q0.set(camRot);
        if (roll != 0.0f) s.q0.rotateZ(Mth.m_14179_(pt, oRoll, roll));
        s.q0.mul(s.qAux.rotationY(3.1415927f));
        ParticleRainClientPerf.emitRotatedQuad(vc, s.q0, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light, s.vCorner);
    }

    private static void refHorizontalQuad(RecordingVertexConsumer vc, float x, float y, float z,
                                          float oRoll, float roll, float pt, float size,
                                          float u0, float u1, float v0, float v1,
                                          float r, float g, float b, float a, int light) {
        Quaternionf q = new Quaternionf(new AxisAngle4d(1.5707963705062866d, -1.0d, 0.0d, 0.0d));
        if (roll != 0.0f) q.rotateZ(Mth.m_14179_(pt, oRoll, roll));
        refTurnBackface(q, new Vector3f(x, y, z));
        refRenderRotatedQuad(vc, q, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light);
    }

    private static void sHorizontalQuad(RecordingVertexConsumer vc, ParticleRainClientPerf.Scratch s,
                                        float x, float y, float z,
                                        float oRoll, float roll, float pt, float size,
                                        float u0, float u1, float v0, float v1,
                                        float r, float g, float b, float a, int light) {
        s.q0.set(s.axis4d.set(1.5707963705062866d, -1.0d, 0.0d, 0.0d));
        if (roll != 0.0f) s.q0.rotateZ(Mth.m_14179_(pt, oRoll, roll));
        s.v0.set(x, y, z);
        ParticleRainClientPerf.turnBackfaceFlipways(s.q0, s.v0, s);
        ParticleRainClientPerf.emitRotatedQuad(vc, s.q0, x, y, z, size, u0, u1, v0, v1, r, g, b, a, light, s.vCorner);
    }

    private static void refVelocityQuad(RecordingVertexConsumer vc, Vector3f vel,
                                         float x, float y, float z, boolean doCollisionAnim,
                                         float pt, float oProg, float prog, float size,
                                         float u0, float u1, float v0, float v1,
                                         float r, float g, float b, float a, int light) {
        Vector3f vn = new Vector3f(vel);
        float lenSq = vn.lengthSquared();
        if (Float.isFinite(lenSq) && lenSq > 1.0E-20f) vn.normalize(); else vn.set(0.0f, -1.0f, 0.0f);
        float angle = org.joml.Math.acos(Math.max(-1.0f, Math.min(1.0f, vn.y)));

        Vector3f axis = new Vector3f(-vel.z(), 0.0f, vel.x());
        float aLenSq = axis.lengthSquared();
        if (Float.isFinite(aLenSq) && aLenSq > 1.0E-20f) axis.normalize(); else axis.set(1.0f, 0.0f, 0.0f);

        Quaternionf q = new Quaternionf(new AxisAngle4f(-angle, axis));
        Vector3f localPos = new Vector3f(x, y, z);
        localPos.rotateAxis(angle, axis.x, axis.y, axis.z);
        q.mul(Axis.f_252436_.m_252961_(org.joml.Math.atan2(localPos.x, localPos.z) + 3.1415927f));
        float yScale = Mth.m_14036_(vel.lengthSquared(), 0.25f, 1.0f);
        if (doCollisionAnim) {
            float p = Mth.m_14179_(pt, oProg, prog);
            if (p < yScale) yScale = p;
        }
        refVertex(vc, q, x, y, z, 1.0f, -yScale, size, u1, v1, r, g, b, a, light);
        refVertex(vc, q, x, y, z, 1.0f, yScale, size, u1, v0, r, g, b, a, light);
        refVertex(vc, q, x, y, z, -1.0f, yScale, size, u0, v0, r, g, b, a, light);
        refVertex(vc, q, x, y, z, -1.0f, -yScale, size, u0, v1, r, g, b, a, light);
    }

    private static void sVelocityQuad(RecordingVertexConsumer vc, ParticleRainClientPerf.Scratch s, Vector3f vel,
                                      float x, float y, float z, boolean doCollisionAnim,
                                      float pt, float oProg, float prog, float size,
                                      float u0, float u1, float v0, float v1,
                                      float r, float g, float b, float a, int light) {
        float yScale = ParticleRainClientPerf.prepareVelocityQuaternion(vel, x, y, z, doCollisionAnim, pt, oProg, prog, s);
        ParticleRainClientPerf.emitVertex(vc, s.q0, x, y, z, 1.0f, -yScale, size, u1, v1, r, g, b, a, light, s.vCorner);
        ParticleRainClientPerf.emitVertex(vc, s.q0, x, y, z, 1.0f, yScale, size, u1, v0, r, g, b, a, light, s.vCorner);
        ParticleRainClientPerf.emitVertex(vc, s.q0, x, y, z, -1.0f, yScale, size, u0, v0, r, g, b, a, light, s.vCorner);
        ParticleRainClientPerf.emitVertex(vc, s.q0, x, y, z, -1.0f, -yScale, size, u0, v1, r, g, b, a, light, s.vCorner);
    }

    private static void refVertex(RecordingVertexConsumer vc, Quaternionf q, float x, float y, float z,
                                  float xo, float yo, float size, float u, float v,
                                  float r, float g, float b, float a, int light) {
        Vector3f c = new Vector3f(xo, yo, 0.0f).rotate(q).mul(size).add(x, y, z);
        vc.m_5483_(c.x(), c.y(), c.z()).m_7421_(u, v).m_85950_(r, g, b, a).m_85969_(light).m_5752_();
    }

    // ---- Bytecode & allocation helpers ----

    private static ClassNode readClassNode(String binaryName) throws IOException {
        try (InputStream in = PerfOptTests.class.getClassLoader()
                .getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            assertNotNull(in, "Missing class resource: " + binaryName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static String extractMixinTarget(ClassNode mixin) {
        AnnotationNode ann = findAnnotation(mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (ann == null) return null;
        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) annValue(ann, "targets");
        if (targets != null && !targets.isEmpty()) return targets.get(0);
        @SuppressWarnings("unchecked")
        List<Type> vals = (List<Type>) annValue(ann, "value");
        if (vals != null && !vals.isEmpty()) return vals.get(0).getClassName();
        return null;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> list, String desc) {
        if (list == null) return null;
        for (AnnotationNode a : list) if (desc.equals(a.desc)) return a;
        return null;
    }

    private static Object annValue(AnnotationNode a, String key) {
        if (a == null || a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode owner, String nameAndDesc) {
        for (MethodNode m : owner.methods) {
            if ((m.name + m.desc).equals(nameAndDesc)) return m;
        }
        return null;
    }

    private static int countMethodInvoke(MethodNode method, String fullTarget) {
        int split = fullTarget.indexOf(';');
        String ownerInternal = fullTarget.substring(1, split);
        String nameDesc = fullTarget.substring(split + 1);
        int lp = nameDesc.indexOf('(');
        String name = nameDesc.substring(0, lp);
        String desc = nameDesc.substring(lp);
        int hits = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                if (mi.owner.equals(ownerInternal) && mi.name.equals(name) && mi.desc.equals(desc)) {
                    hits++;
                }
            }
        }
        return hits;
    }

    private static long measureAllocBytes(Runnable task) {
        THREAD_MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 4; i++) {
            task.run();
        }
        long before = THREAD_MX.getCurrentThreadAllocatedBytes();
        task.run();
        long after = THREAD_MX.getCurrentThreadAllocatedBytes();
        return after - before;
    }

    private static void assertVerticesBitIdentical(RecordingVertexConsumer expected,
                                                   RecordingVertexConsumer actual,
                                                   String label) {
        assertEquals(expected.count, actual.count, label + " vertex count mismatch");
        for (int i = 0; i < expected.count; i++) {
            assertEquals(Float.floatToRawIntBits((float) expected.x[i]), Float.floatToRawIntBits((float) actual.x[i]), label + " x[" + i + "]");
            assertEquals(Float.floatToRawIntBits((float) expected.y[i]), Float.floatToRawIntBits((float) actual.y[i]), label + " y[" + i + "]");
            assertEquals(Float.floatToRawIntBits((float) expected.z[i]), Float.floatToRawIntBits((float) actual.z[i]), label + " z[" + i + "]");
            assertEquals(Float.floatToRawIntBits(expected.u[i]), Float.floatToRawIntBits(actual.u[i]), label + " u[" + i + "]");
            assertEquals(Float.floatToRawIntBits(expected.v[i]), Float.floatToRawIntBits(actual.v[i]), label + " v[" + i + "]");
            assertEquals(expected.rgba[i], actual.rgba[i], label + " rgba[" + i + "]");
            assertEquals(expected.overlay[i], actual.overlay[i], label + " overlay[" + i + "]");
            assertEquals(expected.light[i], actual.light[i], label + " light[" + i + "]");
            assertEquals(Float.floatToRawIntBits(expected.nx[i]), Float.floatToRawIntBits(actual.nx[i]), label + " nx[" + i + "]");
            assertEquals(Float.floatToRawIntBits(expected.ny[i]), Float.floatToRawIntBits(actual.ny[i]), label + " ny[" + i + "]");
            assertEquals(Float.floatToRawIntBits(expected.nz[i]), Float.floatToRawIntBits(actual.nz[i]), label + " nz[" + i + "]");
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError(msg);
    }

    private static void assertSame(Object expected, Object actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertThrows(Class<? extends Throwable> ex, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            if (ex.isInstance(t)) return;
            throw new AssertionError("Expected " + ex.getName() + " but caught " + t, t);
        }
        throw new AssertionError("Expected " + ex.getName() + " to be thrown");
    }

    private static final class TamperPluginLoader extends ClassLoader {
        private final String tamperedResource;

        TamperPluginLoader(ClassLoader parent, String tamperedResource) {
            super(parent);
            this.tamperedResource = tamperedResource;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals(tamperedResource)) {
                try (InputStream orig = getParent().getResourceAsStream(name)) {
                    byte[] data = orig.readAllBytes();
                    data[data.length - 1] ^= 0x5A;
                    return new ByteArrayInputStream(data);
                } catch (IOException e) {
                    return null;
                }
            }
            return super.getResourceAsStream(name);
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(EntryMixinPlugin.class.getName())) {
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] b = in.readAllBytes();
                    Class<?> c = defineClass(name, b, 0, b.length);
                    if (resolve) resolveClass(c);
                    return c;
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private static final int CAP = 64;
        final double[] x = new double[CAP];
        final double[] y = new double[CAP];
        final double[] z = new double[CAP];
        final float[] u = new float[CAP];
        final float[] v = new float[CAP];
        final int[] rgba = new int[CAP];
        final int[] overlay = new int[CAP];
        final int[] light = new int[CAP];
        final float[] nx = new float[CAP];
        final float[] ny = new float[CAP];
        final float[] nz = new float[CAP];
        int count = 0;

        void reset() { count = 0; }

        @Override public VertexConsumer m_5483_(double vx, double vy, double vz) {
            x[count] = vx; y[count] = vy; z[count] = vz;
            return this;
        }
        @Override public VertexConsumer m_6122_(int r, int g, int b, int a) {
            rgba[count] = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }
        @Override public VertexConsumer m_7421_(float vu, float vv) { u[count] = vu; v[count] = vv; return this; }
        @Override public VertexConsumer m_7122_(int u, int v) { overlay[count] = (u & 0xFFFF) | ((v & 0xFFFF) << 16); return this; }
        @Override public VertexConsumer m_7120_(int u, int v) { light[count] = (u & 0xFFFF) | ((v & 0xFFFF) << 16); return this; }
        @Override public VertexConsumer m_85969_(int uv2) { light[count] = uv2; return this; }
        @Override public VertexConsumer m_5601_(float xN, float yN, float zN) { nx[count] = xN; ny[count] = yN; nz[count] = zN; return this; }
        @Override public void m_5752_() { count++; }
        @Override public void m_7404_(int r, int g, int b, int a) {}
        @Override public void m_141991_() {}
    }
}
