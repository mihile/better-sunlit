package dev.sunlit.bettersunlit.tests.perf;

import com.cicada.longwings.entity.ButterflyVariant;
import com.google.common.collect.HashMultimap;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.sun.management.ThreadMXBean;
import dev.sunlit.bettersunlit.client.AmbientSoundsClientPerf;
import dev.sunlit.bettersunlit.client.LongwingsClientPerf;
import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import dev.sunlit.bettersunlit.client.SeasonColorPerf;
import dev.sunlit.bettersunlit.inline.EntryMixinPlugin;
import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.AxisAngle4d;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import sereneseasons.season.SeasonColorHandlers;
import sereneseasons.season.SeasonTime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
            "pigcart.particlerain.ParticleSpawner",
            "pigcart.particlerain.particle.WeatherParticle",
            "pigcart.particlerain.particle.CustomParticle",
            "sereneseasons.config.SeasonsConfig",
            "sereneseasons.season.SeasonHandler",
            "sereneseasons.season.SeasonHooks",
            "sereneseasons.season.SeasonColorHandlers",
            "sereneseasons.util.SeasonColorUtil",
            "team.creative.ambientsounds.environment.BiomeEnvironment$BiomeArea"
    );

    private static final List<String> ALL_PERF_MIXINS = List.of(
            "dev.sunlit.fishvisuals.mixin.PerfButterflyVariantMixin",
            "dev.sunlit.fishvisuals.mixin.PerfButterflyEntityMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonsConfigMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHandlerMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHooksMixin",
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
            "dev.sunlit.fishvisuals.mixin.PerfSeasonHandlerClientMixin",
            "dev.sunlit.fishvisuals.mixin.PerfSeasonColorHandlersMixin",
            "dev.sunlit.fishvisuals.mixin.PerfAmbientBiomeAreaMixin"
    );

    public static void main(String[] args) throws Exception {
        testEntryMixinPluginHashesAndGates();
        testMixinBytecodeContractsAgainstModJars();
        testLongwingsVariantPoolAndMatches();
        testLongwingsTextureCacheAndReload();
        testParticleRainMathAndZeroAlloc();
        testSereneSeasonsHelpers();
        testAmbientSoundsWildcards();
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
            assertEquals(expected.light[i], actual.light[i], label + " light[" + i + "]");
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
        final double[] x = new double[8];
        final double[] y = new double[8];
        final double[] z = new double[8];
        final float[] u = new float[8];
        final float[] v = new float[8];
        final int[] rgba = new int[8];
        final int[] light = new int[8];
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
        @Override public VertexConsumer m_7122_(int u, int v) { return this; }
        @Override public VertexConsumer m_7120_(int u, int v) { light[count] = (u & 0xFFFF) | ((v & 0xFFFF) << 16); return this; }
        @Override public VertexConsumer m_85969_(int uv2) { light[count] = uv2; return this; }
        @Override public VertexConsumer m_5601_(float nx, float ny, float nz) { return this; }
        @Override public void m_5752_() { count++; }
        @Override public void m_7404_(int r, int g, int b, int a) {}
        @Override public void m_141991_() {}
    }
}
