package dev.sunlit.bettersunlit.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-loads and Mixin-transforms verified late-startup classes ({@code initialize = false}, so no
 * static initializers or OpenGL calls run) across background daemon workers during Forge's
 * single-threaded registry phase when extra CPU cores would otherwise sit idle.
 */
public final class StartupClassPreloadPerf {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static final List<String> PRELOAD_TARGETS = Collections.unmodifiableList(Arrays.asList(
            // Late Entity / BlockEntity renderers and Mixin-heavy models
            "net.minecraft.client.renderer.blockentity.BlockEntityRenderers",
            "net.minecraft.client.renderer.blockentity.SignRenderer",
            "net.minecraft.client.renderer.blockentity.HangingSignRenderer",
            "net.minecraft.client.renderer.blockentity.PistonHeadRenderer",
            "net.minecraft.client.renderer.blockentity.ChestRenderer",
            "net.minecraft.client.renderer.blockentity.LecternRenderer",
            "net.minecraft.client.renderer.blockentity.BeaconRenderer",
            "net.minecraft.client.renderer.blockentity.SkullBlockRenderer",
            "net.minecraft.client.renderer.blockentity.BannerRenderer",
            "net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer",
            "net.minecraft.client.renderer.blockentity.BedRenderer",
            "net.minecraft.client.renderer.blockentity.BellRenderer",
            "net.minecraft.client.renderer.blockentity.DecoratedPotRenderer",
            "net.minecraft.client.renderer.entity.EntityRenderers",
            "net.minecraft.client.model.PiglinModel",
            "net.minecraft.client.model.geom.ModelPart$Cube",
            "net.minecraft.client.renderer.entity.EnderDragonRenderer",
            "net.minecraft.client.renderer.entity.EnderDragonRenderer$DragonModel",
            "net.minecraft.client.renderer.entity.layers.LlamaDecorLayer",
            "net.minecraft.client.renderer.entity.layers.HorseArmorLayer",
            "net.minecraft.client.renderer.entity.layers.HorseMarkingLayer",
            "net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer",
            "net.minecraft.client.renderer.entity.layers.ElytraLayer",
            "net.minecraft.client.renderer.entity.layers.ItemInHandLayer",
            "net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer",
            "net.minecraft.client.renderer.entity.layers.CapeLayer",
            "net.minecraft.client.renderer.entity.layers.StuckInBodyLayer",
            "net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer",
            "net.minecraft.client.renderer.entity.layers.BeeStingerLayer",
            "net.minecraft.client.renderer.entity.layers.EyesLayer",
            "net.minecraft.client.renderer.entity.layers.CarriedBlockLayer",
            "net.minecraft.client.renderer.entity.layers.DrownedOuterLayer",
            "net.minecraft.client.renderer.entity.layers.CreeperPowerLayer",
            "net.minecraft.client.renderer.entity.layers.FoxHeldItemLayer",
            "net.minecraft.client.renderer.entity.layers.MushroomCowMushroomLayer",
            "net.minecraft.client.renderer.entity.layers.PandaHoldsItemLayer",
            "net.minecraft.client.renderer.entity.layers.SlimeOuterLayer",
            "net.minecraft.client.renderer.entity.layers.StrayClothingLayer",
            "net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer",
            "net.minecraft.client.renderer.entity.layers.WardenEmissiveLayer",
            "net.minecraft.client.renderer.entity.layers.WitchItemLayer",
            "net.minecraft.client.renderer.entity.layers.WolfCollarLayer",
            "net.minecraft.client.renderer.entity.UndeadHorseRenderer",
            "net.minecraft.client.renderer.entity.LlamaRenderer",
            "net.minecraft.client.renderer.entity.CamelRenderer",
            "net.minecraft.client.renderer.entity.ArmorStandRenderer",
            "net.minecraft.client.model.ArmorStandModel",
            "net.minecraft.client.renderer.entity.BatRenderer",
            "net.minecraft.client.renderer.entity.BeeRenderer",
            "net.minecraft.client.renderer.entity.CatRenderer",
            "net.minecraft.client.model.SpiderModel",
            "net.minecraft.client.renderer.entity.ChickenRenderer",
            "net.minecraft.client.renderer.entity.CowRenderer",
            "net.minecraft.client.renderer.entity.DolphinRenderer",
            "net.minecraft.client.renderer.entity.EndCrystalRenderer",
            "net.minecraft.client.renderer.entity.FrogRenderer",
            "net.minecraft.client.renderer.entity.GoatRenderer",
            "net.minecraft.client.model.HorseModel",
            "net.minecraft.client.renderer.entity.IronGolemRenderer",
            "net.minecraft.client.renderer.entity.ParrotRenderer",
            "net.minecraft.client.renderer.entity.PigRenderer",
            "net.minecraft.client.renderer.entity.RabbitRenderer",
            "net.minecraft.client.renderer.entity.SheepRenderer",
            "net.minecraft.client.renderer.entity.SlimeRenderer",
            "net.minecraft.client.renderer.entity.WolfRenderer",
            "net.minecraft.client.model.ZombieVillagerModel",
            "net.minecraft.client.model.AbstractZombieModel",
            "net.minecraft.client.model.WolfModel",
            "net.minecraft.client.model.IllagerModel",
            "net.minecraft.client.model.PlayerModel",
            // EMF / Longwings / Create renderers & visuals
            "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube",
            "traben.entity_model_features.models.animation.math.expression_tree.MathExpressionParser",
            "traben.entity_model_features.models.animation.math.variables.factories.ModelPartVariableFactory",
            "com.cicada.longwings.client.model.ButterflyModel",
            "com.cicada.longwings.client.renderers.ButterflyEntityRenderer",
            "com.cicada.longwings.client.renderers.ButterflyEntityRenderer$MothEntityRenderer",
            "com.cicada.longwings.client.renderers.ButterflyItemRenderer",
            "com.cicada.longwings.client.renderers.ButterflyItemRenderer$MothItemRenderer",
            "com.simibubi.create.content.trains.bogey.BogeyBlockEntityRenderer",
            "com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceRenderer",
            "com.simibubi.create.content.contraptions.render.ContraptionVisual",
            "com.simibubi.create.content.trains.entity.CarriageContraptionVisual",
            "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorVisual",
            "com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorRenderer",
            "com.simibubi.create.content.kinetics.flywheel.FlywheelVisual",
            "com.simibubi.create.content.kinetics.deployer.DeployerRenderer",
            "com.simibubi.create.content.logistics.factoryBoard.FactoryPanelRenderer",
            "com.simibubi.create.content.trains.track.TrackVisual",
            "com.simibubi.create.content.trains.track.TrackRenderer",
            "com.simibubi.create.content.trains.station.StationRenderer",
            "com.simibubi.create.content.decoration.slidingDoor.SlidingDoorRenderer",
            "com.simibubi.create.content.kinetics.belt.BeltHelper",
            "com.simibubi.create.foundation.fluid.FluidHelper",
            "com.simibubi.create.foundation.block.BigOutlines",
            "com.simibubi.create.infrastructure.ponder.AllCreatePonderScenes",
            // Resource reload / Model / Sound / Font / Search / EMI / Misc heavy Mixin targets
            "net.minecraft.data.models.BlockModelGenerators",
            "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
            "net.minecraft.world.level.levelgen.structure.StructureStart",
            "net.minecraft.nbt.NbtUtils",
            "net.minecraft.client.resources.model.MultiPartBakedModel",
            "net.minecraft.client.resources.model.MultiPartBakedModel$Builder",
            "net.minecraft.client.resources.model.WeightedBakedModel",
            "net.minecraft.client.resources.model.SimpleBakedModel",
            "net.minecraft.client.renderer.texture.SpriteLoader",
            "net.minecraft.client.renderer.texture.Stitcher",
            "net.minecraft.client.renderer.texture.MipmapGenerator",
            "net.minecraft.client.renderer.texture.SpriteContents$Ticker",
            "net.minecraft.client.renderer.texture.SpriteContents$InterpolationData",
            "com.mojang.blaze3d.audio.Channel",
            "com.mojang.blaze3d.audio.SoundBuffer",
            "com.mojang.blaze3d.audio.OggAudioStream",
            "net.minecraft.client.searchtree.FullTextSearchTree",
            "icyllis.modernui.mc.text.StandardFontSet",
            "dev.engine_room.flywheel.backend.compile.InstancingPrograms",
            "dev.engine_room.flywheel.backend.compile.PipelineCompiler",
            "dev.engine_room.flywheel.backend.compile.core.ProgramLinker",
            "dev.emi.emi.bom.BoM",
            "dev.emi.emi.screen.RecipeScreen",
            "dev.emi.emi.screen.StackBatcher",
            "dev.emi.emi.data.RecipeDefaults",
            "dev.emi.emi.data.EmiRecipeCategoryProperties",
            "dev.emi.emi.api.recipe.EmiRecipeSorting",
            "dev.emi.emi.data.EmiAlias",
            "dev.emi.emi.data.IndexStackData",
            "dev.emi.emi.data.EmiDataLoader",
            "dev.isxander.yacl3.gui.image.YACLImageReloadListener",
            "net.createmod.ponder.PonderClient",
            "com.hrznstudio.emojiful.ClientEmojiHandler",
            "com.hrznstudio.emojiful.render.EmojiFontRenderer",
            "com.moepus.gbf.molang.compiler.MolangCompiler",
            "software.bernie.geckolib.core.molang.MolangParser",
            "journeymap.client.io.ThemeLoader",
            "journeymap.client.texture.ImageUtil",
            "sereneseasons.season.SeasonColorHandlers",
            "pigcart.particlerain.WindManager",
            "pigcart.particlerain.ParticleSpawner",
            "team.creative.ambientsounds.block.AmbientBlockGroup",
            "de.keksuccino.fancymenu.customization.layout.Layout",
            "de.keksuccino.fancymenu.customization.ScreenCustomization",
            "de.keksuccino.fancymenu.customization.overlay.CustomizationOverlay",
            "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen",
            "net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen",
            "net.minecraft.client.gui.screens.worldselection.WorldSelectionList",
            "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList",
            // VanillaBackport / Controllable / EMF / ETF / Voxy heavy client & renderer classes
            "com.blackgear.vanillabackport.client.ClientSetup",
            "com.blackgear.vanillabackport.client.Rendering",
            "com.blackgear.vanillabackport.client.api.color.DryFoliageColor",
            "com.blackgear.vanillabackport.client.api.color.LeafColors",
            "com.blackgear.vanillabackport.client.api.modules.leaf_litter.DryFoliageColor",
            "com.blackgear.vanillabackport.client.api.modules.falling_leaves.LeafColors",
            "com.blackgear.vanillabackport.client.api.renderer.renderers.BatSpecialRenderer",
            "com.blackgear.vanillabackport.client.api.renderer.renderers.ChickenSpecialRenderer",
            "com.blackgear.vanillabackport.client.api.renderer.renderers.CowSpecialRenderer",
            "com.blackgear.vanillabackport.client.api.renderer.renderers.PigSpecialRenderer",
            "com.mrcrayfish.controllable.client.ClientBootstrap",
            "com.mrcrayfish.controllable.client.ControllerInput",
            "com.mrcrayfish.controllable.client.OverlayHandler",
            "com.mrcrayfish.controllable.client.RadialMenuHandler",
            "traben.entity_model_features.EMFManager",
            "traben.entity_model_features.models.EMFModelMappings",
            "traben.entity_model_features.models.animation.EMFAnimationHandler",
            "traben.entity_texture_features.features.ETFManager",
            "traben.entity_texture_features.features.ETFRenderContext",
            "me.cortex.voxy.client.VoxyClientInstance",
            "me.cortex.voxy.client.compat.LodPipelineHooks",
            "me.cortex.voxy.client.compat.create.DistantCreateRenderer",
            "me.cortex.voxy.client.compat.create.DistantKineticRenderer",
            // Late-startup client / UI / renderer Mixin targets preloaded with initialize = false
            "net.minecraft.client.renderer.item.ItemProperties",
            "net.minecraft.client.gui.screens.TitleScreen",
            "net.minecraft.client.renderer.LevelRenderer",
            "net.minecraft.client.renderer.GameRenderer",
            "net.minecraft.client.renderer.entity.LivingEntityRenderer",
            "net.minecraft.client.renderer.entity.HumanoidMobRenderer",
            "net.minecraft.client.renderer.entity.BoatRenderer",
            "net.minecraft.client.particle.ParticleEngine",
            "net.minecraft.client.renderer.ItemModelShaper",
            "net.minecraft.client.renderer.block.BlockRenderDispatcher",
            "net.minecraft.client.renderer.entity.ItemRenderer",
            "de.keksuccino.fancymenu.customization.screen.identifier.UniversalScreenIdentifierRegistry",
            "vectorwing.farmersdelight.common.registry.ModBlocks",
            "de.keksuccino.fancymenu.customization.background.backgrounds.MenuBackgrounds",
            "de.keksuccino.fancymenu.customization.element.elements.Elements",
            "de.keksuccino.fancymenu.customization.placeholder.PlaceholderParser",
            "de.keksuccino.fancymenu.customization.loadingrequirement.internal.LoadingRequirementContainer",
            "de.keksuccino.drippyloadingscreen.customization.DrippyOverlayScreen",
            "io.github.chakyl.societytrading.events.ClientModEvents",
            "com.alc.moreminecarts.MoreMinecartsMod",
            "dev.ftb.mods.ftblibrary.icon.ImageIcon",
            "net.minecraft.client.model.geom.EntityModelSet",
            "de.markusbordihn.easynpc.client.model.EasyNPCModelManager",
            "net.rasanovum.viaromana.client.render.ClientCursorHandler",
            "dev.uncandango.alltheleaks.utils.ReflectionHelper",
            "com.github.alexthe666.citadel.client.model.AdvancedModelBox",
            "journeymap.client.ui.minimap.MiniMap",
            "net.minecraftforge.client.model.obj.ObjModel",
            "net.minecraftforge.client.model.obj.ObjLoader",
            "com.supermartijn642.fusion.entity.model.loader.FusionEntityModelLoader",
            "traben.entity_model_features.models.animation.math.variables.factories.ModelVariableFactory",
            "traben.entity_model_features.models.jem_objects.EMFJemData",
            "net.satisfy.wildernature.client.WilderNatureClient",
            "com.ninni.species.registry.SpeciesBlocks",
            "net.satisfy.farm_and_charm.registry.ObjectRegistry",
            "net.minecraft.client.gui.screens.MenuScreens",
            "de.markusbordihn.easynpc.commands.ModArgumentTypes",
            "dev.emi.emi.platform.forge.EmiClientForge",
            "at.ridgo8.moreoverlays.KeyBindings",
            "com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes",
            "com.teammetallurgy.aquaculture.init.FishRegistry",
            "sfiomn.legendarycreatures.registry.EntityTypeRegistry",
            "net.mehvahdjukaar.supplementaries.reg.ModEntities",
            "net.liukrast.eg.registry.EGBlockEntityTypes",
            "software.bernie.geckolib.core.animation.Animation$LoopType",
            "net.mehvahdjukaar.supplementaries.reg.ClientRegistry",
            "net.minecraftforge.client.PresetEditorManager",
            "dev.emi.emi.data.EmiData",
            "com.blackgear.vanillabackport.client.Rendering",
            "com.mrcrayfish.furniture.refurbished.client.ForgeClientEvents",
            "de.keksuccino.fancymenu.customization.screen.identifier.UniversalScreenIdentifierRegistry",
            "top.yourzi.dialog.network.NetworkHandler",
            "fuzs.armorstatues.client.gui.screens.armorstand.ArmorStandAlignmentsScreen",
            "io.github.mortuusars.scholar.client.gui.screen.view.LecternSpreadBookViewScreen",
            "io.github.mortuusars.scholar.client.gui.screen.edit.LecternSpreadBookEditScreen",
            "de.markusbordihn.easynpc.client.screen.dialog.DialogScreen",
            "de.markusbordihn.easynpc.client.screen.dialog.DialogScreenWrapper",
            "com.github.alexthe666.citadel.client.model.TabulaModelHandler",
            "journeymap.common.properties.catagory.CategorySet",
            "journeymap.client.texture.ComparableNativeImage",
            "xyz.apex.forge.fantasyfurniture.client.screen.FurnitureStationMenuScreen",
            "dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel",
            "dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel",
            "net.rasanovum.viaromana.client.gui.TeleportMapScreen",
            "net.satisfy.candlelight.client.CandlelightClient",
            "io.github.mortuusars.exposure.Exposure$Blocks",
            "vectorwing.farmersdelight.common.registry.ModBlocks",
            "net.liukrast.eg.registry.EGPanels",
            "com.ninni.species.client.events.ClientEvents",
            "vectorwing.farmersdelight.client.event.ClientSetupEvents",
            "de.keksuccino.fancymenu.customization.element.elements.Elements",
            "dev.uncandango.alltheleaks.leaks.common.mods.smallships.UntrackedIssue001",
            "com.mrcrayfish.furniture.refurbished.client.ClientBootstrap",
            "com.corosus.watut.client.screen.RenderHelper",
            "noobanidus.mods.lootr.init.ModBlocks",
            "com.alc.moreminecarts.registry.MMEntities",
            "com.simibubi.create.AllMenuTypes",
            "com.github.teamfusion.rottencreatures.client.ClientSetup",
            "codyhuh.unusualfishmod.client.ClientEvents",
            "com.ninni.species.client.events.ClientEvents",
            "vectorwing.farmersdelight.client.event.ClientSetupEvents",
            "de.keksuccino.fancymenu.util.event.acara.EventHandler",
            "dev.uncandango.alltheleaks.leaks.IssueManager"
    ));

    private StartupClassPreloadPerf() {
    }

    public static void schedulePreload() {
        schedulePreload(StartupClassPreloadPerf.class.getClassLoader(), PRELOAD_TARGETS);
        Thread warmup = new Thread(() -> {
            try {
                SpriteMipmapPerf.prewarmExtendedOffHeap();
            } catch (Throwable ignored) {
            }
        }, "BetterSunlit-MipmapWarmup");
        warmup.setDaemon(true);
        warmup.setPriority(Thread.NORM_PRIORITY - 1);
        warmup.start();
    }

    public static int computeWorkerCount(int availableProcessors) {
        return Math.max(1, Math.min(2, availableProcessors / 4));
    }

    static void schedulePreload(ClassLoader loader, List<String> targets) {
        if (!STARTED.compareAndSet(false, true) || loader == null || targets == null || targets.isEmpty()) {
            return;
        }
        int workers = computeWorkerCount(Runtime.getRuntime().availableProcessors());
        AtomicInteger cursor = new AtomicInteger(0);
        for (int i = 0; i < workers; i++) {
            Thread thread = new Thread(() -> runWorker(loader, targets, cursor), "BetterSunlit-Preload-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
    }

    public static int runPreloadSync(ClassLoader loader, List<String> targets) {
        int loaded = 0;
        for (String name : targets) {
            if (tryPreloadClass(loader, name)) {
                loaded++;
            }
        }
        return loaded;
    }

    private static void runWorker(ClassLoader loader, List<String> targets, AtomicInteger cursor) {
        int size = targets.size();
        while (true) {
            int idx = cursor.getAndIncrement();
            if (idx >= size) {
                return;
            }
            tryPreloadClass(loader, targets.get(idx));
        }
    }

    private static boolean tryPreloadClass(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void resetForTest() {
        STARTED.set(false);
    }
}
