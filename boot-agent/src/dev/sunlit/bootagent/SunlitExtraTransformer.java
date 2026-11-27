package dev.sunlit.bootagent;

import dev.sunlit.bootagent.payload.BootJarPayload;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Modular Batch 24 transformer for startup-critical targets:
 * <ul>
 *   <li>Generates {@code com.google.common.collect.SunlitFlatStateMap} for zero-entry BlockState property maps.</li>
 *   <li>Eliminates {@code HashSet} copies in {@code java.lang.module.Configuration.reads}.</li>
 *   <li>Replaces ETF {@code Plugin.hasClass} bytecode parsing with classloader resource existence checks.</li>
 *   <li>Synchronizes {@code RemappingHelper.getMinecraftRemapper} for safe background pre-warming.</li>
 * </ul>
 */
public final class SunlitExtraTransformer implements ClassFileTransformer {
    private static final String CONFIG_CLS = "java/lang/module/Configuration";
    private static final String ETF_PLUGIN_CLS = "traben/entity_texture_features/mixin/Plugin";
    private static final String RHINO_REMAP_CLS = "dev/latvian/mods/rhino/mod/util/RemappingHelper";
    private static final String JDK_ZIPPATH_CLS = "jdk/nio/zipfs/ZipPath";
    private static final String MF_SCAN_COMPACTOR_CLS = "org/embeddedt/modernfix/forge/load/ModFileScanDataCompactor";
    private static final String CLIENT_LANG_CLS = "net/minecraft/client/resources/language/ClientLanguage";
    private static final String FLW_BACKEND_MGR_CLS = "dev/engine_room/flywheel/impl/BackendManagerImpl";
    private static final String MOONLIGHT_RES_TX_CLS = "net/mehvahdjukaar/moonlight/api/resources/BlockTypeResTransformer";
    private static final String EMF_RES_CACHING_CLS = "traben/entity_model_features/utils/EMFResourceCaching";
    private static final String MC_BOOTSTRAP_CLS = "net/minecraft/server/Bootstrap";
    private static final String CARRYON_LIST_HANDLER_CLS = "tschipp/carryon/common/config/ListHandler";
    private static final String EVERYCOMP_PENDING_CLS =
            "net/mehvahdjukaar/every_compat/misc/HardcodedBlockType$PendingBlockInfo";
    private static final String MCLIB_MATH_BUILDER_CLS = "com/eliotlash/mclib/math/MathBuilder";
    private static final String EMF_MAPPINGS_CLS = "traben/entity_model_features/models/EMFModelMappings";
    private static final String EMF_MANAGER_CLS = "traben/entity_model_features/EMFManager";
    private static final String EMF_MATH_PARSER_CLS =
            "traben/entity_model_features/models/animation/math/expression_tree/MathExpressionParser";
    private static final String EMF_VAR_FACTORY_CLS =
            "traben/entity_model_features/models/animation/math/variables/factories/ModelVariableFactory";
    private static final String EMF_MATH_CLS =
            "traben/entity_model_features/models/animation/math/EMFMath";
    private static final String EMF_ASM_ANIM_HANDLER_CLS =
            "traben/entity_model_features/models/animation/math/asm/ASMAnimationHandler";
    private static final String MC_BAKED_QUAD_CLS =
            "net/minecraft/client/renderer/block/model/BakedQuad";
    private static final String FUSION_ENTITY_LOADER_CLS =
            "com/supermartijn642/fusion/entity/model/loader/FusionEntityModelLoader";
    private static final String CSC_MATH_CLS = "com/Pink_Cats/createschematicchecker/core/attach/Math";
    private static final String CSC_TOML_EDITOR_CLS =
            "com/Pink_Cats/createschematicchecker/FancyConfig/SimpleTomlEditor";
    private static final String FM_LOADING_REQ_CLS =
            "de/keksuccino/fancymenu/customization/loadingrequirement/internal/LoadingRequirementContainer";
    private static final String EMBERBIUM_MODULE_SCANNER_CLS =
            "me/jellysquid/mods/sodium/client/compatibility/checks/ModuleScanner";
    private static final String AMENDMENTS_WALL_LANTERN_CLS =
            "net/mehvahdjukaar/amendments/common/block/WallLanternBlock";
    private static final String EMF_JEM_DATA_CLS =
            "traben/entity_model_features/models/jem_objects/EMFJemData";
    private static final String KJS_ICON_PROVIDER_CLS =
            "dev/latvian/mods/kubejs/core/WindowKJS$KJSScaledIconProvider";
    private static final String MOONLIGHT_PATH_TRIE_CLS =
            "net/mehvahdjukaar/moonlight/api/misc/PathTrie";
    private static final String MIXIN_INJECTION_POINT_CLS =
            "org/spongepowered/asm/mixin/injection/InjectionPoint";
    private static final String ALI_TOOLTIP_NODE_CLS =
            "com/yanny/aci/tooltip/TooltipNode";
    private static final String ALI_TOOLTIP_BUILDER_CLS =
            "com/yanny/aci/tooltip/TooltipBuilder";
    private static final String CREATE_SPOUT_CATEGORY_CLS =
            "com/simibubi/create/compat/jei/category/SpoutCategory";
    private static final String CREATE_TOOLTIP_HELPER_CLS =
            "com/simibubi/create/foundation/item/TooltipHelper";
    private static final String EMI_RELOAD_MANAGER_CLS =
            "dev/emi/emi/runtime/EmiReloadManager";
    private static final String EMI_RECIPES_CLS =
            "dev/emi/emi/registry/EmiRecipes";
    private static final String EMI_RECIPES_MANAGER_CLS =
            "dev/emi/emi/registry/EmiRecipes$Manager";
    private static final String EMI_LIST_INGREDIENT_CLS =
            "dev/emi/emi/api/stack/ListEmiIngredient";
    private static final String EMI_INGREDIENT_CLS =
            "dev/emi/emi/api/stack/EmiIngredient";
    private static final String EMI_DATA_CLS =
            "dev/emi/emi/data/EmiData";
    private static final String EMI_HIDDEN_CLS =
            "dev/emi/emi/runtime/EmiHidden";
    private static final String EMI_ITEM_STACK_CLS =
            "dev/emi/emi/api/stack/ItemEmiStack";
    private static final String EMI_TAG_INGREDIENT_CLS =
            "dev/emi/emi/api/stack/TagEmiIngredient";
    private static final String EMI_TAGS_CLS =
            "dev/emi/emi/registry/EmiTags";
    private static final String EMI_SHAPED_RECIPE_CLS =
            "dev/emi/emi/recipe/EmiShapedRecipe";
    private static final String EMI_ANVIL_ENCHANT_CLS =
            "dev/emi/emi/recipe/special/EmiAnvilEnchantRecipe";
    private static final String EMI_UTIL_CLS =
            "dev/emi/emi/EmiUtil";
    private static final String EMI_JEMI_PLUGIN_CLS =
            "dev/emi/emi/jemi/JemiPlugin";
    private static final String JEI_ITEM_STACK_LIST_FACTORY_CLS =
            "mezz/jei/library/plugins/vanilla/ingredients/ItemStackListFactory";
    private static final String JEI_SUBTYPE_MANAGER_CLS =
            "mezz/jei/library/ingredients/subtypes/SubtypeManager";
    private static final String IRIS_JCPP_PROCESSOR_CLS =
            "net/irisshaders/iris/shaderpack/preprocessor/JcppProcessor";
    private static final String IRIS_SHADER_PACK_CLS =
            "net/irisshaders/iris/shaderpack/ShaderPack";
    private static final String IRIS_ABS_PACK_PATH_CLS =
            "net/irisshaders/iris/shaderpack/include/AbsolutePackPath";
    private static final String IRIS_INCLUDE_PROC_CLS =
            "net/irisshaders/iris/shaderpack/include/IncludeProcessor";
    private static final String IRIS_COMMENT_DIRECTIVE_CLS =
            "net/irisshaders/iris/shaderpack/parsing/CommentDirectiveParser";
    private static final String IRIS_CONST_DIRECTIVE_CLS =
            "net/irisshaders/iris/shaderpack/parsing/ConstDirectiveParser";
    private static final String IRIS_PROGRAM_SOURCE_CLS =
            "net/irisshaders/iris/shaderpack/programs/ProgramSource";
    private static final String IRIS_OUTER_WRAPPED_RT_CLS =
            "net/irisshaders/iris/layer/OuterWrappedRenderType";
    private static final String IRIS_ENTITY_VERTEX_CLS =
            "net/irisshaders/iris/compat/sodium/impl/vertex_format/entity_xhfp/EntityVertex";
    private static final String MC_CLIENT_LEVEL_CLS =
            "net/minecraft/client/multiplayer/ClientLevel";
    private static final String FLW_STRING_UTIL_CLS =
            "dev/engine_room/flywheel/lib/util/StringUtil";
    private static final String MC_VIS_GRAPH_CLS =
            "net/minecraft/client/renderer/chunk/VisGraph";
    private static final String MC_RESOURCE_LOCATION_CLS =
            "net/minecraft/resources/ResourceLocation";
    private static final String MC_ITEM_STACK_CLS =
            "net/minecraft/world/item/ItemStack";
    private static final String EMF_ANIM_CONTEXT_CLS =
            "traben/entity_model_features/models/animation/EMFAnimationEntityContext";
    private static final String SODIUM_MESH_TASK_CLS =
            "me/jellysquid/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask";
    private static final String SODIUM_WORLD_RENDERER_CLS =
            "me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer";
    private static final String SODIUM_RADIX_SORT_CLS =
            "me/jellysquid/mods/sodium/client/util/sorting/RadixSort";
    private static final String SODIUM_WORKER_THREAD_CLS =
            "me/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder$WorkerThread";
    private static final String SODIUM_CHUNK_BUILDER_CLS =
            "me/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder";
    private static final String SODIUM_RENDER_SECTION_MGR_CLS =
            "me/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager";
    private static final String SMALLSHIPS_CANNON_MODEL_CLS =
            "com/talhanation/smallships/client/model/CannonModel";
    private static final String AMBIENT_DIMENSION_CLS =
            "team/creative/ambientsounds/dimension/AmbientDimension";
    private static final String QUARK_VARIANT_ANIMAL_CLIENT_CLS =
            "org/violetmoon/quark/content/client/module/VariantAnimalTexturesModule$Client";
    private static final String JM_THREAD_FACTORY_CLS =
            "journeymap/common/thread/JMThreadFactory";
    private static final String GECKOLIB_GEO_RENDERER_CLS =
            "software/bernie/geckolib/renderer/GeoRenderer";
    private static final String GECKOLIB_RENDER_UTILS_CLS =
            "software/bernie/geckolib/util/RenderUtils";
    private static final String GECKOLIB_ANIM_POINT_QUEUE_CLS =
            "software/bernie/geckolib/core/keyframe/AnimationPointQueue";
    private static final String GECKOLIB_ANIM_POINT_CLS =
            "software/bernie/geckolib/core/keyframe/AnimationPoint";
    private static final String GECKOLIB_ANIM_CTRL_CLS =
            "software/bernie/geckolib/core/animation/AnimationController";
    private static final String GECKOLIB_BONE_ANIM_QUEUE_CLS =
            "software/bernie/geckolib/core/keyframe/BoneAnimationQueue";
    private static final String GECKOLIB_LAZY_VAR_CLS =
            "software/bernie/geckolib/core/molang/LazyVariable";
    private static final String GECKOLIB_MOLANG_PARSER_CLS =
            "software/bernie/geckolib/core/molang/MolangParser";
    private static final String MC_ITEM_RENDERER_CLS =
            "net/minecraft/client/renderer/entity/ItemRenderer";
    private static final String MC_ENTITY_CLS =
            "net/minecraft/world/entity/Entity";
    private static final String PARTICLERAIN_WIND_MGR_CLS =
            "pigcart/particlerain/WindManager";
    private static final String EMBEDDIUM_BLOCK_RENDERER_REGISTRY_CLS =
            "org/embeddedt/embeddium/api/BlockRendererRegistry";
    private static final String GBF_IRIS_COMPAT_CLS =
            "com/moepus/gbf/IrisCompat";
    private static final String MC_FRUSTUM_CLS =
            "net/minecraft/client/renderer/culling/Frustum";
    private static final String IRIS_ADV_FRUSTUM_CLS =
            "net/irisshaders/iris/shadows/frustum/advanced/AdvancedShadowCullingFrustum";
    private static final String MC_BUFFER_BUILDER_CLS =
            "com/mojang/blaze3d/vertex/BufferBuilder";
    private static final String INDIGO_ABSTRACT_RENDER_CTX_CLS =
            "net/fabricmc/fabric/impl/client/indigo/renderer/render/AbstractRenderContext";
    private static final String FTB_MAP_REGION_DATA_CLS =
            "dev/ftb/mods/ftbchunks/client/map/MapRegionData";
    private static final String REMI_STACK_GROUP_MGR_CLS =
            "com/evandev/remi/feature/stackgroup/StackGroupManager";
    private static final String REMI_EMI_STACK_GROUP_CLS =
            "com/evandev/remi/feature/stackgroup/data/EmiStackGroup";
    private static final String REMI_ANIMAL_ARMOR_GROUP_CLS =
            "com/evandev/remi/feature/stackgroup/data/groups/AnimalArmorItemGroup";
    private static final String FORGE_REGISTRY_CLS =
            "net/minecraftforge/registries/ForgeRegistry";
    private static final String FORGE_I18N_CLS =
            "net/minecraftforge/common/ForgeI18n";
    private static final String CURIOS_HOOKS_CLS =
            "top/theillusivec4/curios/mixin/CuriosImplMixinHooks";
    private static final String CURIOS_CLIENT_EVENTS_CLS =
            "top/theillusivec4/curios/client/ClientEventHandler";
    private static final String MC_ITEM_BLOCK_RENDER_TYPES_CLS =
            "net/minecraft/client/renderer/ItemBlockRenderTypes";
    private static final String MC_NON_NULL_LIST_CLS =
            "net/minecraft/core/NonNullList";
    private static final String MC_FOOD_PROPERTIES_CLS =
            "net/minecraft/world/food/FoodProperties";
    private static final String MC_SUFFIX_ARRAY_CLS =
            "net/minecraft/client/searchtree/SuffixArray";
    private static final String JM_REGION_DATA_CLS =
            "journeymap/common/nbt/RegionData";
    private static final String JM_DATA_CACHE_CLS =
            "journeymap/client/data/DataCache";
    private static final String JM_CHUNK_MD_CLS =
            "journeymap/client/model/ChunkMD";
    private static final String JM_RGB_CLS =
            "journeymap/client/cartography/color/RGB";
    private static final String LIBTOOLTIPS_CLS =
            "com/wdiscute/libtooltips/Tooltips";
    private static final String MC_STRING_TAG_VISITOR_CLS =
            "net/minecraft/nbt/StringTagVisitor";
    private static final String MC_LEVEL_CLS =
            "net/minecraft/world/level/Level";
    private static final String VB_LEAF_COLORS_CLS =
            "com/blackgear/vanillabackport/client/api/color/LeafColors";
    private static final String VB122_LEAF_COLORS_CLS =
            "com/blackgear/vanillabackport/client/api/modules/falling_leaves/LeafColors";
    private static final String VB_LEASH_RENDERER_CLS =
            "com/blackgear/vanillabackport/common/api/leash/LeashFeatureRenderer";
    private static final String VB122_LEASH_RENDERER_CLS =
            "com/blackgear/vanillabackport/common/api/modules/leash_behavior/LeashFeatureRenderer";
    private static final String RHINO_JAVA_MEMBERS_CLS =
            "dev/latvian/mods/rhino/JavaMembers";
    private static final String BLUEPRINT_REMOLDED_CLS =
            "com/teamabnormals/blueprint/common/remolder/RemoldedResourceManager";
    private static final String TANUKI_ROTATING_CLS = "tanukidecor/block/RotatingBlock";
    private static final String TANUKI_MULTIBLOCK_CLS = "tanukidecor/block/RotatingMultiblock";
    private static final String TANUKI_TALL_ROTATING_CLS = "tanukidecor/block/RotatingTallBlock";
    private static final String TANUKI_WIDE_CLS = "tanukidecor/block/RotatingWideBlock";
    private static final String TANUKI_TALL_CLS = "tanukidecor/block/TallBlock";
    private static final String TANUKI_PLASMA_CLS = "tanukidecor/block/misc/PlasmaBallBlock";
    private static final String MC_CRASH_REPORT_CLS = "net/minecraft/CrashReport";
    private static final String MC_SYSTEM_REPORT_CLS = "net/minecraft/SystemReport";
    private static final String FORGE_GAME_DATA_CLS = "net/minecraftforge/registries/GameData";
    private static final String MYSTICAL_TREE_LORE_CLS = "net/mehvahdjukaar/mysticaloaktree/client/TreeLoreManager";
    private static final String KJS_GEN_PACK_CLS = "dev/latvian/mods/kubejs/script/data/GeneratedResourcePack";
    private static final String KJS_TAG_EVENT_CLS = "dev/latvian/mods/kubejs/server/tag/TagEventJS";
    private static final String FD_TOOL_ACTION_ING_CLS =
            "vectorwing/farmersdelight/common/crafting/ingredient/ToolActionIngredient";
    private static final String LOOTJS_RL_PATTERN_CLS =
            "com/almostreliable/lootjs/filters/ResourceLocationFilter$ByPattern";
    private static final String ALI_ABSTRACT_SERVER_CLS = "com/yanny/ali/network/AbstractServer";
    private static final String PAYLOAD_CLS = "dev/sunlit/bootagent/payload/BootJarPayload";
    private static Instrumentation extraInst;
    private static Module extraPayloadModule;

    private SunlitExtraTransformer() {
    }

    public static void install(Instrumentation inst, Module payloadModule) {
        extraInst = inst;
        extraPayloadModule = payloadModule;
        BootJarPayload.setFlatStateMapBytes(buildFlatStateMapBytes());
        if (inst == null) {
            return;
        }
        SunlitExtraTransformer transformer = new SunlitExtraTransformer();
        inst.addTransformer(transformer, true);
        try {
            Class<?> cfgCls = java.lang.module.Configuration.class;
            if (payloadModule != null) {
                Module base = cfgCls.getModule();
                if (base != null && base.isNamed() && !base.canRead(payloadModule)) {
                    inst.redefineModule(
                            base,
                            java.util.Set.of(payloadModule),
                            java.util.Map.of(),
                            java.util.Map.of(),
                            java.util.Set.of(),
                            java.util.Map.of());
                }
                ModuleLayer.boot().findModule("jdk.zipfs").ifPresent(zipfs -> {
                    if (zipfs.isNamed() && !zipfs.canRead(payloadModule)) {
                        inst.redefineModule(
                                zipfs,
                                java.util.Set.of(payloadModule),
                                java.util.Map.of(),
                                java.util.Map.of(),
                                java.util.Set.of(),
                                java.util.Map.of());
                    }
                });
            }
            inst.retransformClasses(cfgCls);
            for (Class<?> loadedCls : inst.getAllLoadedClasses()) {
                if ("jdk.nio.zipfs.ZipPath".equals(loadedCls.getName())) {
                    inst.retransformClasses(loadedCls);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        return switch (className) {
            case CONFIG_CLS -> transformConfiguration(loader, classfileBuffer);
            case ETF_PLUGIN_CLS -> transformEtfPlugin(loader, classfileBuffer);
            case RHINO_REMAP_CLS -> transformRemappingHelper(classfileBuffer);
            case JDK_ZIPPATH_CLS -> transformZipPath(loader, classfileBuffer, classBeingRedefined == null);
            case MF_SCAN_COMPACTOR_CLS -> transformScanDataCompactor(module, classfileBuffer, classBeingRedefined == null);
            case CLIENT_LANG_CLS -> transformClientLanguage(loader, classfileBuffer);
            case FLW_BACKEND_MGR_CLS -> transformFlywheelBackendManager(classfileBuffer);
            case MOONLIGHT_RES_TX_CLS -> transformMoonlightResTransformer(module, classfileBuffer);
            case EMF_RES_CACHING_CLS -> transformEmfResourceCaching(classfileBuffer);
            case MC_BOOTSTRAP_CLS -> transformMinecraftBootstrap(module, classfileBuffer);
            case CARRYON_LIST_HANDLER_CLS -> transformCarryOnListHandler(module, classfileBuffer);
            case EVERYCOMP_PENDING_CLS,
                 MCLIB_MATH_BUILDER_CLS,
                 EMF_MAPPINGS_CLS,
                 EMF_MANAGER_CLS,
                 EMF_MATH_PARSER_CLS,
                 FUSION_ENTITY_LOADER_CLS,
                 CSC_MATH_CLS,
                 CSC_TOML_EDITOR_CLS,
                 FM_LOADING_REQ_CLS,
                 MOONLIGHT_PATH_TRIE_CLS,
                 MIXIN_INJECTION_POINT_CLS,
                 AMBIENT_DIMENSION_CLS,
                 QUARK_VARIANT_ANIMAL_CLIENT_CLS -> transformStringMatches(module, classfileBuffer);
            case EMF_VAR_FACTORY_CLS -> transformEmfModelVariableFactory(module, classfileBuffer);
            case EMF_MATH_CLS -> transformEmfMath(classfileBuffer);
            case EMF_ANIM_CONTEXT_CLS -> transformEmfAnimationEntityContext(classfileBuffer);
            case EMF_ASM_ANIM_HANDLER_CLS -> transformEmfAsmAnimationHandler(classfileBuffer);
            case MC_BAKED_QUAD_CLS -> transformBakedQuad(classfileBuffer);
            case EMBERBIUM_MODULE_SCANNER_CLS -> transformEmberbiumModuleScanner(module, classfileBuffer);
            case AMENDMENTS_WALL_LANTERN_CLS -> transformWallLanternBlock(module, classfileBuffer);
            case EMF_JEM_DATA_CLS -> transformEmfJemData(module, classfileBuffer);
            case KJS_ICON_PROVIDER_CLS -> transformKubeJsIconProvider(module, classfileBuffer);
            case RHINO_JAVA_MEMBERS_CLS -> transformRhinoJavaMembers(module, classfileBuffer);
            case "dev/latvian/mods/rhino/NativeJavaMethod" -> classBeingRedefined == null
                    ? transformRhinoNativeJavaMethod(classfileBuffer) : null;
            case BLUEPRINT_REMOLDED_CLS -> transformBlueprintRemolded(module, classfileBuffer);
            case ALI_TOOLTIP_NODE_CLS -> transformAliTooltipNode(classfileBuffer);
            case ALI_TOOLTIP_BUILDER_CLS -> transformAliTooltipBuilder(classfileBuffer);
            case CREATE_SPOUT_CATEGORY_CLS -> transformCreateSpoutCategory(classfileBuffer);
            case CREATE_TOOLTIP_HELPER_CLS -> transformCreateTooltipHelper(module, classfileBuffer);
            case EMI_RELOAD_MANAGER_CLS -> transformEmiReloadManager(classfileBuffer);
            case EMI_RECIPES_CLS -> transformEmiRecipes(classfileBuffer);
            case EMI_RECIPES_MANAGER_CLS -> transformEmiRecipesManager(classfileBuffer);
            case EMI_LIST_INGREDIENT_CLS -> transformEmiListIngredient(classfileBuffer);
            case EMI_INGREDIENT_CLS -> transformEmiIngredient(classfileBuffer);
            case EMI_DATA_CLS -> transformEmiData(module, classfileBuffer);
            case EMI_HIDDEN_CLS -> transformEmiHidden(classfileBuffer);
            case EMI_ITEM_STACK_CLS -> transformEmiItemStack(classfileBuffer);
            case EMI_TAG_INGREDIENT_CLS -> transformEmiTagIngredient(classfileBuffer);
            case EMI_TAGS_CLS -> transformEmiTags(module, classfileBuffer);
            case EMI_SHAPED_RECIPE_CLS -> transformEmiShapedRecipe(module, classfileBuffer);
            case EMI_ANVIL_ENCHANT_CLS -> transformEmiAnvilEnchantRecipe(module, classfileBuffer);
            case EMI_UTIL_CLS -> transformEmiUtil(module, classfileBuffer);
            case EMI_JEMI_PLUGIN_CLS -> transformEmiJemiPlugin(module, classfileBuffer);
            case JEI_ITEM_STACK_LIST_FACTORY_CLS -> transformJeiItemStackListFactory(classfileBuffer);
            case JEI_SUBTYPE_MANAGER_CLS -> transformJeiSubtypeManager(classfileBuffer);
            case IRIS_JCPP_PROCESSOR_CLS -> transformJcppProcessor(classfileBuffer);
            case IRIS_SHADER_PACK_CLS -> transformIrisShaderPack(classfileBuffer);
            case IRIS_ABS_PACK_PATH_CLS -> transformIrisAbsolutePackPath(classfileBuffer);
            case IRIS_INCLUDE_PROC_CLS -> transformIrisIncludeProcessor(classfileBuffer);
            case IRIS_COMMENT_DIRECTIVE_CLS -> transformIrisCommentDirectiveParser(module, classfileBuffer);
            case IRIS_CONST_DIRECTIVE_CLS -> transformIrisConstDirectiveParser(module, classfileBuffer);
            case IRIS_PROGRAM_SOURCE_CLS -> transformIrisProgramSource(module, classfileBuffer);
            case IRIS_OUTER_WRAPPED_RT_CLS -> transformIrisOuterWrappedRenderType(module, classfileBuffer);
            case IRIS_ENTITY_VERTEX_CLS -> transformIrisEntityVertex(classfileBuffer);
            case MC_CLIENT_LEVEL_CLS -> transformClientLevel(classfileBuffer);
            case FLW_STRING_UTIL_CLS -> transformFlywheelStringUtil(module, classfileBuffer);
            case MC_VIS_GRAPH_CLS -> transformVisGraph(classfileBuffer);
            case MC_RESOURCE_LOCATION_CLS -> transformResourceLocation(classfileBuffer);
            case MC_ITEM_STACK_CLS -> transformItemStackTooltips(classfileBuffer);
            case SODIUM_MESH_TASK_CLS -> transformSodiumChunkBuilderMeshingTask(classfileBuffer);
            case SODIUM_WORLD_RENDERER_CLS -> transformSodiumWorldRenderer(classfileBuffer);
            case SODIUM_RADIX_SORT_CLS -> transformSodiumRadixSort(classfileBuffer);
            case SODIUM_WORKER_THREAD_CLS -> transformSodiumWorkerThread(classfileBuffer);
            case SODIUM_CHUNK_BUILDER_CLS -> transformSodiumChunkBuilder(classfileBuffer);
            case SODIUM_RENDER_SECTION_MGR_CLS -> transformSodiumRenderSectionManager(classfileBuffer);
            case SMALLSHIPS_CANNON_MODEL_CLS -> transformSmallShipsCannonModel(classfileBuffer);
            case JM_THREAD_FACTORY_CLS -> transformJourneyMapThreadFactory(classfileBuffer);
            case GECKOLIB_GEO_RENDERER_CLS -> transformGeckoLibGeoRenderer(classfileBuffer);
            case GECKOLIB_RENDER_UTILS_CLS -> transformGeckoLibRenderUtils(classfileBuffer);
            case GECKOLIB_ANIM_POINT_QUEUE_CLS -> transformGeckoLibAnimationPointQueue(classfileBuffer);
            case GECKOLIB_ANIM_POINT_CLS -> transformGeckoLibAnimationPoint(classfileBuffer);
            case GECKOLIB_ANIM_CTRL_CLS,
                 GECKOLIB_BONE_ANIM_QUEUE_CLS -> transformGeckoLibAnimationPointCallers(classfileBuffer);
            case GECKOLIB_LAZY_VAR_CLS -> transformGeckoLibLazyVariable(classfileBuffer);
            case GECKOLIB_MOLANG_PARSER_CLS -> transformGeckoLibMolangParser(classfileBuffer);
            case MC_ITEM_RENDERER_CLS -> transformItemRenderer(classfileBuffer);
            case MC_ENTITY_CLS -> transformEntity(classfileBuffer);
            case PARTICLERAIN_WIND_MGR_CLS -> transformParticleRainWindManager(classfileBuffer);
            case EMBEDDIUM_BLOCK_RENDERER_REGISTRY_CLS -> transformEmbeddiumBlockRendererRegistry(classfileBuffer);
            case GBF_IRIS_COMPAT_CLS -> transformGbfIrisCompat(classfileBuffer);
            case MC_FRUSTUM_CLS -> transformFrustum(classfileBuffer);
            case IRIS_ADV_FRUSTUM_CLS -> transformIrisShadowFrustum(classfileBuffer);
            case MC_BUFFER_BUILDER_CLS -> transformBufferBuilder(classfileBuffer);
            case INDIGO_ABSTRACT_RENDER_CTX_CLS -> transformIndigoAbstractRenderContext(classfileBuffer);
            case FTB_MAP_REGION_DATA_CLS -> transformFtbMapRegionData(module, classfileBuffer);
            case REMI_STACK_GROUP_MGR_CLS -> transformReliableEmiStackGroupManager(classfileBuffer);
            case REMI_EMI_STACK_GROUP_CLS -> transformRemiEmiStackGroup(classfileBuffer);
            case REMI_ANIMAL_ARMOR_GROUP_CLS -> transformRemiAnimalArmorGroup(classfileBuffer);
            case FORGE_REGISTRY_CLS -> transformForgeRegistry(classfileBuffer);
            case FORGE_I18N_CLS -> transformForgeI18n(classfileBuffer);
            case CURIOS_HOOKS_CLS -> transformCuriosImplMixinHooks(module, classfileBuffer);
            case CURIOS_CLIENT_EVENTS_CLS -> transformCuriosClientEventHandler(classfileBuffer);
            case MC_ITEM_BLOCK_RENDER_TYPES_CLS -> transformItemBlockRenderTypes(module, classfileBuffer);
            case MC_NON_NULL_LIST_CLS -> transformNonNullList(classfileBuffer);
            case MC_FOOD_PROPERTIES_CLS -> transformFoodProperties(classfileBuffer);
            case MC_SUFFIX_ARRAY_CLS -> transformSuffixArray(classfileBuffer);
            case JM_REGION_DATA_CLS -> transformJourneyMapRegionData(module, classfileBuffer);
            case JM_DATA_CACHE_CLS -> transformJourneyMapDataCache(module, classfileBuffer);
            case JM_CHUNK_MD_CLS -> transformJourneyMapChunkMD(classfileBuffer);
            case JM_RGB_CLS -> transformJourneyMapRgb(module, classfileBuffer);
            case LIBTOOLTIPS_CLS -> transformLibTooltips(classfileBuffer);
            case MC_STRING_TAG_VISITOR_CLS -> transformStringTagVisitor(module, classfileBuffer);
            case MC_LEVEL_CLS -> transformMinecraftLevel(classfileBuffer);
            case VB_LEAF_COLORS_CLS, VB122_LEAF_COLORS_CLS -> transformVanillaBackportLeafColors(classfileBuffer);
            case VB_LEASH_RENDERER_CLS, VB122_LEASH_RENDERER_CLS -> transformVanillaBackportLeashRenderer(classfileBuffer);
            case TANUKI_ROTATING_CLS,
                 TANUKI_MULTIBLOCK_CLS,
                 TANUKI_TALL_ROTATING_CLS,
                 TANUKI_WIDE_CLS,
                 TANUKI_TALL_CLS,
                 TANUKI_PLASMA_CLS -> transformTanukiBlockShapes(className, classfileBuffer);
            case MC_CRASH_REPORT_CLS -> transformCrashReportPreload(module, classfileBuffer);
            case MC_SYSTEM_REPORT_CLS -> transformSystemReportPreload(module, classfileBuffer);
            case FORGE_GAME_DATA_CLS -> transformForgeGameData(classfileBuffer);
            case MYSTICAL_TREE_LORE_CLS -> transformMysticalTreeLore(classfileBuffer);
            case KJS_GEN_PACK_CLS -> transformKubeJsGeneratedResourcePack(classfileBuffer);
            case KJS_TAG_EVENT_CLS -> transformKubeJsTagEventJS(classfileBuffer);
            case FD_TOOL_ACTION_ING_CLS -> transformToolActionIngredient(classfileBuffer);
            case LOOTJS_RL_PATTERN_CLS -> transformLootJsByPattern(classfileBuffer);
            case ALI_ABSTRACT_SERVER_CLS -> transformAliAbstractServer(classfileBuffer);
            default -> null;
        };
    }

    public static byte[] transformConfiguration(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"reads".equals(mn.name)
                    || !"(Ljava/lang/module/ResolvedModule;)Ljava/util/Set;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/util/Set".equals(min.owner)
                        && "copyOf".equals(min.name)
                        && "(Ljava/util/Collection;)Ljava/util/Set;".equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastUnmodifiableSet";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformClientLanguage(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasStorageField = false;
        if (cn.fields != null) {
            for (var fn : cn.fields) {
                if ("f_118910_".equals(fn.name) && "Ljava/util/Map;".equals(fn.desc)) {
                    hasStorageField = true;
                    break;
                }
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("m_264420_".equals(mn.name)
                    && "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;"
                            .equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "com/google/common/collect/ImmutableMap".equals(min.owner)
                            && "copyOf".equals(min.name)
                            && "(Ljava/util/Map;)Lcom/google/common/collect/ImmutableMap;".equals(min.desc)) {
                        min.owner = "java/util/Collections";
                        min.name = "unmodifiableMap";
                        min.desc = "(Ljava/util/Map;)Ljava/util/Map;";
                        min.itf = false;
                        patched = true;
                    }
                }
                continue;
            }
            if (!"iris$lookupOverriddenEntry".equals(mn.name)
                    || !"(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            InsnList guard = new InsnList();
            LabelNode cont = new LabelNode();
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, PAYLOAD_CLS, "irisDeferredPending", "Z"));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            guard.add(new InsnNode(Opcodes.ACONST_NULL));
            guard.add(new InsnNode(Opcodes.ARETURN));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            if (hasStorageField) {
                LabelNode cont2 = new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new FieldInsnNode(Opcodes.GETFIELD, CLIENT_LANG_CLS, "f_118910_", "Ljava/util/Map;"));
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, cont2));
                guard.add(new InsnNode(Opcodes.ACONST_NULL));
                guard.add(new InsnNode(Opcodes.ARETURN));
                guard.add(cont2);
                guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            }
            mn.instructions.insert(guard);
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformFlywheelBackendManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"onEndClientResourceReload".equals(mn.name)
                    || !"(Z)V".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            InsnList guard = new InsnList();
            LabelNode cont = new LabelNode();
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, PAYLOAD_CLS, "irisDeferredPending", "Z"));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEtfPlugin(ClassLoader loader, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("hasClass".equals(mn.name) && "(Ljava/lang/String;)Z".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables.clear();
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new LdcInsnNode(Type.getObjectType(ETF_PLUGIN_CLS)));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "classResourceExists",
                        "(Ljava/lang/String;Ljava/lang/ClassLoader;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformRemappingHelper(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getMinecraftRemapper".equals(mn.name) && (mn.access & Opcodes.ACC_SYNCHRONIZED) == 0) {
                mn.access |= Opcodes.ACC_SYNCHRONIZED;
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformZipPath(ClassLoader loader, byte[] bytes) {
        return transformZipPath(loader, bytes, true);
    }

    public static byte[] transformZipPath(ClassLoader loader, byte[] bytes, boolean canAddField) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        boolean hasCachedField = false;
        for (FieldNode fn : cn.fields) {
            if ("sunlitCachedStr".equals(fn.name)) {
                hasCachedField = true;
                break;
            }
        }
        boolean canCacheToString = canAddField && !hasCachedField;
        for (MethodNode mn : cn.methods) {
            if ("normalize".equals(mn.name)
                    && "(Ljava/lang/String;)[B".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "jdk/nio/zipfs/ZipCoder".equals(min.owner)
                            && "isUTF8".equals(min.name)
                            && "()Z".equals(min.desc)
                            && min.getNext() instanceof JumpInsnNode jmp
                            && jmp.getOpcode() == Opcodes.IFEQ) {
                        InsnList fast = new InsnList();
                        LabelNode slow = new LabelNode();
                        fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        fast.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                PAYLOAD_CLS,
                                "fastZipNormalizeAscii",
                                "(Ljava/lang/String;)[B",
                                false));
                        fast.add(new InsnNode(Opcodes.DUP));
                        fast.add(new JumpInsnNode(Opcodes.IFNULL, slow));
                        fast.add(new InsnNode(Opcodes.ARETURN));
                        fast.add(slow);
                        fast.add(new InsnNode(Opcodes.POP));
                        mn.instructions.insert(jmp, fast);
                        patched = true;
                        break;
                    }
                }
            } else if (canCacheToString
                    && "toString".equals(mn.name)
                    && "()Ljava/lang/String;".equals(mn.desc)
                    && mn.instructions != null) {
                cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlitCachedStr", "Ljava/lang/String;", null, null));
                InsnList head = new InsnList();
                LabelNode miss = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new FieldInsnNode(Opcodes.GETFIELD, JDK_ZIPPATH_CLS, "sunlitCachedStr", "Ljava/lang/String;"));
                head.add(new InsnNode(Opcodes.DUP));
                head.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(miss);
                head.add(new InsnNode(Opcodes.POP));
                mn.instructions.insert(head);
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        InsnList store = new InsnList();
                        store.add(new InsnNode(Opcodes.DUP));
                        store.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        store.add(new InsnNode(Opcodes.SWAP));
                        store.add(new FieldInsnNode(
                                Opcodes.PUTFIELD, JDK_ZIPPATH_CLS, "sunlitCachedStr", "Ljava/lang/String;"));
                        mn.instructions.insertBefore(insn, store);
                    }
                }
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : ClassLoader.getPlatformClassLoader();
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Most reflected methods never resolve competing overloads, so allocate their caches on demand. */
    public static byte[] transformRhinoNativeJavaMethod(byte[] bytes) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!"db99d3a7e01384927a450e46827729e509c8c0d410c656ba3c8551fb06f95a15".equals(hash)) return null;
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        String list = "java/util/concurrent/CopyOnWriteArrayList";
        String desc = "L" + list + ";";
        String getter = "sunlit$getOverloadCache";
        String initialize = "sunlit$initializeOverloadCache";
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (FieldNode field : node.fields) {
            if ("overloadCache".equals(field.name)) {
                field.access = (field.access & ~Opcodes.ACC_FINAL) | Opcodes.ACC_VOLATILE;
            }
        }
        for (MethodNode method : node.methods) {
            var code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof FieldInsnNode field) || !node.name.equals(field.owner)
                        || !"overloadCache".equals(field.name)) continue;
                if (field.getOpcode() == Opcodes.PUTFIELD) {
                    // The reviewed constructors use ALOAD 0; NEW; DUP; INVOKESPECIAL; PUTFIELD.
                    for (int j = i - 4; j <= i; j++) method.instructions.remove(code.get(j));
                } else if (field.getOpcode() == Opcodes.GETFIELD) {
                    method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESPECIAL,
                            node.name, getter, "()" + desc, false));
                }
            }
        }
        var read = new MethodNode(Opcodes.ACC_PRIVATE, getter, "()" + desc, null, null);
        var create = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED, initialize, "()" + desc, null, null);
        for (MethodNode method : new MethodNode[] {read, create}) {
            LabelNode ready = new LabelNode();
            InsnList code = method.instructions;
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "overloadCache", desc));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
            code.add(new InsnNode(Opcodes.POP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (method == read) {
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, initialize, "()" + desc, false));
            } else {
                code.add(new TypeInsnNode(Opcodes.NEW, list));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, list, "<init>", "()V", false));
                code.add(new InsnNode(Opcodes.DUP_X1));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "overloadCache", desc));
            }
            code.add(ready);
            code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {list}));
            code.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(method);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static byte[] transformRhinoJavaMembers(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Class".equals(min.owner)
                        && "getDeclaredMethods".equals(min.name)
                        && "()[Ljava/lang/reflect/Method;".equals(min.desc)) {
                    min.setOpcode(Opcodes.INVOKESTATIC);
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastGetDeclaredMethods";
                    min.desc = "(Ljava/lang/Class;)[Ljava/lang/reflect/Method;";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformBlueprintRemolded(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getResourceFunction".equals(mn.name)
                    && "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/function/Function;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList head = new InsnList();
                LabelNode cont = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new FieldInsnNode(
                        Opcodes.GETFIELD, BLUEPRINT_REMOLDED_CLS, "fileExtensionToEntries", "Ljava/util/HashMap;"));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135827_",
                        "()Ljava/lang/String;",
                        false));
                head.add(new VarInsnNode(Opcodes.ALOAD, 1));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/resources/ResourceLocation",
                        "m_135815_",
                        "()Ljava/lang/String;",
                        false));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "canSkipRemoldedLookup",
                        "(Ljava/util/HashMap;Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                head.add(new InsnNode(Opcodes.ACONST_NULL));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(cont);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
                mn.instructions.insert(head);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformScanDataCompactor(byte[] bytes) {
        return transformScanDataCompactor(null, bytes, true);
    }

    public static byte[] transformScanDataCompactor(Module module, byte[] bytes, boolean canAddMethods) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedCall = false;
        int strHits = 0;
        MethodNode compact0 = null;
        MethodNode compact2 = null;
        MethodNode lambda2 = null;
        MethodNode lambda3 = null;
        boolean hasHelper = false;
        for (MethodNode mn : cn.methods) {
            if ("sunlitCompactOne".equals(mn.name)) {
                hasHelper = true;
            } else if ("compact".equals(mn.name) && "()V".equals(mn.desc)) {
                compact0 = mn;
            } else if ("compact".equals(mn.name)
                    && "(Lnet/minecraftforge/forgespi/language/ModFileScanData;Ljava/lang/String;)V".equals(mn.desc)) {
                compact2 = mn;
            } else if ("lambda$compact$2".equals(mn.name)
                    && "(Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;)Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;"
                            .equals(mn.desc)) {
                lambda2 = mn;
            } else if ("lambda$compact$3".equals(mn.name)
                    && "(Lnet/minecraftforge/forgespi/language/ModFileScanData$ClassData;)Lnet/minecraftforge/forgespi/language/ModFileScanData$ClassData;"
                            .equals(mn.desc)) {
                lambda3 = mn;
            }
            if (!"lambda$compact$0".equals(mn.name)
                    || !"(Lnet/minecraftforge/forgespi/language/ModFileScanData$AnnotationData;)Z".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "org/objectweb/asm/Type".equals(min.owner)
                        && "getClassName".equals(min.name)
                        && "()Ljava/lang/String;".equals(min.desc)) {
                    min.name = "getDescriptor";
                    patchedCall = true;
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                    switch (s) {
                        case "kotlin.jvm." -> { ldc.cst = "Lkotlin/jvm/"; strHits++; }
                        case "scala.reflect." -> { ldc.cst = "Lscala/reflect/"; strHits++; }
                        case "org.spongepowered.asm.mixin." -> { ldc.cst = "Lorg/spongepowered/asm/mixin/"; strHits++; }
                        case "com.llamalad7.mixinextras." -> { ldc.cst = "Lcom/llamalad7/mixinextras/"; strHits++; }
                        case "org.jetbrains.annotations." -> { ldc.cst = "org/jetbrains/annotations/"; strHits++; }
                        case "javax.annotation." -> { ldc.cst = "javax/annotation/"; strHits++; }
                        case "kotlin.Metadata" -> { ldc.cst = "kotlin/Metadata;"; strHits++; }
                        case "net.minecraftforge.api.distmarker.OnlyIn" -> { ldc.cst = "Lnet/minecraftforge/api/distmarker/OnlyIn;"; strHits++; }
                        default -> { }
                    }
                }
            }
        }
        boolean patchedBase = patchedCall && strHits == 8;
        boolean patchedParallel = false;
        if (canAddMethods && !hasHelper && compact0 != null && compact2 != null && lambda2 != null && lambda3 != null
                && patchScanDataParallel(cn, compact0, compact2, lambda2, lambda3)) {
            patchedParallel = true;
        }
        if (!patchedBase && !patchedParallel) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean patchScanDataParallel(
            ClassNode cn,
            MethodNode compact0,
            MethodNode compact2,
            MethodNode lambda2,
            MethodNode lambda3) {
        final String setCls = "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet";
        int replacedAddOrGet = 0;
        for (MethodNode lm : new MethodNode[] {lambda2, lambda3}) {
            if (lm.instructions == null) {
                return false;
            }
            for (AbstractInsnNode insn : lm.instructions.toArray()) {
                if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.GETSTATIC
                        && MF_SCAN_COMPACTOR_CLS.equals(fin.owner)
                        && "TYPES".equals(fin.name)) {
                    AbstractInsnNode a1 = nextRealInsn(fin);
                    AbstractInsnNode a2 = nextRealInsn(a1);
                    AbstractInsnNode a3 = nextRealInsn(a2);
                    if (a1 instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD
                            && a2 instanceof MethodInsnNode getter && getter.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && a3 instanceof MethodInsnNode addOrGet
                            && addOrGet.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && setCls.equals(addOrGet.owner)
                            && "addOrGet".equals(addOrGet.name)
                            && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(addOrGet.desc)) {
                        lm.instructions.remove(fin);
                        addOrGet.setOpcode(Opcodes.INVOKESTATIC);
                        addOrGet.owner = PAYLOAD_CLS;
                        addOrGet.name = "internScanType";
                        addOrGet.itf = false;
                        replacedAddOrGet++;
                    }
                }
            }
        }
        boolean replacedFn = false;
        for (AbstractInsnNode insn : lambda3.instructions.toArray()) {
            if (insn instanceof FieldInsnNode fin
                    && fin.getOpcode() == Opcodes.GETSTATIC
                    && MF_SCAN_COMPACTOR_CLS.equals(fin.owner)
                    && "TYPES".equals(fin.name)) {
                AbstractInsnNode d1 = nextRealInsn(fin);
                AbstractInsnNode req = nextRealInsn(d1);
                AbstractInsnNode pop = nextRealInsn(req);
                AbstractInsnNode indy = nextRealInsn(pop);
                if (d1 != null && d1.getOpcode() == Opcodes.DUP
                        && req instanceof MethodInsnNode reqM && "requireNonNull".equals(reqM.name)
                        && pop != null && pop.getOpcode() == Opcodes.POP
                        && indy instanceof InvokeDynamicInsnNode indyNode
                        && "(Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;)Ljava/util/function/Function;"
                                .equals(indyNode.desc)) {
                    lmRemoveAndReplaceFn(lambda3.instructions, fin, d1, req, pop, indy);
                    replacedFn = true;
                    break;
                }
            }
        }
        if (replacedAddOrGet != 4 || !replacedFn) {
            return false;
        }

        compact2.access = (compact2.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;

        String modFileInfoCls = "net/minecraftforge/forgespi/language/IModFileInfo";
        String modFileCls = "net/minecraftforge/forgespi/locating/IModFile";
        String scanDataCls = "net/minecraftforge/forgespi/language/ModFileScanData";
        MethodNode compactOne = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlitCompactOne",
                "(Ljava/lang/Object;)V",
                null,
                null);
        LabelNode lTryStart = new LabelNode();
        LabelNode lTryEnd = new LabelNode();
        LabelNode lCatch = new LabelNode();
        LabelNode lRet = new LabelNode();
        InsnList oil = compactOne.instructions;
        oil.add(new VarInsnNode(Opcodes.ALOAD, 0));
        oil.add(new TypeInsnNode(Opcodes.INSTANCEOF, modFileInfoCls));
        oil.add(new JumpInsnNode(Opcodes.IFEQ, lRet));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 0));
        oil.add(new TypeInsnNode(Opcodes.CHECKCAST, modFileInfoCls));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 1));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 1));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileInfoCls, "getFile", "()L" + modFileCls + ";", true));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 2));
        oil.add(lTryStart);
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getScanResult", "()L" + scanDataCls + ";", true));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getFileName", "()Ljava/lang/String;", true));
        oil.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MF_SCAN_COMPACTOR_CLS, "compact", "(L" + scanDataCls + ";Ljava/lang/String;)V", false));
        oil.add(lTryEnd);
        oil.add(new JumpInsnNode(Opcodes.GOTO, lRet));
        oil.add(lCatch);
        oil.add(new FrameNode(
                Opcodes.F_FULL,
                3,
                new Object[] {"java/lang/Object", modFileInfoCls, modFileCls},
                1,
                new Object[] {"java/lang/Throwable"}));
        oil.add(new VarInsnNode(Opcodes.ASTORE, 3));
        oil.add(new FieldInsnNode(Opcodes.GETSTATIC, "org/embeddedt/modernfix/ModernFix", "LOGGER", "Lorg/apache/logging/log4j/Logger;"));
        oil.add(new LdcInsnNode("An error occured while compacting {}"));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 2));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, modFileCls, "getFileName", "()Ljava/lang/String;", true));
        oil.add(new VarInsnNode(Opcodes.ALOAD, 3));
        oil.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true));
        oil.add(lRet);
        oil.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {"java/lang/Object"}, 0, new Object[0]));
        oil.add(new InsnNode(Opcodes.RETURN));
        compactOne.tryCatchBlocks.add(new TryCatchBlockNode(lTryStart, lTryEnd, lCatch, "java/lang/Throwable"));
        cn.methods.add(compactOne);

        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, MF_SCAN_COMPACTOR_CLS, "sunlitCompactOne", "(Ljava/lang/Object;)V", false);
        InsnList c0 = new InsnList();
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/ModList", "get", "()Lnet/minecraftforge/fml/ModList;", false));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/fml/ModList", "getModFiles", "()Ljava/util/List;", false));
        c0.add(new InvokeDynamicInsnNode(
                "accept",
                "()Ljava/util/function/Consumer;",
                bsm,
                Type.getMethodType("(Ljava/lang/Object;)V"),
                impl,
                Type.getMethodType("(Ljava/lang/Object;)V")));
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "parallelForEach", "(Ljava/util/List;Ljava/util/function/Consumer;)V", false));
        c0.add(new FieldInsnNode(Opcodes.GETSTATIC, MF_SCAN_COMPACTOR_CLS, "TYPES", "L" + setCls + ";"));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, setCls, "clear", "()V", false));
        c0.add(new FieldInsnNode(Opcodes.GETSTATIC, MF_SCAN_COMPACTOR_CLS, "TYPES", "L" + setCls + ";"));
        c0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, setCls, "trim", "()Z", false));
        c0.add(new InsnNode(Opcodes.POP));
        c0.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "clearScanTypes", "()V", false));
        c0.add(new InsnNode(Opcodes.RETURN));
        compact0.instructions = c0;
        if (compact0.tryCatchBlocks != null) {
            compact0.tryCatchBlocks.clear();
        }
        if (compact0.localVariables != null) {
            compact0.localVariables.clear();
        }
        return true;
    }

    private static void lmRemoveAndReplaceFn(
            InsnList list,
            AbstractInsnNode fin,
            AbstractInsnNode d1,
            AbstractInsnNode req,
            AbstractInsnNode pop,
            AbstractInsnNode indy) {
        list.insertBefore(
                indy,
                new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "internScanTypeFn", "()Ljava/util/function/Function;", false));
        list.remove(fin);
        list.remove(d1);
        list.remove(req);
        list.remove(pop);
        list.remove(indy);
    }

    private static AbstractInsnNode nextRealInsn(AbstractInsnNode node) {
        AbstractInsnNode cur = node == null ? null : node.getNext();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    /**
     * Generates {@code com/google/common/collect/SunlitFlatStateMap extends ImmutableMap}.
     * Zero {@code ImmutableMapEntry}, zero hash table, shares {@code keys[]} across all states of a block,
     * and clones {@code vals[]} once per state.
     */
    public static byte[] buildFlatStateMapBytes() {
        String self = "com/google/common/collect/SunlitFlatStateMap";
        String imMap = "com/google/common/collect/ImmutableMap";
        String imSet = "com/google/common/collect/ImmutableSet";
        String imCol = "com/google/common/collect/ImmutableCollection";
        String imList = "com/google/common/collect/ImmutableList";
        String imSetBuilder = "com/google/common/collect/ImmutableSet$Builder";
        String entryCls = "java/util/AbstractMap$SimpleImmutableEntry";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, self, null, imMap, null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "keys", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "vals", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "hash", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "lastKeys", "[Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "lastKeySet", "L" + imSet + ";", null, null).visitEnd();

        // <init>(Object[] keys, Object[] vals)
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([Ljava/lang/Object;[Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, imMap, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[Ljava/lang/Object;", "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // size()
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // isPartialView() -> false
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isPartialView", "()Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // get(Object key)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        var getNotNull = new Label();
        var loop1Cond = new Label();
        var loop1End = new Label();
        var loop1Hit = new Label();
        var loop2Cond = new Label();
        var loop2Hit = new Label();
        var notFound = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, getNotNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(getNotNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loop1Cond);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loop1End);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, loop1Hit);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop1Cond);
        mv.visitLabel(loop1Hit);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(loop1End);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(loop2Cond);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, notFound);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, loop2Hit);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop2Cond);
        mv.visitLabel(loop2Hit);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notFound);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // containsKey(Object key) -> get(key) != null
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "containsKey", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var ckFalse = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, self, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitJumpInsn(Opcodes.IFNULL, ckFalse);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(ckFalse);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // containsValue(Object val)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "containsValue", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var cvNonNull = new Label();
        var cvLoop = new Label();
        var cvEnd = new Label();
        var cvHit = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, cvNonNull);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cvNonNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitLabel(cvLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, cvEnd);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPEQ, cvHit);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, cvHit);
        mv.visitIincInsn(3, 1);
        mv.visitJumpInsn(Opcodes.GOTO, cvLoop);
        mv.visitLabel(cvHit);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(cvEnd);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // hashCode() -> cached sum of (keys[i].hashCode() ^ vals[i].hashCode())
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        mv.visitCode();
        var hcCompute = new Label();
        var hcLoop = new Label();
        var hcDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "hash", "I");
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFEQ, hcCompute);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(hcCompute);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitLabel(hcLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, hcDone);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
        mv.visitInsn(Opcodes.IXOR);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, hcLoop);
        mv.visitLabel(hcDone);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD, self, "hash", "I");
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // equals(Object o)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        mv.visitCode();
        var eqNotSame = new Label();
        var eqFallback = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, eqNotSame);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(eqNotSame);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, self);
        mv.visitJumpInsn(Opcodes.IFEQ, eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, self);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "flatArrayEquals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(eqFallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, imMap, "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createKeySet() -> 1-slot cached ImmutableSet.copyOf(keys)
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createKeySet", "()L" + imSet + ";", null, null);
        mv.visitCode();
        var ksMiss = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETSTATIC, self, "lastKeys", "[Ljava/lang/Object;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, ksMiss);
        mv.visitFieldInsn(Opcodes.GETSTATIC, self, "lastKeySet", "L" + imSet + ";");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, ksMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(ksMiss);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imSet, "copyOf", "([Ljava/lang/Object;)L" + imSet + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, self, "lastKeySet", "L" + imSet + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, self, "lastKeys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createValues() -> ImmutableList.copyOf(vals)
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createValues", "()L" + imCol + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imList, "copyOf", "([Ljava/lang/Object;)L" + imList + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // createEntrySet() -> ImmutableSet.Builder with SimpleImmutableEntry
        mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "createEntrySet", "()L" + imSet + ";", null, null);
        mv.visitCode();
        var esLoop = new Label();
        var esDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "keys", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, self, "vals", "[Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, imSet, "builder", "()L" + imSetBuilder + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 5);
        mv.visitLabel(esLoop);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, esDone);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitTypeInsn(Opcodes.NEW, entryCls);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, entryCls, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, imSetBuilder, "add", "(Ljava/lang/Object;)L" + imSetBuilder + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(Opcodes.GOTO, esLoop);
        mv.visitLabel(esDone);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, imSetBuilder, "build", "()L" + imSet + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void ensureReadsExtraPayload(Module module) {
        if (module != null && module.isNamed() && extraPayloadModule != null && extraInst != null
                && !module.canRead(extraPayloadModule)) {
            try {
                extraInst.redefineModule(
                        module,
                        java.util.Set.of(extraPayloadModule),
                        java.util.Map.of(),
                        java.util.Map.of(),
                        java.util.Set.of(),
                        java.util.Map.of());
            } catch (Throwable ignored) {
            }
        }
    }

    public static byte[] transformMoonlightResTransformer(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min) {
                    if (min.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/util/regex/Pattern".equals(min.owner)
                            && "compile".equals(min.name)
                            && "(Ljava/lang/String;)Ljava/util/regex/Pattern;".equals(min.desc)) {
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastCompilePattern";
                        min.itf = false;
                        patched = true;
                    } else if (min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/String".equals(min.owner)
                            && "matches".equals(min.name)
                            && "(Ljava/lang/String;)Z".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringMatches";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)Z";
                        min.itf = false;
                        patched = true;
                    } else if (min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/String".equals(min.owner)
                            && "replaceAll".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceAll";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    }
                }
            }
            if ("replaceFullGenericType".equals(mn.name)
                    && ("(Ljava/lang/String;Lnet/mehvahdjukaar/moonlight/api/set/BlockType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                                    .equals(mn.desc)
                            || "(Ljava/lang/String;Lnet/mehvahdjukaar/moonlight/api/set/BlockType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;"
                                    .equals(mn.desc))) {
                InsnList head = new InsnList();
                LabelNode cont = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new VarInsnNode(Opcodes.ALOAD, 3));
                head.add(new VarInsnNode(Opcodes.ALOAD, 4));
                head.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        PAYLOAD_CLS,
                        "canSkipMoonlightGenericReplace",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new InsnNode(Opcodes.ARETURN));
                head.add(cont);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
                mn.instructions.insert(head);
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformStringMatches(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(min.owner)) {
                    if ("matches".equals(min.name) && "(Ljava/lang/String;)Z".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringMatches";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)Z";
                        min.itf = false;
                        patched = true;
                    } else if ("replaceAll".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceAll";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("replaceFirst".equals(min.name)
                            && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringReplaceFirst";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("split".equals(min.name)
                            && "(Ljava/lang/String;)[Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringSplit";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    } else if ("split".equals(min.name)
                            && "(Ljava/lang/String;I)[Ljava/lang/String;".equals(min.desc)) {
                        min.setOpcode(Opcodes.INVOKESTATIC);
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastStringSplitLimit";
                        min.desc = "(Ljava/lang/String;Ljava/lang/String;I)[Ljava/lang/String;";
                        min.itf = false;
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEmberbiumModuleScanner(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"checkModules".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            InsnList head = new InsnList();
            LabelNode cont = new LabelNode();
            head.add(new LdcInsnNode(Type.getObjectType(EMBERBIUM_MODULE_SCANNER_CLS)));
            head.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
            head.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "mayHaveRtssHooksLoaded", "(Ljava/lang/ClassLoader;)Z", false));
            head.add(new JumpInsnNode(Opcodes.IFNE, cont));
            head.add(new InsnNode(Opcodes.RETURN));
            head.add(cont);
            head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, new Object[0]));
            mn.instructions.insert(head);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformWallLanternBlock(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"isValidBlock".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(min.owner)
                        && "contains".equals(min.name)
                        && "(Ljava/lang/Object;)Z".equals(min.desc)) {
                    min.setOpcode(Opcodes.INVOKESTATIC);
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastCachedListContains";
                    min.desc = "(Ljava/util/List;Ljava/lang/Object;)Z";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformKubeJsIconProvider(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "javax/imageio/ImageIO".equals(min.owner)
                        && "write".equals(min.name)
                        && "(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/OutputStream;)Z"
                                .equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "fastWritePng";
                    min.desc = "(Ljava/lang/Object;Ljava/lang/String;Ljava/io/OutputStream;)Z";
                    min.itf = false;
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Defers TanukiDecor per-state VoxelShape precalculation from constructor time to first
     * shape query, saving synchronous block-registration startup time.
     */
    public static byte[] transformTanukiBlockShapes(String className, byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (TANUKI_PLASMA_CLS.equals(className)) {
            boolean patched = stripCtorPrecalculate(cn);
            if (!patched) {
                return null;
            }
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        }
        boolean hasPrecalc = false;
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if ("precalculateShapes".equals(mn.name) && "()V".equals(mn.desc)) {
                    hasPrecalc = true;
                } else if ("sunlitEnsureShapes".equals(mn.name)) {
                    return null;
                }
            }
        }
        if (!hasPrecalc) {
            return null;
        }
        boolean patchedCtor = stripCtorPrecalculate(cn);
        if (!patchedCtor) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitShapesReady",
                "Z",
                null,
                null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
                "sunlitAllowPrecalc",
                "Z",
                null,
                null));

        for (MethodNode mn : cn.methods) {
            if ("precalculateShapes".equals(mn.name) && "()V".equals(mn.desc) && mn.instructions != null) {
                LabelNode lAllow = new LabelNode();
                InsnList preGuard = new InsnList();
                preGuard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                preGuard.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitAllowPrecalc", "Z"));
                preGuard.add(new JumpInsnNode(Opcodes.IFNE, lAllow));
                preGuard.add(new InsnNode(Opcodes.RETURN));
                preGuard.add(lAllow);
                preGuard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                mn.instructions.insert(preGuard);
                break;
            }
        }

        MethodNode ensure = new MethodNode(Opcodes.ACC_PRIVATE, "sunlitEnsureShapes", "()V", null, null);
        LabelNode lSlow = new LabelNode();
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitShapesReady", "Z"));
        ensure.instructions.add(new JumpInsnNode(Opcodes.IFEQ, lSlow));
        ensure.instructions.add(new InsnNode(Opcodes.RETURN));
        ensure.instructions.add(lSlow);
        ensure.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, className, "sunlitInitShapes", "()V", false));
        ensure.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(ensure);

        MethodNode initShapes = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED, "sunlitInitShapes", "()V", null, null);
        LabelNode lDone = new LabelNode();
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "sunlitShapesReady", "Z"));
        initShapes.instructions.add(new JumpInsnNode(Opcodes.IFNE, lDone));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, className, "sunlitAllowPrecalc", "Z"));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, className, "precalculateShapes", "()V", false));
        initShapes.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initShapes.instructions.add(new InsnNode(Opcodes.ICONST_1));
        initShapes.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, className, "sunlitShapesReady", "Z"));
        initShapes.instructions.add(lDone);
        initShapes.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        initShapes.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(initShapes);

        boolean patchedGetter = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            boolean match;
            if (TANUKI_ROTATING_CLS.equals(className)) {
                match = "m_5940_".equals(mn.name);
            } else if (TANUKI_TALL_CLS.equals(className)) {
                match = "getMultiblockShape".equals(mn.name);
            } else {
                match = "getBlockShape".equals(mn.name) || "getMultiblockShape".equals(mn.name);
            }
            if (match) {
                InsnList guard = new InsnList();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
                guard.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, className, "sunlitEnsureShapes", "()V", false));
                mn.instructions.insert(guard);
                patchedGetter = true;
            }
        }
        if (!patchedGetter) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean stripCtorPrecalculate(ClassNode cn) {
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "precalculateShapes".equals(min.name)
                        && "()V".equals(min.desc)) {
                    AbstractInsnNode prev = min.getPrevious();
                    while (prev != null && (prev.getOpcode() < 0)) {
                        prev = prev.getPrevious();
                    }
                    if (prev instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ALOAD && vin.var == 0) {
                        mn.instructions.remove(vin);
                        mn.instructions.remove(min);
                        patched = true;
                    }
                }
                insn = next;
            }
        }
        return patched;
    }

    /**
     * Synchronizes {@code EMFResourceCaching.populateCacheIfNeeded} and short-circuits
     * post-population misses under {@code emf/cem/} and {@code optifine/cem/} without re-scanning all packs.
     */
    public static byte[] transformEmfResourceCaching(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasCacheField = false;
        if (cn.fields != null) {
            for (var fn : cn.fields) {
                if ("RESOURCE_EXISTENCE_CACHE".equals(fn.name) && "Ljava/util/Map;".equals(fn.desc)) {
                    hasCacheField = true;
                    break;
                }
            }
        }
        if (!hasCacheField) {
            return null;
        }
        final String rmCls = "net/minecraft/server/packs/resources/ResourceManager";
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        boolean patchedSync = false;
        boolean patchedExists = false;
        for (MethodNode mn : cn.methods) {
            if ("populateCacheIfNeeded".equals(mn.name) && ("(L" + rmCls + ";)V").equals(mn.desc)) {
                mn.access |= Opcodes.ACC_SYNCHRONIZED;
                patchedSync = true;
            } else if ("resourceExists".equals(mn.name) && ("(L" + rmCls + ";L" + rlCls + ";)Z").equals(mn.desc)) {
                InsnList il = new InsnList();
                LabelNode lNotCached1 = new LabelNode();
                LabelNode lNotCached2 = new LabelNode();
                LabelNode lFastFalse = new LabelNode();
                LabelNode lFallbackLookup = new LabelNode();

                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "toString", "()Ljava/lang/String;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, lNotCached1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lNotCached1);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        4,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, EMF_RES_CACHING_CLS, "populateCacheIfNeeded", "(L" + rmCls + ";)V", false));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, lNotCached2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lNotCached2);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        4,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135815_", "()Ljava/lang/String;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new LdcInsnNode("emf/cem/"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFNE, lFastFalse));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new LdcInsnNode("optifine/cem/"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFEQ, lFallbackLookup));

                il.add(lFastFalse);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        5,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean", "java/lang/String"},
                        0,
                        new Object[0]));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));

                il.add(lFallbackLookup);
                il.add(new FrameNode(
                        Opcodes.F_FULL,
                        5,
                        new Object[] {rmCls, rlCls, "java/lang/String", "java/lang/Boolean", "java/lang/String"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, rmCls, "m_213713_", "(L" + rlCls + ";)Ljava/util/Optional;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
                il.add(new VarInsnNode(Opcodes.ISTORE, 5));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMF_RES_CACHING_CLS, "RESOURCE_EXISTENCE_CACHE", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new InsnNode(Opcodes.IRETURN));

                mn.instructions = il;
                if (mn.tryCatchBlocks != null) {
                    mn.tryCatchBlocks.clear();
                }
                if (mn.localVariables != null) {
                    mn.localVariables.clear();
                }
                patchedExists = true;
            }
        }
        if (!patchedSync || !patchedExists) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Offloads ModernFix post-bootstrap classloading hooks off {@code BackgroundWaiter}. */
    public static byte[] transformMinecraftBootstrap(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        // A temporary RenderType worker can exit while Windows LWJGL is still initializing JNI.
        boolean offloadRenderType = !System.getProperty("os.name", "").startsWith("Windows");
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKESTATIC) {
                    if (offloadRenderType && "net/minecraft/client/renderer/RenderType".equals(min.owner)
                            && "m_110451_".equals(min.name)
                            && "()Lnet/minecraft/client/renderer/RenderType;".equals(min.desc)) {
                        AbstractInsnNode next = nextRealInsn(min);
                        if (next != null && next.getOpcode() == Opcodes.POP) {
                            mn.instructions.insertBefore(min, new InsnNode(Opcodes.ICONST_1));
                            mn.instructions.insertBefore(min, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    PAYLOAD_CLS,
                                    "schedulePostBootstrapPrewarm",
                                    "(Z)V",
                                    false));
                            mn.instructions.remove(min);
                            mn.instructions.remove(next);
                            patched = true;
                        }
                    } else if ("net/minecraftforge/network/NetworkConstants".equals(min.owner)
                            && "init".equals(min.name)
                            && "()Ljava/lang/String;".equals(min.desc)) {
                        AbstractInsnNode next = nextRealInsn(min);
                        if (next != null && next.getOpcode() == Opcodes.POP) {
                            mn.instructions.insertBefore(min, new InsnNode(Opcodes.ICONST_0));
                            mn.instructions.insertBefore(min, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    PAYLOAD_CLS,
                                    "schedulePostBootstrapPrewarm",
                                    "(Z)V",
                                    false));
                            mn.instructions.remove(min);
                            mn.instructions.remove(next);
                            patched = true;
                        }
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Single-pass wildcard matcher for CarryOn {@code ListHandler.addWithWildcards}. */
    public static byte[] transformCarryOnListHandler(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String desc =
                "(Ljava/util/List;Ljava/util/Set;Lnet/minecraft/core/Registry;Ljava/util/Map;Ljava/util/List;)V";
        for (MethodNode mn : cn.methods) {
            if (!"addWithWildcards".equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode lLoop = new LabelNode();
            LabelNode lNext = new LabelNode();
            LabelNode lEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "net/minecraft/core/Registry",
                    "m_6566_",
                    "()Ljava/util/Set;",
                    true));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PAYLOAD_CLS,
                    "applyCarryOnWildcards",
                    "(Ljava/util/List;Ljava/util/Set;Ljava/util/Set;)V",
                    false));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));

            Object[] loopLocals = new Object[] {
                "java/util/List",
                "java/util/Set",
                "net/minecraft/core/Registry",
                "java/util/Map",
                "java/util/List",
                Opcodes.INTEGER,
                Opcodes.INTEGER
            };
            il.add(lLoop);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new JumpInsnNode(Opcodes.IFNULL, lNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new LdcInsnNode("#"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, lNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CARRYON_LIST_HANDLER_CLS,
                    "addTag",
                    "(Ljava/lang/String;Ljava/util/Map;Ljava/util/List;)V",
                    false));

            il.add(lNext);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new IincInsnNode(5, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, lLoop));

            il.add(lEnd);
            il.add(new FrameNode(Opcodes.F_FULL, 7, loopLocals, 0, new Object[0]));
            il.add(new InsnNode(Opcodes.RETURN));

            mn.instructions = il;
            if (mn.tryCatchBlocks != null) {
                mn.tryCatchBlocks.clear();
            }
            if (mn.localVariables != null) {
                mn.localVariables.clear();
            }
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformEmfJemData(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String desc = "(Ltraben/entity_model_features/models/jem_objects/EMFPartData;Ljava/lang/String;)Ljava/lang/String;";
        final String partCls = "traben/entity_model_features/models/jem_objects/EMFPartData";
        for (MethodNode mn : cn.methods) {
            if (!"processAnimAndKeyString".equals(mn.name) || !desc.equals(mn.desc)) {
                continue;
            }
            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "id", "Ljava/lang/String;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "originalPart", "Ljava/lang/String;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, partCls, "part", "Ljava/lang/String;"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Objects",
                    "requireNonNullElse",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    PAYLOAD_CLS,
                    "fastEmfProcessAnimKey",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    false));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) {
                mn.tryCatchBlocks.clear();
            }
            if (mn.localVariables != null) {
                mn.localVariables.clear();
            }
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Guards {@code com.yanny.aci.tooltip.TooltipNode.empty()} against null
     * {@code CorePluginManager.INSTANCE} / {@code serverRegistry} on dedicated-server multiplayer joins,
     * eliminating repeated NPE stack traces during EMI reload.
     */
    public static byte[] transformAliTooltipNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"empty".equals(mn.name)
                    || !"()Lcom/yanny/aci/tooltip/TooltipNode;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            LabelNode fallback = new LabelNode();
            LabelNode origStart = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/yanny/aci/manager/CorePluginManager",
                    "INSTANCE",
                    "Lcom/yanny/aci/manager/CorePluginManager;"));
            guard.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
            guard.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/yanny/aci/manager/CorePluginManager",
                    "INSTANCE",
                    "Lcom/yanny/aci/manager/CorePluginManager;"));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "com/yanny/aci/manager/CorePluginManager",
                    "serverRegistry",
                    "Lcom/yanny/aci/manager/CoreServerRegistry;"));
            guard.add(new JumpInsnNode(Opcodes.IFNONNULL, origStart));
            guard.add(fallback);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            guard.add(new TypeInsnNode(Opcodes.NEW, "com/yanny/aci/tooltip/TooltipNode"));
            guard.add(new InsnNode(Opcodes.DUP));
            guard.add(new TypeInsnNode(Opcodes.NEW, "com/yanny/aci/tooltip/CacheKey"));
            guard.add(new InsnNode(Opcodes.DUP));
            guard.add(new InsnNode(Opcodes.ACONST_NULL));
            guard.add(new InsnNode(Opcodes.ACONST_NULL));
            guard.add(new InsnNode(Opcodes.ACONST_NULL));
            guard.add(new IntInsnNode(Opcodes.BIPUSH, 8));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "com/yanny/aci/tooltip/CacheKey",
                    "<init>",
                    "(Ljava/lang/String;Ljava/util/List;Lnet/minecraft/network/chat/Component;SLjava/util/List;)V",
                    false));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "com/yanny/aci/tooltip/TooltipNode",
                    "<init>",
                    "(Lcom/yanny/aci/tooltip/CacheKey;)V",
                    false));
            guard.add(new InsnNode(Opcodes.ARETURN));
            guard.add(origStart);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Hoists {@code GenericItemFilling.isFluidHandlerValid(stack, handler)} out of the inner
     * per-fluid loop in {@code SpoutCategory.consumeRecipes}, avoiding ~100x redundant
     * {@code ItemStack.copy()} + {@code getCapability()} calls for every full fluid container on JEI start.
     */
    public static byte[] transformCreateSpoutCategory(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"consumeRecipes".equals(mn.name)
                    || !"(Ljava/util/function/Consumer;Lmezz/jei/api/runtime/IIngredientManager;)V".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            LabelNode continueLoop = null;
            VarInsnNode handlerStore = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (continueLoop == null
                        && insn instanceof MethodInsnNode min
                        && "net/minecraftforge/common/util/LazyOptional".equals(min.owner)
                        && "isPresent".equals(min.name)
                        && "()Z".equals(min.desc)) {
                    AbstractInsnNode n1 = insn.getNext();
                    while (n1 != null && n1.getOpcode() < 0) n1 = n1.getNext();
                    if (n1 instanceof JumpInsnNode ifne && ifne.getOpcode() == Opcodes.IFNE) {
                        AbstractInsnNode n2 = ifne.getNext();
                        while (n2 != null && n2.getOpcode() < 0) n2 = n2.getNext();
                        if (n2 instanceof JumpInsnNode go && go.getOpcode() == Opcodes.GOTO) {
                            continueLoop = go.label;
                        }
                    }
                } else if (continueLoop != null
                        && insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.CHECKCAST
                        && "net/minecraftforge/fluids/capability/IFluidHandlerItem".equals(tin.desc)) {
                    AbstractInsnNode n1 = tin.getNext();
                    while (n1 != null && n1.getOpcode() < 0) n1 = n1.getNext();
                    if (n1 instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                        handlerStore = vin;
                        break;
                    }
                }
            }
            if (continueLoop != null && handlerStore != null) {
                InsnList check = new InsnList();
                check.add(new VarInsnNode(Opcodes.ALOAD, 4));
                check.add(new VarInsnNode(Opcodes.ALOAD, handlerStore.var));
                check.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/simibubi/create/content/fluids/transfer/GenericItemFilling",
                        "isFluidHandlerValid",
                        "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraftforge/fluids/capability/IFluidHandlerItem;)Z",
                        false));
                check.add(new JumpInsnNode(Opcodes.IFEQ, continueLoop));
                mn.instructions.insert(handlerStore, check);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Lowers EMI's background {@code ReloadWorker} thread priority to {@code NORM_PRIORITY - 2} (3)
     * so multiplayer join recipe/loot reloading does not starve the Render thread or chunk builders.
     */
    public static byte[] transformEmiReloadManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Thread".equals(min.owner)
                        && "setDaemon".equals(min.name)
                        && "(Z)V".equals(min.desc)) {
                    InsnList prio = new InsnList();
                    prio.add(new FieldInsnNode(
                            Opcodes.GETSTATIC,
                            EMI_RELOAD_MANAGER_CLS,
                            "thread",
                            "Ljava/lang/Thread;"));
                    prio.add(new InsnNode(Opcodes.ICONST_1));
                    prio.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "java/lang/Thread",
                            "setPriority",
                            "(I)V",
                            false));
                    mn.instructions.insert(min, prio);
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Presizes the item list ({@code ArrayList(16384)}) and dedup set ({@code HashSet(32768)}) in
     * JEI's {@code ItemStackListFactory.create} to eliminate repeated resizes/rehashes on world join.
     */
    public static byte[] transformJeiItemStackListFactory(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"create".equals(mn.name)
                    || !"(Lmezz/jei/common/util/StackHelper;)Ljava/util/List;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    if ("java/util/ArrayList".equals(min.owner)) {
                        mn.instructions.insertBefore(min, new IntInsnNode(Opcodes.SIPUSH, 16384));
                        min.desc = "(I)V";
                        patched = true;
                    } else if ("java/util/HashSet".equals(min.owner)) {
                        mn.instructions.insertBefore(min, new LdcInsnNode(32768));
                        min.desc = "(I)V";
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Guards {@code com.yanny.aci.tooltip.TooltipBuilder.build()} against null
     * {@code CorePluginManager.INSTANCE} / {@code serverRegistry} on dedicated-server joins.
     */
    public static byte[] transformAliTooltipBuilder(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"build".equals(mn.name)
                    || !"()Lcom/yanny/aci/tooltip/TooltipNode;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            LabelNode fallback = new LabelNode();
            LabelNode origStart = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/yanny/aci/manager/CorePluginManager",
                    "INSTANCE",
                    "Lcom/yanny/aci/manager/CorePluginManager;"));
            guard.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
            guard.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "com/yanny/aci/manager/CorePluginManager",
                    "INSTANCE",
                    "Lcom/yanny/aci/manager/CorePluginManager;"));
            guard.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    "com/yanny/aci/manager/CorePluginManager",
                    "serverRegistry",
                    "Lcom/yanny/aci/manager/CoreServerRegistry;"));
            guard.add(new JumpInsnNode(Opcodes.IFNONNULL, origStart));
            guard.add(fallback);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/yanny/aci/tooltip/TooltipNode",
                    "empty",
                    "()Lcom/yanny/aci/tooltip/TooltipNode;",
                    false));
            guard.add(new InsnNode(Opcodes.ARETURN));
            guard.add(origStart);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Wraps {@code JcppProcessor.glslPreprocessSource} with {@code GlslTransformDiskCache} so
     * shaderpack C preprocessing (~3.5s and >1.5GB garbage per launch) is served from disk cache.
     */
    public static byte[] transformJcppProcessor(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode orig = null;
        for (MethodNode mn : cn.methods) {
            if ("glslPreprocessSource".equals(mn.name)
                    && "(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;".equals(mn.desc)
                    && mn.instructions != null) {
                orig = mn;
                break;
            }
        }
        if (orig == null) {
            return null;
        }
        final String cacheCls = "dev/sunlit/bettersunlit/client/GlslTransformDiskCache";
        orig.name = "sunlit$glslPreprocessSourceOrig";
        orig.access = (orig.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;

        MethodNode wrapper = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glslPreprocessSource",
                "(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;",
                null,
                null);
        InsnList il = wrapper.instructions;
        LabelNode miss = new LabelNode();
        LabelNode skipPut = new LabelNode();
        // String key = GlslTransformDiskCache.keyForJcpp(source, defines);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, cacheCls, "keyForJcpp",
                "(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        // if (key != null) { String cached = getJcpp(key); if (cached != null) return cached; }
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, cacheCls, "getJcpp",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARETURN));
        // miss: String result = sunlit$glslPreprocessSourceOrig(source, defines);
        il.add(miss);
        il.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {"java/lang/String"}, 0, null));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, IRIS_JCPP_PROCESSOR_CLS, "sunlit$glslPreprocessSourceOrig",
                "(Ljava/lang/String;Ljava/lang/Iterable;)Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        // if (key != null) putJcpp(key, result);
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IFNULL, skipPut));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, cacheCls, "putJcpp",
                "(Ljava/lang/String;Ljava/lang/String;)V", false));
        il.add(skipPut);
        il.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {"java/lang/String"}, 0, null));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(wrapper);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~700MB+ of transient GC garbage in {@code StringTagVisitor} during JEI/EMI
     * subtype NBT serialization by:
     * <ol>
     *   <li>Replacing {@code SIMPLE_VALUE.matcher(key).matches()} in {@code m_178159_} with a
     *       zero-allocation ASCII scan.</li>
     *   <li>Reusing {@code this} (and {@code this.f_178156_}) when recursing into child tags in
     *       {@code m_142303_} ({@code visitCompound}) and {@code m_142447_} ({@code visitList})
     *       instead of allocating {@code new StringTagVisitor()} per child tag.</li>
     * </ol>
     */
    public static byte[] transformStringTagVisitor(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "()V".equals(mn.desc) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "java/lang/StringBuilder".equals(min.owner)
                            && "<init>".equals(min.name)
                            && "()V".equals(min.desc)) {
                        mn.instructions.insertBefore(min, new IntInsnNode(Opcodes.SIPUSH, 128));
                        min.desc = "(I)V";
                        patched = true;
                        break;
                    }
                }
            } else if ("m_178159_".equals(mn.name)
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode quote = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "isSimpleNbtKey",
                        "(Ljava/lang/String;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFEQ, quote));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(quote);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "net/minecraft/nbt/StringTag", "m_129303_",
                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_142303_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/CompoundTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode notEmpty = new LabelNode();
                LabelNode multiKey = new LabelNode();
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                LabelNode skipComma = new LabelNode();
                // StringBuilder sb = this.f_178156_;
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                // int size = tag.m_128440_();
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128440_", "()I", false));
                il.add(new VarInsnNode(Opcodes.ISTORE, 3));
                // if (size == 0) { sb.append("{}"); return; }
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNE, notEmpty));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new LdcInsnNode("{}"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                // notEmpty: sb.append('{');
                il.add(notEmpty);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 4,
                        new Object[] {MC_STRING_TAG_VISITOR_CLS, "net/minecraft/nbt/CompoundTag", "java/lang/StringBuilder", Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, '{'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                // if (size == 1) { single-key fast path without array/sort }
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new InsnNode(Opcodes.ICONST_1));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPNE, multiKey));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128431_", "()Ljava/util/Set;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MC_STRING_TAG_VISITOR_CLS, "m_178159_", "(Ljava/lang/String;)Ljava/lang/String;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, ':'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128423_", "(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/nbt/Tag", "m_142327_", "(Lnet/minecraft/nbt/TagVisitor;)V", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, '}'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                // multiKey: String[] keys = tag.m_128431_().toArray(new String[size]); Arrays.sort(keys);
                il.add(multiKey);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128431_", "()Ljava/util/Set;", false));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/String;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "sort", "([Ljava/lang/Object;)V", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new InsnNode(Opcodes.ARRAYLENGTH));
                il.add(new VarInsnNode(Opcodes.ISTORE, 3));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 5));
                il.add(loopCond);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 6,
                        new Object[] {
                                MC_STRING_TAG_VISITOR_CLS, "net/minecraft/nbt/CompoundTag",
                                "java/lang/StringBuilder", Opcodes.INTEGER,
                                "[Ljava/lang/String;", Opcodes.INTEGER
                        },
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new JumpInsnNode(Opcodes.IFEQ, skipComma));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, ','));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(skipComma);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new InsnNode(Opcodes.AALOAD));
                il.add(new VarInsnNode(Opcodes.ASTORE, 6));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MC_STRING_TAG_VISITOR_CLS, "m_178159_", "(Ljava/lang/String;)Ljava/lang/String;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, ':'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128423_", "(Ljava/lang/String;)Lnet/minecraft/nbt/Tag;", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/nbt/Tag", "m_142327_", "(Lnet/minecraft/nbt/TagVisitor;)V", true));
                il.add(new IincInsnNode(5, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                il.add(loopEnd);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, '}'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_142614_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/StringTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/StringTag", "m_7916_", "()Ljava/lang/String;", false));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "appendQuotedNbtString",
                        "(Ljava/lang/StringBuilder;Ljava/lang/String;)V", false));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_142447_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/ListTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                LabelNode skipComma = new LabelNode();
                // StringBuilder sb = this.f_178156_; sb.append('[');
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, '['));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/ListTag", "size", "()I", false));
                il.add(new VarInsnNode(Opcodes.ISTORE, 3));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                il.add(loopCond);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 5,
                        new Object[] {
                                MC_STRING_TAG_VISITOR_CLS, "net/minecraft/nbt/ListTag",
                                "java/lang/StringBuilder", Opcodes.INTEGER, Opcodes.INTEGER
                        },
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IFEQ, skipComma));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, ','));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(skipComma);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/ListTag", "get", "(I)Lnet/minecraft/nbt/Tag;", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/nbt/Tag", "m_142327_", "(Lnet/minecraft/nbt/TagVisitor;)V", true));
                il.add(new IincInsnNode(4, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                il.add(loopEnd);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new IntInsnNode(Opcodes.BIPUSH, ']'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_141946_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/ByteTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/ByteTag", "m_7063_", "()B", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, 'b'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_142183_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/ShortTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/ShortTag", "m_7053_", "()S", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, 's'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("m_142046_".equals(mn.name)
                    && "(Lnet/minecraft/nbt/LongTag;)V".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_STRING_TAG_VISITOR_CLS, "f_178156_", "Ljava/lang/StringBuilder;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/LongTag", "m_7046_", "()J", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, 'L'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches the {@code BlockTintCache} per {@code ClientLevel} in VanillaBackport's
     * {@code LeafColors.lambda$getAverageDryFoliageColor$3} instead of allocating a fresh
     * {@code BlockTintCache} (with a 256-entry {@code Long2ObjectLinkedOpenHashMap}) on every
     * dry-foliage block during chunk meshing (~142MB/10s garbage eliminated).
     */
    public static byte[] transformVanillaBackportLeafColors(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String ownerCls = cn.name;
        final String levelDesc = "Lnet/minecraft/client/multiplayer/ClientLevel;";
        final String cacheCls = "net/minecraft/client/color/block/BlockTintCache";
        final String cacheDesc = "L" + cacheCls + ";";
        for (MethodNode mn : cn.methods) {
            if ("lambda$getAverageDryFoliageColor$3".equals(mn.name)
                    && "(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;)Ljava/lang/Integer;".equals(mn.desc)
                    && mn.instructions != null) {
                InvokeDynamicInsnNode tintIndy = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && "(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/util/function/ToIntFunction;".equals(indy.desc)) {
                        tintIndy = indy;
                        break;
                    }
                }
                if (tintIndy == null) {
                    continue;
                }
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        "sunlit$lastLevel", levelDesc, null, null));
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        "sunlit$dryCache", cacheDesc, null, null));

                InsnList il = new InsnList();
                LabelNode miss = new LabelNode();
                LabelNode useCache = new LabelNode();
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, ownerCls, "sunlit$dryCache", cacheDesc));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, ownerCls, "sunlit$lastLevel", levelDesc));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, useCache));
                il.add(miss);
                il.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {cacheCls}, 0, null));
                il.add(new TypeInsnNode(Opcodes.NEW, cacheCls));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(tintIndy.clone(null));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cacheCls, "<init>", "(Ljava/util/function/ToIntFunction;)V", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, ownerCls, "sunlit$lastLevel", levelDesc));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, ownerCls, "sunlit$dryCache", cacheDesc));
                il.add(useCache);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cacheCls, "m_193812_", "(Lnet/minecraft/core/BlockPos;)I", false));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                il.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
            if ("getAverageDryFoliageColor".equals(mn.name)
                    && "(Lnet/minecraft/core/BlockPos;)I".equals(mn.desc)
                    && mn.instructions != null) {
                TypeInsnNode newCache = null;
                InvokeDynamicInsnNode tintIndy = null;
                MethodInsnNode getColorCall = null;
                int levelSlot = -1;
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && cacheCls.equals(t.desc)) {
                        newCache = t;
                    } else if (newCache != null && tintIndy == null && insn instanceof InvokeDynamicInsnNode indy
                            && "(Lnet/minecraft/client/multiplayer/ClientLevel;)Ljava/util/function/ToIntFunction;".equals(indy.desc)) {
                        tintIndy = indy;
                        AbstractInsnNode prev = indy.getPrevious();
                        while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                        if (prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD) {
                            levelSlot = v.var;
                        }
                    } else if (tintIndy != null && insn instanceof MethodInsnNode m
                            && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && cacheCls.equals(m.owner) && "m_193812_".equals(m.name)
                            && "(Lnet/minecraft/core/BlockPos;)I".equals(m.desc)) {
                        getColorCall = m;
                        break;
                    }
                }
                if (newCache == null || tintIndy == null || getColorCall == null || levelSlot < 1) {
                    continue;
                }
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        "sunlit$lastLevel", levelDesc, null, null));
                cn.fields.add(new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                        "sunlit$dryCache", cacheDesc, null, null));

                MethodNode helper = new MethodNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                        "sunlit$getDryFoliageColor",
                        "(" + levelDesc + "Lnet/minecraft/core/BlockPos;)I",
                        null,
                        null);
                LabelNode miss = new LabelNode();
                LabelNode useCache = new LabelNode();
                helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, ownerCls, "sunlit$dryCache", cacheDesc));
                helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                helper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                helper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, ownerCls, "sunlit$lastLevel", levelDesc));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                helper.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, useCache));
                helper.instructions.add(miss);
                helper.instructions.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {cacheCls}, 0, null));
                helper.instructions.add(new TypeInsnNode(Opcodes.NEW, cacheCls));
                helper.instructions.add(new InsnNode(Opcodes.DUP));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                helper.instructions.add(tintIndy.clone(null));
                helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cacheCls, "<init>", "(Ljava/util/function/ToIntFunction;)V", false));
                helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                helper.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, ownerCls, "sunlit$lastLevel", levelDesc));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                helper.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, ownerCls, "sunlit$dryCache", cacheDesc));
                helper.instructions.add(useCache);
                helper.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                helper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cacheCls, "m_193812_", "(Lnet/minecraft/core/BlockPos;)I", false));
                helper.instructions.add(new InsnNode(Opcodes.IRETURN));
                cn.methods.add(helper);

                InsnList rep = new InsnList();
                rep.add(new VarInsnNode(Opcodes.ALOAD, levelSlot));
                rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ownerCls, "sunlit$getDryFoliageColor",
                        "(" + levelDesc + "Lnet/minecraft/core/BlockPos;)I", false));
                mn.instructions.insertBefore(newCache, rep);
                AbstractInsnNode cur = newCache;
                while (cur != null) {
                    AbstractInsnNode nxt = cur.getNext();
                    mn.instructions.remove(cur);
                    if (cur == getColorCall) break;
                    cur = nxt;
                }
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Guards Data_Anchor's client-side {@code tickClient} Mixin handler in {@code Level} with an O(1)
     * check: when neither {@code TrackedDataRegistries.CHUNK} nor {@code BLOCK_ENTITY} has any
     * registered factories, returns immediately instead of scanning all 625+ loaded chunks and
     * thousands of block entities on every client tick.
     */
    public static byte[] transformMinecraftLevel(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String regCls = "dev/corgitaco/dataanchor/data/registry/TrackedDataRegistries";
        final String regType = "Ldev/corgitaco/dataanchor/data/registry/TrackedDataRegistry;";
        final String containerCls = "dev/corgitaco/dataanchor/data/TrackedDataContainer";
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null
                    || !mn.name.contains("tickClient")
                    || !"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V".equals(mn.desc)) {
                continue;
            }
            boolean isDataAnchor = false;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && containerCls.equals(min.owner)
                        && "dataAnchor$getTrackedDataKeys".equals(min.name)) {
                    isDataAnchor = true;
                    break;
                }
            }
            if (!isDataAnchor) {
                continue;
            }
            LabelNode runOrig = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, regCls, "CHUNK", regType));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "dev/corgitaco/dataanchor/data/registry/TrackedDataRegistry", "factories", "()Ljava/util/Map;", false));
            guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, runOrig));
            guard.add(new FieldInsnNode(Opcodes.GETSTATIC, regCls, "BLOCK_ENTITY", regType));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "dev/corgitaco/dataanchor/data/registry/TrackedDataRegistry", "factories", "()Ljava/util/Map;", false));
            guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, runOrig));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(runOrig);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Lowers {@code EmiRecipes$Worker} thread priority to {@code NORM_PRIORITY - 2} (3) in
     * {@code EmiRecipes.setWorker} so post-join recipe indexing does not preempt the Render thread,
     * and replaces {@code Iterables.concat} in {@code lambda$bake$2} with direct index loops
     * (~73MB transient allocation eliminated during EMI recipe baking).
     */
    public static byte[] transformEmiRecipes(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        boolean needAnyDisabledHelper = false;
        final String recipeCls = "dev/emi/emi/api/recipe/EmiRecipe";
        final String ingCls = "dev/emi/emi/api/stack/EmiIngredient";
        for (MethodNode mn : cn.methods) {
            if ("setWorker".equals(mn.name) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && "java/lang/Thread".equals(min.owner)
                            && "start".equals(min.name)
                            && "()V".equals(min.desc)) {
                        InsnList prio = new InsnList();
                        prio.add(new InsnNode(Opcodes.DUP));
                        prio.add(new InsnNode(Opcodes.ICONST_1));
                        prio.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "setPriority", "(I)V", false));
                        mn.instructions.insertBefore(min, prio);
                        patched = true;
                    }
                }
            } else if ("lambda$bake$2".equals(mn.name)
                    && "(Ldev/emi/emi/api/recipe/EmiRecipe;)Z".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode retTrue = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getInputs", "()Ljava/util/List;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EMI_RECIPES_CLS, "sunlit$anyDisabled", "(Ljava/util/List;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getOutputs", "()Ljava/util/List;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EMI_RECIPES_CLS, "sunlit$anyDisabled", "(Ljava/util/List;)Z", false));
                il.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getCatalysts", "()Ljava/util/List;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EMI_RECIPES_CLS, "sunlit$anyDisabled", "(Ljava/util/List;)Z", false));
                il.add(new InsnNode(Opcodes.IRETURN));
                il.add(retTrue);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new InsnNode(Opcodes.ICONST_1));
                il.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                needAnyDisabledHelper = true;
                patched = true;
            }
        }
        if (needAnyDisabledHelper) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$anyDisabled",
                    "(Ljava/util/List;)Z",
                    null,
                    null);
            InsnList il = helper.instructions;
            LabelNode retFalse = new LabelNode();
            LabelNode loopCond = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new JumpInsnNode(Opcodes.IFNULL, retFalse));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 1));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2));
            il.add(loopCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {"java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, retFalse));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ingCls));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "dev/emi/emi/runtime/EmiHidden", "isDisabled",
                    "(L" + ingCls + ";)Z", false));
            LabelNode nextIter = new LabelNode();
            il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(nextIter);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new IincInsnNode(2, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(retFalse);
            il.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {"java/util/List"}, 0, new Object[0]));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(helper);
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces the three per-recipe {@code Stream}/{@code flatMap}/{@code stack.copy()} pipelines in
     * {@code EmiRecipes$Manager.<init>} with direct index loops that only copy an {@code EmiStack}
     * key when inserting a new map entry (~279MB garbage eliminated right during post-join GP +0..5s).
     */
    public static byte[] transformEmiRecipesManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name)
                    || !"(Ljava/util/List;Ljava/util/Map;Ljava/util/List;Z)V".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            // Locate the 3-stream block for local 15 (EmiRecipe):
            //   aload 15; invokeinterface getInputs ... invokeinterface forEach (3rd stream ends with getOutputs...forEach)
            AbstractInsnNode blockStart = null;
            AbstractInsnNode blockEnd = null;
            int forEachCount = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (blockStart == null
                        && insn instanceof VarInsnNode vin
                        && vin.getOpcode() == Opcodes.ALOAD
                        && vin.var == 15) {
                    AbstractInsnNode nxt = nextRealInsn(vin);
                    if (nxt instanceof MethodInsnNode min
                            && "dev/emi/emi/api/recipe/EmiRecipe".equals(min.owner)
                            && "getInputs".equals(min.name)) {
                        blockStart = vin;
                    }
                } else if (blockStart != null
                        && insn instanceof MethodInsnNode min
                        && "java/util/stream/Stream".equals(min.owner)
                        && "forEach".equals(min.name)) {
                    forEachCount++;
                    if (forEachCount == 3) {
                        blockEnd = min;
                        break;
                    }
                }
            }
            if (blockStart != null && blockEnd != null) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 15));
                call.add(new VarInsnNode(Opcodes.ALOAD, 7));
                call.add(new VarInsnNode(Opcodes.ALOAD, 8));
                call.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        EMI_RECIPES_MANAGER_CLS,
                        "sunlit$indexRecipe",
                        "(Ldev/emi/emi/api/recipe/EmiRecipe;Ljava/util/Map;Ljava/util/Map;)V",
                        false));
                mn.instructions.insertBefore(blockStart, call);
                AbstractInsnNode cur = blockStart;
                while (true) {
                    AbstractInsnNode next = cur.getNext();
                    mn.instructions.remove(cur);
                    if (cur == blockEnd) {
                        break;
                    }
                    cur = next;
                }
                addEmiIndexHelpers(cn);
                patched = true;
            }
            // Also replace set.stream().toList() in byInput / byOutput finalization loops with new ArrayList(set)
            // (avoids the double array copy inside List.copyOf -> ImmutableCollections.listCopy)
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode m1
                        && m1.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/Set".equals(m1.owner)
                        && "stream".equals(m1.name)
                        && "()Ljava/util/stream/Stream;".equals(m1.desc)) {
                    AbstractInsnNode nxt = nextRealInsn(m1);
                    if (nxt instanceof MethodInsnNode m2
                            && m2.getOpcode() == Opcodes.INVOKEINTERFACE
                            && "java/util/stream/Stream".equals(m2.owner)
                            && "toList".equals(m2.name)
                            && "()Ljava/util/List;".equals(m2.desc)) {
                        InsnList al = new InsnList();
                        al.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
                        al.add(new InsnNode(Opcodes.DUP_X1));
                        al.add(new InsnNode(Opcodes.SWAP));
                        al.add(new MethodInsnNode(
                                Opcodes.INVOKESPECIAL,
                                "java/util/ArrayList",
                                "<init>",
                                "(Ljava/util/Collection;)V",
                                false));
                        mn.instructions.insertBefore(m1, al);
                        mn.instructions.remove(m1);
                        mn.instructions.remove(m2);
                        patched = true;
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void addEmiIndexHelpers(ClassNode cn) {
        final String recipeCls = "dev/emi/emi/api/recipe/EmiRecipe";
        final String ingCls = "dev/emi/emi/api/stack/EmiIngredient";
        final String stackCls = "dev/emi/emi/api/stack/EmiStack";

        // private static void sunlit$addStack(Map map, EmiStack stack, EmiRecipe recipe)
        MethodNode addStack = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$addStack",
                "(Ljava/util/Map;L" + stackCls + ";L" + recipeCls + ";)V",
                null,
                null);
        {
            InsnList il = addStack.instructions;
            LabelNode hasSet = new LabelNode();
            // Set set = (Set) map.get(stack);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Set"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasSet));
            // set = new LinkedHashSet(4); map.put(stack.copy(), set);
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/LinkedHashSet"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new InsnNode(Opcodes.ICONST_4));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, stackCls, "copy", "()L" + stackCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(hasSet);
            il.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {"java/util/Set"}, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(addStack);

        // private static void sunlit$addIngredients(List ingredients, Map map, EmiRecipe recipe)
        MethodNode addIngs = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$addIngredients",
                "(Ljava/util/List;Ljava/util/Map;L" + recipeCls + ";)V",
                null,
                null);
        {
            InsnList il = addIngs.instructions;
            LabelNode outerCond = new LabelNode();
            LabelNode outerEnd = new LabelNode();
            LabelNode innerCond = new LabelNode();
            LabelNode innerEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(outerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/util/List", "java/util/Map", recipeCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerEnd));
            // List stacks = ((EmiIngredient) ingredients.get(i)).getEmiStacks();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ingCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ingCls, "getEmiStacks", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 7));
            il.add(innerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 8,
                    new Object[] {"java/util/List", "java/util/Map", recipeCls, Opcodes.INTEGER, Opcodes.INTEGER, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, innerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, stackCls));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EMI_RECIPES_MANAGER_CLS, "sunlit$addStack",
                    "(Ljava/util/Map;L" + stackCls + ";L" + recipeCls + ";)V", false));
            il.add(new IincInsnNode(7, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerCond));
            il.add(innerEnd);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/util/List", "java/util/Map", recipeCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(4, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, outerCond));
            il.add(outerEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(addIngs);

        // private static void sunlit$indexRecipe(EmiRecipe recipe, Map byInput, Map byOutput)
        MethodNode idx = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$indexRecipe",
                "(L" + recipeCls + ";Ljava/util/Map;Ljava/util/Map;)V",
                null,
                null);
        {
            InsnList il = idx.instructions;
            LabelNode outCond = new LabelNode();
            LabelNode outEnd = new LabelNode();
            // sunlit$addIngredients(recipe.getInputs(), byInput, recipe);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getInputs", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EMI_RECIPES_MANAGER_CLS, "sunlit$addIngredients",
                    "(Ljava/util/List;Ljava/util/Map;L" + recipeCls + ";)V", false));
            // sunlit$addIngredients(recipe.getCatalysts(), byInput, recipe);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getCatalysts", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EMI_RECIPES_MANAGER_CLS, "sunlit$addIngredients",
                    "(Ljava/util/List;Ljava/util/Map;L" + recipeCls + ";)V", false));
            // List outputs = recipe.getOutputs(); for (int i = 0; i < outputs.size(); i++) sunlit$addStack(byOutput, outputs.get(i), recipe);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getOutputs", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(outCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 6,
                    new Object[] {recipeCls, "java/util/Map", "java/util/Map", "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, stackCls));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EMI_RECIPES_MANAGER_CLS, "sunlit$addStack",
                    "(Ljava/util/Map;L" + stackCls + ";L" + recipeCls + ";)V", false));
            il.add(new IincInsnNode(5, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, outCond));
            il.add(outEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(idx);
    }

    /**
     * Replaces {@code Objects.hash(this.path)} (which allocates {@code new Object[1]} on every
     * {@code Map.get} during Iris include/shader loading (~166MB garbage) with zero-allocation
     * {@code Objects.hashCode(this.path)}.
     */
    public static byte[] transformIrisAbsolutePackPath(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("hashCode".equals(mn.name) && "()I".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, IRIS_ABS_PACK_PATH_CLS, "path", "Ljava/lang/String;"));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false));
                il.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces the O(N^2) linear scan over {@code groupedEmiStacks} in Reliable EMI's
     * {@code StackGroupManager.registerMatch} with an O(1) key-bucketed {@code HashMap} index
     * ({@code stack.getKey() -> ArrayList<EmiStack>}), preserving exact
     * {@code isEqual(stack, Comparison.compareNbt())} semantics and insertion order.
     */
    public static byte[] transformReliableEmiStackGroupManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode clinit = null;
        MethodNode build = null;
        MethodNode regMatch = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
            } else if ("buildGroupedEmiStacksAndStackGroupToContents".equals(mn.name)
                    && "(Ljava/util/List;)V".equals(mn.desc)) {
                build = mn;
            } else if ("registerMatch".equals(mn.name)
                    && "(Lcom/evandev/remi/feature/stackgroup/data/StackGroup;Ldev/emi/emi/api/stack/EmiStack;Ljava/util/Map;)V"
                            .equals(mn.desc)) {
                regMatch = mn;
            }
        }
        if (clinit == null || build == null || regMatch == null
                || clinit.instructions == null || build.instructions == null || regMatch.instructions == null) {
            return null;
        }

        // Locate O(N^2) block in registerMatch:
        //   iconst_0; istore 6; getstatic groupedEmiStacks; ... getstatic groupedEmiStacks; aload 1; List.add; pop
        AbstractInsnNode blockStart = null;
        AbstractInsnNode blockEnd = null;
        for (AbstractInsnNode insn = regMatch.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (blockStart == null && insn.getOpcode() == Opcodes.ICONST_0) {
                AbstractInsnNode n1 = nextRealInsn(insn);
                AbstractInsnNode n2 = nextRealInsn(n1);
                if (n1 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ISTORE && v1.var == 6
                        && n2 instanceof FieldInsnNode f2 && f2.getOpcode() == Opcodes.GETSTATIC
                        && REMI_STACK_GROUP_MGR_CLS.equals(f2.owner)
                        && "groupedEmiStacks".equals(f2.name)) {
                    blockStart = insn;
                }
            } else if (blockStart != null
                    && insn instanceof FieldInsnNode fin
                    && fin.getOpcode() == Opcodes.GETSTATIC
                    && REMI_STACK_GROUP_MGR_CLS.equals(fin.owner)
                    && "groupedEmiStacks".equals(fin.name)) {
                AbstractInsnNode n1 = nextRealInsn(fin);
                AbstractInsnNode n2 = nextRealInsn(n1);
                AbstractInsnNode n3 = nextRealInsn(n2);
                if (n1 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 1
                        && n2 instanceof MethodInsnNode m2 && "java/util/List".equals(m2.owner) && "add".equals(m2.name)
                        && n3 != null && n3.getOpcode() == Opcodes.POP) {
                    blockEnd = n3;
                    break;
                }
            }
        }
        if (blockStart == null || blockEnd == null) {
            return null;
        }

        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "sunlit$groupedByKey",
                "Ljava/util/HashMap;",
                null,
                null));

        // Initialize map in <clinit>
        InsnList initMap = new InsnList();
        initMap.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        initMap.add(new InsnNode(Opcodes.DUP));
        initMap.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        initMap.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, REMI_STACK_GROUP_MGR_CLS, "sunlit$groupedByKey", "Ljava/util/HashMap;"));
        clinit.instructions.insert(initMap);

        // Clear map at start of buildGroupedEmiStacksAndStackGroupToContents
        InsnList clearMap = new InsnList();
        clearMap.add(new FieldInsnNode(
                Opcodes.GETSTATIC, REMI_STACK_GROUP_MGR_CLS, "sunlit$groupedByKey", "Ljava/util/HashMap;"));
        clearMap.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "clear", "()V", false));
        build.instructions.insert(clearMap);

        // Replace linear scan with O(1) helper call
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 1));
        call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                REMI_STACK_GROUP_MGR_CLS,
                "sunlit$addGroupedStack",
                "(Ldev/emi/emi/api/stack/EmiStack;)V",
                false));
        regMatch.instructions.insertBefore(blockStart, call);

        AbstractInsnNode afterEnd = blockEnd.getNext();
        AbstractInsnNode cur = blockStart;
        while (true) {
            AbstractInsnNode next = cur.getNext();
            regMatch.instructions.remove(cur);
            if (cur == blockEnd) {
                break;
            }
            cur = next;
        }
        // Remove now-unreachable FrameNode right after blockEnd (previously target of ifne 118)
        for (AbstractInsnNode scan = afterEnd; scan != null; scan = scan.getNext()) {
            if (scan instanceof FrameNode) {
                regMatch.instructions.remove(scan);
                break;
            }
            if (scan.getOpcode() >= 0) {
                break;
            }
        }

        // Add private static void sunlit$addGroupedStack(EmiStack stack)
        final String stackCls = "dev/emi/emi/api/stack/EmiStack";
        final String cmpCls = "dev/emi/emi/api/stack/Comparison";
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$addGroupedStack",
                "(L" + stackCls + ";)V",
                null,
                null);
        {
            InsnList il = helper.instructions;
            LabelNode hasBucket = new LabelNode();
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            LabelNode nextIter = new LabelNode();
            // Object key = stack.getKey();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, stackCls, "getKey", "()Ljava/lang/Object;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            // ArrayList bucket = (ArrayList) sunlit$groupedByKey.get(key);
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, REMI_STACK_GROUP_MGR_CLS, "sunlit$groupedByKey", "Ljava/util/HashMap;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/ArrayList"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasBucket));
            // bucket = new ArrayList(2); sunlit$groupedByKey.put(key, bucket); bucket.add(stack); groupedEmiStacks.add(stack); return;
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new InsnNode(Opcodes.ICONST_2));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, REMI_STACK_GROUP_MGR_CLS, "sunlit$groupedByKey", "Ljava/util/HashMap;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, REMI_STACK_GROUP_MGR_CLS, "groupedEmiStacks", "Ljava/util/List;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new InsnNode(Opcodes.RETURN));
            // hasBucket: Comparison cmp = Comparison.compareNbt(); for (int i = 0, n = bucket.size(); i < n; i++) ...
            il.add(hasBucket);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {stackCls, "java/lang/Object", "java/util/ArrayList"},
                    0, new Object[0]));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cmpCls, "compareNbt", "()L" + cmpCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(loopCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 6,
                    new Object[] {stackCls, "java/lang/Object", "java/util/ArrayList", cmpCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "get", "(I)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, stackCls));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, stackCls, "isEqual", "(L" + stackCls + ";L" + cmpCls + ";)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
            il.add(new InsnNode(Opcodes.RETURN));
            il.add(nextIter);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new IincInsnNode(5, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, REMI_STACK_GROUP_MGR_CLS, "groupedEmiStacks", "Ljava/util/List;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(helper);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pre-sizes the {@code StringBuilder} in Iris {@code ShaderPack.lambda$new$9} to the exact sum
     * of line lengths and iterates the {@code ImmutableList} by index (~250-300MB garbage eliminated
     * during shaderpack load).
     */
    public static byte[] transformIrisShaderPack(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"lambda$new$9".equals(mn.name)
                    || !"(Ljava/util/List;Lnet/irisshaders/iris/shaderpack/include/IncludeProcessor;Ljava/lang/Iterable;Lnet/irisshaders/iris/shaderpack/include/AbsolutePackPath;)Ljava/lang/String;"
                            .equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            AbstractInsnNode sbNew = null;
            AbstractInsnNode sbToString = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (sbNew == null
                        && insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && "java/lang/StringBuilder".equals(tin.desc)) {
                    sbNew = tin;
                } else if (sbNew != null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/StringBuilder".equals(min.owner)
                        && "toString".equals(min.name)
                        && "()Ljava/lang/String;".equals(min.desc)) {
                    sbToString = min;
                    break;
                }
            }
            if (sbNew != null && sbToString != null) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 6));
                call.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        IRIS_SHADER_PACK_CLS,
                        "sunlit$joinLines",
                        "(Ljava/util/List;)Ljava/lang/String;",
                        false));
                mn.instructions.insertBefore(sbNew, call);
                AbstractInsnNode cur = sbNew;
                while (true) {
                    AbstractInsnNode next = cur.getNext();
                    mn.instructions.remove(cur);
                    if (cur == sbToString) {
                        break;
                    }
                    cur = next;
                }
                MethodNode helper = new MethodNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                        "sunlit$joinLines",
                        "(Ljava/util/List;)Ljava/lang/String;",
                        null,
                        null);
                InsnList il = helper.instructions;
                LabelNode loop1 = new LabelNode();
                LabelNode end1 = new LabelNode();
                LabelNode loop2 = new LabelNode();
                LabelNode end2 = new LabelNode();
                // int n = lines.size(); int total = 0;
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                il.add(new VarInsnNode(Opcodes.ISTORE, 1));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 2));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 3));
                // for (int i = 0; i < n; i++) total += ((String) lines.get(i)).length() + 1;
                il.add(loop1);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 4,
                        new Object[] {"java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new VarInsnNode(Opcodes.ILOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end1));
                il.add(new VarInsnNode(Opcodes.ILOAD, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
                il.add(new InsnNode(Opcodes.ICONST_1));
                il.add(new InsnNode(Opcodes.IADD));
                il.add(new InsnNode(Opcodes.IADD));
                il.add(new VarInsnNode(Opcodes.ISTORE, 2));
                il.add(new IincInsnNode(3, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loop1));
                // StringBuilder sb = new StringBuilder(total);
                il.add(end1);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3,
                        new Object[] {"java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new VarInsnNode(Opcodes.ILOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(I)V", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                // for (int i = 0; i < n; i++) sb.append((String) lines.get(i)).append('\n');
                il.add(loop2);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 5,
                        new Object[] {"java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, "java/lang/StringBuilder", Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
                il.add(new IntInsnNode(Opcodes.BIPUSH, '\n'));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new IincInsnNode(4, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loop2));
                il.add(end2);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                cn.methods.add(helper);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates 3 {@code Optional} allocations per {@code ForgeRegistry.getKey(V)} call (and 1 per
     * {@code getResourceKey(V)}) by reading {@code this.owners.inverse().get(value)} directly.
     */
    public static byte[] transformForgeRegistry(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String ownerCls = "net/minecraftforge/registries/ForgeRegistry$OverrideOwner";
        final String rkCls = "net/minecraft/resources/ResourceKey";
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        for (MethodNode mn : cn.methods) {
            if ("getKey".equals(mn.name)
                    && "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode fallback = new LabelNode();
                // OverrideOwner owner = (OverrideOwner) this.owners.inverse().get(value);
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_REGISTRY_CLS, "owners", "Lcom/google/common/collect/BiMap;"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "inverse", "()Lcom/google/common/collect/BiMap;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, ownerCls));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                // ResourceKey key = owner.key();
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerCls, "key", "()L" + rkCls + ";", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                // ResourceLocation loc = key.m_135782_();
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rkCls, "m_135782_", "()L" + rlCls + ";", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new InsnNode(Opcodes.ARETURN));
                // fallback: return this.defaultKey;
                il.add(fallback);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {FORGE_REGISTRY_CLS, "java/lang/Object"},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_REGISTRY_CLS, "defaultKey", "L" + rlCls + ";"));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("getResourceKey".equals(mn.name)
                    && "(Ljava/lang/Object;)Ljava/util/Optional;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode isNull = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, FORGE_REGISTRY_CLS, "owners", "Lcom/google/common/collect/BiMap;"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "inverse", "()Lcom/google/common/collect/BiMap;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "com/google/common/collect/BiMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, ownerCls));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, isNull));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ownerCls, "key", "()L" + rkCls + ";", false));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "ofNullable", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(isNull);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {FORGE_REGISTRY_CLS, "java/lang/Object"},
                        0, new Object[0]));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code ingredients.stream().flatMap(i -> i.getEmiStacks().stream()).toList()} in
     * {@code ListEmiIngredient.<init>} with direct index loops (~75MB garbage eliminated in EMI bake).
     */
    public static byte[] transformEmiListIngredient(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String ingCls = "dev/emi/emi/api/stack/EmiIngredient";
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || !"(Ljava/util/List;J)V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode m1
                        && m1.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(m1.owner)
                        && "stream".equals(m1.name)
                        && "()Ljava/util/stream/Stream;".equals(m1.desc)) {
                    AbstractInsnNode n1 = nextRealInsn(m1);
                    AbstractInsnNode n2 = nextRealInsn(n1);
                    AbstractInsnNode n3 = nextRealInsn(n2);
                    if (n1 instanceof InvokeDynamicInsnNode
                            && n2 instanceof MethodInsnNode m2
                            && "java/util/stream/Stream".equals(m2.owner) && "flatMap".equals(m2.name)
                            && n3 instanceof MethodInsnNode m3
                            && "java/util/stream/Stream".equals(m3.owner) && "toList".equals(m3.name)) {
                        mn.instructions.set(m1, new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                EMI_LIST_INGREDIENT_CLS,
                                "sunlit$flatten",
                                "(Ljava/util/List;)Ljava/util/List;",
                                false));
                        mn.instructions.remove(n1);
                        mn.instructions.remove(n2);
                        mn.instructions.remove(n3);
                        patched = true;
                        break;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$flatten",
                "(Ljava/util/List;)Ljava/util/List;",
                null,
                null);
        {
            InsnList il = helper.instructions;
            LabelNode outerCond = new LabelNode();
            LabelNode outerEnd = new LabelNode();
            LabelNode innerCond = new LabelNode();
            LabelNode innerEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 1));
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(outerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            LabelNode notSingleStack = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, "dev/emi/emi/api/stack/EmiStack"));
            il.add(new JumpInsnNode(Opcodes.IFEQ, notSingleStack));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerEnd));
            il.add(notSingleStack);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, "java/lang/Object"},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ingCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ingCls, "getEmiStacks", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));
            il.add(innerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, innerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new IincInsnNode(6, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerCond));
            il.add(innerEnd);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(3, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, outerCond));
            il.add(outerEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableList", "(Ljava/util/List;)Ljava/util/List;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        cn.methods.add(helper);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~70MB of {@code CallbackInfoReturnable}, {@code Optional}, and {@code ResourceLocation}
     * churn in Curios tooltip/predicate checks during EMI search indexing by:
     * <ol>
     *   <li>Reading {@code SLOT_RESULT_PREDICATES} directly in {@code testCurioPredicates}.</li>
     *   <li>Caching {@code curios:<slot>} and {@code curios:curio} {@code TagKey}s in {@code lambda$static$9}.</li>
     * </ol>
     */
    public static byte[] transformCuriosImplMixinHooks(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedPred = false;
        boolean patchedTag = false;
        boolean patchedFilter = false;
        boolean hasGetAttrMods = false;
        MethodNode clinit = null;
        final String srCls = "top/theillusivec4/curios/api/SlotResult";
        final String scCls = "top/theillusivec4/curios/api/SlotContext";
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        final String tagCls = "net/minecraft/tags/TagKey";
        final String isCls = "net/minecraft/world/item/ItemStack";

        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
            } else if ("getAttributeModifiers".equals(mn.name)
                    && "(Ltop/theillusivec4/curios/api/SlotContext;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/google/common/collect/Multimap;".equals(mn.desc)) {
                hasGetAttrMods = true;
            } else if ("testCurioPredicates".equals(mn.name)
                    && "(Ljava/util/Set;Ltop/theillusivec4/curios/api/SlotResult;)Z".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                LabelNode nextIter = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(loopCond);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3,
                        new Object[] {"java/util/Set", srCls, "java/util/Iterator"},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
                il.add(new JumpInsnNode(Opcodes.IFEQ, loopEnd));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "SLOT_RESULT_PREDICATES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Predicate"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFNULL, nextIter));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true));
                il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
                il.add(new InsnNode(Opcodes.ICONST_1));
                il.add(new InsnNode(Opcodes.IRETURN));
                il.add(nextIter);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3,
                        new Object[] {"java/util/Set", srCls, "java/util/Iterator"},
                        0, new Object[0]));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                il.add(loopEnd);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedPred = true;
            } else if ("lambda$static$9".equals(mn.name)
                    && "(Ltop/theillusivec4/curios/api/SlotResult;)Z".equals(mn.desc)
                    && mn.instructions != null) {
                // Verify this lambda is indeed the curios:tag predicate referencing "curio"
                boolean sawCurio = false;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof LdcInsnNode ldc && "curio".equals(ldc.cst)) {
                        sawCurio = true;
                        break;
                    }
                }
                if (sawCurio) {
                    InsnList il = new InsnList();
                    LabelNode hasSlotTag = new LabelNode();
                    LabelNode checkCurio = new LabelNode();
                    LabelNode hasCurioTag = new LabelNode();
                    // String id = slotResult.slotContext().identifier();
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, srCls, "slotContext", "()L" + scCls + ";", false));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, scCls, "identifier", "()Ljava/lang/String;", false));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                    // TagKey slotTag = (TagKey) sunlit$slotTags.get(id);
                    il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$slotTags", "Ljava/util/concurrent/ConcurrentHashMap;"));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    il.add(new TypeInsnNode(Opcodes.CHECKCAST, tagCls));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasSlotTag));
                    // slotTag = ItemTags.create(new ResourceLocation("curios", id)); sunlit$slotTags.putIfAbsent(id, slotTag);
                    il.add(new TypeInsnNode(Opcodes.NEW, rlCls));
                    il.add(new InsnNode(Opcodes.DUP));
                    il.add(new LdcInsnNode("curios"));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, rlCls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false));
                    il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/tags/ItemTags", "create", "(L" + rlCls + ";)L" + tagCls + ";", false));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                    il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$slotTags", "Ljava/util/concurrent/ConcurrentHashMap;"));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                    il.add(new InsnNode(Opcodes.POP));
                    il.add(hasSlotTag);
                    il.add(new FrameNode(
                            Opcodes.F_FULL, 3,
                            new Object[] {srCls, "java/lang/String", tagCls},
                            0, new Object[0]));
                    // ItemStack stack = slotResult.stack(); if (stack.m_204117_(slotTag)) return true;
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, srCls, "stack", "()L" + isCls + ";", false));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "m_204117_", "(L" + tagCls + ";)Z", false));
                    il.add(new JumpInsnNode(Opcodes.IFEQ, checkCurio));
                    il.add(new InsnNode(Opcodes.ICONST_1));
                    il.add(new InsnNode(Opcodes.IRETURN));
                    // checkCurio: TagKey curioTag = sunlit$curioTag;
                    il.add(checkCurio);
                    il.add(new FrameNode(
                            Opcodes.F_FULL, 4,
                            new Object[] {srCls, "java/lang/String", tagCls, isCls},
                            0, new Object[0]));
                    il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$curioTag", "L" + tagCls + ";"));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                    il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasCurioTag));
                    il.add(new TypeInsnNode(Opcodes.NEW, rlCls));
                    il.add(new InsnNode(Opcodes.DUP));
                    il.add(new LdcInsnNode("curios"));
                    il.add(new LdcInsnNode("curio"));
                    il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, rlCls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false));
                    il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/tags/ItemTags", "create", "(L" + rlCls + ";)L" + tagCls + ";", false));
                    il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                    il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CURIOS_HOOKS_CLS, "sunlit$curioTag", "L" + tagCls + ";"));
                    il.add(hasCurioTag);
                    il.add(new FrameNode(
                            Opcodes.F_FULL, 5,
                            new Object[] {srCls, "java/lang/String", tagCls, isCls, tagCls},
                            0, new Object[0]));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "m_204117_", "(L" + tagCls + ";)Z", false));
                    il.add(new InsnNode(Opcodes.IRETURN));
                    mn.instructions = il;
                    if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                    if (mn.localVariables != null) mn.localVariables.clear();
                    patchedTag = true;
                }
            } else if ("filteredSlots".equals(mn.name)
                    && "(Ljava/util/function/Predicate;Ljava/util/Map;)Ljava/util/Map;".equals(mn.desc)
                    && mn.instructions != null) {
                final String slotCls = "top/theillusivec4/curios/api/type/ISlotType";
                InsnList il = new InsnList();
                LabelNode hasEntries = new LabelNode();
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                LabelNode nextIter = new LabelNode();
                // Map.Entry[] entries = (slots == sunlit$lastSlotsMap) ? sunlit$lastSlotsEntries : null;
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$lastSlotsMap", "Ljava/util/Map;"));
                il.add(new JumpInsnNode(Opcodes.IF_ACMPNE, hasEntries));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$lastSlotsEntries", "[Ljava/util/Map$Entry;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                LabelNode ready = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
                // cache miss: entries = slots.entrySet().toArray(new Map.Entry[0]);
                il.add(hasEntries);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {"java/util/function/Predicate", "java/util/Map"},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/util/Map$Entry"));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/util/Map$Entry;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CURIOS_HOOKS_CLS, "sunlit$lastSlotsEntries", "[Ljava/util/Map$Entry;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CURIOS_HOOKS_CLS, "sunlit$lastSlotsMap", "Ljava/util/Map;"));
                // ready: HashMap result = new HashMap(); for (int i = 0, n = entries.length; i < n; i++) ...
                il.add(ready);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3,
                        new Object[] {"java/util/function/Predicate", "java/util/Map", "[Ljava/util/Map$Entry;"},
                        0, new Object[0]));
                il.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new InsnNode(Opcodes.ARRAYLENGTH));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 5));
                il.add(loopCond);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 6,
                        new Object[] {"java/util/function/Predicate", "java/util/Map", "[Ljava/util/Map$Entry;", "java/util/HashMap", Opcodes.INTEGER, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new InsnNode(Opcodes.AALOAD));
                il.add(new VarInsnNode(Opcodes.ASTORE, 6));
                il.add(new VarInsnNode(Opcodes.ALOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, slotCls));
                il.add(new VarInsnNode(Opcodes.ASTORE, 7));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 7));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true));
                il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 7));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(nextIter);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 6,
                        new Object[] {"java/util/function/Predicate", "java/util/Map", "[Ljava/util/Map$Entry;", "java/util/HashMap", Opcodes.INTEGER, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new IincInsnNode(5, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                il.add(loopEnd);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedFilter = true;
            } else if ("getItemStackSlots".equals(mn.name) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "top/theillusivec4/curios/api/CuriosApi".equals(min.owner)) {
                        if ("getSlots".equals(min.name) && "(Z)Ljava/util/Map;".equals(min.desc)) {
                            min.owner = CURIOS_HOOKS_CLS;
                            patchedPred = true;
                        } else if ("getEntitySlots".equals(min.name)
                                && "(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Map;".equals(min.desc)) {
                            min.owner = CURIOS_HOOKS_CLS;
                            min.name = "sunlit$getEntitySlotsDirect";
                            patchedPred = true;
                        }
                    }
                }
            }
        }
        if (patchedPred) {
            // Add direct helper bypassing CuriosApi.getEntitySlots Mixin CallbackInfoReturnable
            MethodNode directSlots = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$getEntitySlotsDirect",
                    "(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Map;",
                    null,
                    null);
            InsnList il = directSlots.instructions;
            LabelNode isNull = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new JumpInsnNode(Opcodes.IFNULL, isNull));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/LivingEntity",
                    "m_6095_", "()Lnet/minecraft/world/entity/EntityType;", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/LivingEntity",
                    "m_9236_", "()Lnet/minecraft/world/level/Level;", false));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/Level",
                    "m_5776_", "()Z", false));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, CURIOS_HOOKS_CLS, "getEntitySlots",
                    "(Lnet/minecraft/world/entity/EntityType;Z)Ljava/util/Map;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(isNull);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true));
            il.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(directSlots);
        }
        if (patchedFilter) {
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "sunlit$lastSlotsMap", "Ljava/util/Map;", null, null));
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "sunlit$lastSlotsEntries", "[Ljava/util/Map$Entry;", null, null));
        }
        if (patchedTag && clinit != null && clinit.instructions != null) {
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "sunlit$curioTag", "L" + tagCls + ";", null, null));
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "sunlit$slotTags", "Ljava/util/concurrent/ConcurrentHashMap;", null, null));
            InsnList initTags = new InsnList();
            initTags.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
            initTags.add(new InsnNode(Opcodes.DUP));
            initTags.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V", false));
            initTags.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, CURIOS_HOOKS_CLS, "sunlit$slotTags", "Ljava/util/concurrent/ConcurrentHashMap;"));
            clinit.instructions.insert(initTags);
        }
        if (hasGetAttrMods) {
            final String mmCls = "com/google/common/collect/Multimap";
            final String immCls = "com/google/common/collect/ImmutableMultimap";
            final String loCls = "net/minecraftforge/common/util/LazyOptional";
            final String icCls = "top/theillusivec4/curios/api/type/capability/ICurio";
            final String evCls = "top/theillusivec4/curios/api/event/CurioAttributeModifierEvent";
            final String llCls = "net/minecraftforge/eventbus/ListenerList";
            final String ebCls = "net/minecraftforge/eventbus/api/IEventBus";
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "sunlit$attrListeners", "L" + llCls + ";", null, null));
            MethodNode roAttr = new MethodNode(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "sunlit$getAttributeModifiersReadOnly",
                    "(L" + scCls + ";Ljava/util/UUID;L" + isCls + ";)L" + mmCls + ";",
                    null,
                    null);
            InsnList il = roAttr.instructions;
            LabelNode noNbtMods = new LabelNode();
            LabelNode afterCurio = new LabelNode();
            LabelNode postEvent = new LabelNode();
            LabelNode hasLl = new LabelNode();
            // CompoundTag tag = stack.m_41783_();
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "m_41783_", "()Lnet/minecraft/nbt/CompoundTag;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IFNULL, noNbtMods));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new LdcInsnNode("CurioAttributeModifiers"));
            il.add(new IntInsnNode(Opcodes.BIPUSH, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "m_128425_", "(Ljava/lang/String;I)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, noNbtMods));
            // Delegate rare explicit CurioAttributeModifiers NBT tag case to full method
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, CURIOS_HOOKS_CLS, "getAttributeModifiers",
                    "(L" + scCls + ";Ljava/util/UUID;L" + isCls + ";)L" + mmCls + ";", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            // noNbtMods: Multimap map = ImmutableMultimap.of();
            il.add(noNbtMods);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {scCls, "java/util/UUID", isCls},
                    0, new Object[0]));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, immCls, "of", "()L" + immCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            // LazyOptional opt = getCurio(stack);
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CURIOS_HOOKS_CLS, "getCurio", "(L" + isCls + ";)L" + loCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, loCls, "isPresent", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, afterCurio));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, loCls, "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, icCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IFNULL, afterCurio));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, icCls, "getAttributeModifiers",
                    "(L" + scCls + ";Ljava/util/UUID;)L" + mmCls + ";", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            // afterCurio: check CurioAttributeModifierEvent listeners before allocating event
            il.add(afterCurio);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {scCls, "java/util/UUID", isCls, mmCls},
                    0, new Object[0]));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + ebCls + ";"));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getEventBusId", "(Ljava/lang/Object;)I", false));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IFLT, postEvent));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, CURIOS_HOOKS_CLS, "sunlit$attrListeners", "L" + llCls + ";"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasLl));
            il.add(new TypeInsnNode(Opcodes.NEW, evCls));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, evCls, "<init>",
                    "(L" + isCls + ";L" + scCls + ";Ljava/util/UUID;L" + mmCls + ";)V", false));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "net/minecraftforge/eventbus/api/Event",
                    "getListenerList", "()L" + llCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, CURIOS_HOOKS_CLS, "sunlit$attrListeners", "L" + llCls + ";"));
            il.add(hasLl);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 6,
                    new Object[] {scCls, "java/util/UUID", isCls, mmCls, Opcodes.INTEGER, llCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, llCls, "getListeners",
                    "(I)[Lnet/minecraftforge/eventbus/api/IEventListener;", false));
            il.add(new InsnNode(Opcodes.ARRAYLENGTH));
            il.add(new JumpInsnNode(Opcodes.IFGT, postEvent));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            // postEvent: post CurioAttributeModifierEvent and return evt.getModifiers() without LinkedHashMultimap copy
            il.add(postEvent);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {scCls, "java/util/UUID", isCls, mmCls},
                    0, new Object[0]));
            il.add(new TypeInsnNode(Opcodes.NEW, evCls));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, evCls, "<init>",
                    "(L" + isCls + ";L" + scCls + ";Ljava/util/UUID;L" + mmCls + ";)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS", "L" + ebCls + ";"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, ebCls, "post", "(Lnet/minecraftforge/eventbus/api/Event;)Z", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, evCls, "getModifiers", "()L" + mmCls + ";", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(roAttr);
        }
        if (!patchedPred && !patchedTag && !patchedFilter && !hasGetAttrMods) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Guards Citadel's {@code citadel_getFluidRenderLayer} Mixin handler on
     * {@code ItemBlockRenderTypes} with a live zero-listener check so fluid chunk meshing does not
     * allocate an {@code EventGetFluidRenderType} and post to {@code EVENT_BUS} on every fluid block
     * when no listeners are registered.
     */
    public static byte[] transformItemBlockRenderTypes(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String evCls = "com/github/alexthe666/citadel/client/event/EventGetFluidRenderType";
        final String llCls = "net/minecraftforge/eventbus/ListenerList";
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null
                    || !mn.name.contains("citadel_getFluidRenderLayer")
                    || !"(Lnet/minecraft/world/level/material/FluidState;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V"
                            .equals(mn.desc)) {
                continue;
            }
            boolean instantiatesEvent = false;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW && evCls.equals(tin.desc)) {
                    instantiatesEvent = true;
                    break;
                }
            }
            if (!instantiatesEvent) {
                continue;
            }
            LabelNode runOrig = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, cn.name, "sunlit$hasFluidEventListeners", "()Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFNE, runOrig));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(runOrig);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
        }
        boolean patchedIrisBlockType = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions != null && mn.name.contains("iris$setCustomRenderType")) {
                if (patchIrisBlockRenderTypeLookup(mn)) {
                    patchedIrisBlockType = true;
                }
            }
        }
        if (!patched && !patchedIrisBlockType) {
            return null;
        }
        if (patched) {
            cn.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                    "sunlit$fluidListeners", "L" + llCls + ";", null, null));
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$hasFluidEventListeners",
                    "()Z",
                    null,
                    null);
            {
                InsnList il = helper.instructions;
                LabelNode retTrue = new LabelNode();
                LabelNode hasLl = new LabelNode();
                // int busId = BootJarPayload.getEventBusId(MinecraftForge.EVENT_BUS);
                il.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge",
                        "EVENT_BUS", "Lnet/minecraftforge/eventbus/api/IEventBus;"));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getEventBusId", "(Ljava/lang/Object;)I", false));
                il.add(new VarInsnNode(Opcodes.ISTORE, 0));
                il.add(new VarInsnNode(Opcodes.ILOAD, 0));
                il.add(new JumpInsnNode(Opcodes.IFLT, retTrue));
                // ListenerList ll = sunlit$fluidListeners;
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "sunlit$fluidListeners", "L" + llCls + ";"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasLl));
                // ll = new EventGetFluidRenderType(null, null).getListenerList(); sunlit$fluidListeners = ll;
                il.add(new TypeInsnNode(Opcodes.NEW, evCls));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, evCls, "<init>",
                        "(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/RenderType;)V", false));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "net/minecraftforge/eventbus/api/Event",
                        "getListenerList", "()L" + llCls + ";", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, "sunlit$fluidListeners", "L" + llCls + ";"));
                il.add(hasLl);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {Opcodes.INTEGER, llCls},
                        0, new Object[0]));
                // return ll.getListeners(busId).length != 0;
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ILOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, llCls, "getListeners",
                        "(I)[Lnet/minecraftforge/eventbus/api/IEventListener;", false));
                il.add(new InsnNode(Opcodes.ARRAYLENGTH));
                il.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));
                il.add(retTrue);
                il.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {Opcodes.INTEGER}, 0, new Object[0]));
                il.add(new InsnNode(Opcodes.ICONST_1));
                il.add(new InsnNode(Opcodes.IRETURN));
            }
            cn.methods.add(helper);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~317MB of full-shader prefix/suffix substring and {@code String.trim()} copies in
     * Iris {@code CommentDirectiveParser.findDirective} using zero-copy index scans in
     * {@link BootJarPayload}.
     */
    public static byte[] transformIrisCommentDirectiveParser(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String typeCls = "net/irisshaders/iris/shaderpack/parsing/CommentDirective$Type";
        final String dirCls = "net/irisshaders/iris/shaderpack/parsing/CommentDirective";
        for (MethodNode mn : cn.methods) {
            if (!"findDirective".equals(mn.name)
                    || !("(Ljava/lang/String;L" + typeCls + ";)Ljava/util/Optional;").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode retEmpty = new LabelNode();
            // String needle = type.name().concat(":");
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, typeCls, "name", "()Ljava/lang/String;", false));
            il.add(new LdcInsnNode(":"));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            // int idx = BootJarPayload.findCommentDirectiveIdx(haystack, needle);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "findCommentDirectiveIdx",
                    "(Ljava/lang/String;Ljava/lang/String;)I", false));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IFLT, retEmpty));
            // String val = BootJarPayload.extractCommentDirectiveValue(haystack, idx + needle.length());
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "extractCommentDirectiveValue",
                    "(Ljava/lang/String;I)Ljava/lang/String;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IFNULL, retEmpty));
            // return Optional.of(new CommentDirective(type, val, idx));
            il.add(new TypeInsnNode(Opcodes.NEW, dirCls));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, dirCls, "<init>",
                    "(L" + typeCls + ";Ljava/lang/String;I)V", false));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            // retEmpty: return Optional.empty();
            il.add(retEmpty);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 2,
                    new Object[] {"java/lang/String", typeCls},
                    0, new Object[0]));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces Apache Commons {@code Validate.notNull(T)} (which allocates {@code new Object[0]}
     * plus a capturing {@code Supplier} lambda on every call, ~176MB garbage) in
     * {@code NonNullList.set/add} with zero-allocation {@code Objects.requireNonNull(T)}.
     */
    public static byte[] transformNonNullList(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "org/apache/commons/lang3/Validate".equals(min.owner)
                        && "notNull".equals(min.name)
                        && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(min.desc)) {
                    min.owner = "java/util/Objects";
                    min.name = "requireNonNull";
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~62MB of {@code ResourceLocation.toString()} allocations in EMI
     * {@code EmiData.lambda$init$10} (recipe category filter) via zero-allocation region matching.
     */
    public static byte[] transformEmiData(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String recipeCls = "dev/emi/emi/api/recipe/EmiRecipe";
        final String catCls = "dev/emi/emi/api/recipe/EmiRecipeCategory";
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        for (MethodNode mn : cn.methods) {
            if (!"lambda$init$10".equals(mn.name)
                    || !"(Ljava/lang/String;Ldev/emi/emi/api/recipe/EmiRecipe;)Z".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            boolean matchesPattern = false;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min
                        && catCls.equals(min.owner)
                        && "getId".equals(min.name)) {
                    matchesPattern = true;
                    break;
                }
            }
            if (!matchesPattern) {
                continue;
            }
            InsnList il = new InsnList();
            // ResourceLocation rl = recipe.getCategory().getId();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, recipeCls, "getCategory", "()L" + catCls + ";", true));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, catCls, "getId", "()L" + rlCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            // return BootJarPayload.resourceLocationEqualsString(rl.m_135827_(), rl.m_135815_(), str);
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135827_", "()Ljava/lang/String;", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135815_", "()Ljava/lang/String;", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "resourceLocationEqualsString",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Guards {@code EmiHidden.isDisabled}'s {@code disabledFilterLookup} lookup with an O(1)
     * {@code disabledFilters.isEmpty()} fast return and caches filter results per
     * {@code ResourceLocation} so {@code ResourceLocation.toString()} + string concat is not
     * repeated across hundreds of thousands of recipe ingredient checks.
     */
    public static byte[] transformEmiHidden(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        MethodNode clinit = null;
        MethodNode clearM = null;
        MethodNode reloadM = null;
        MethodNode isDisabledM = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) clinit = mn;
            else if ("clear".equals(mn.name) && "()V".equals(mn.desc)) clearM = mn;
            else if ("reload".equals(mn.name) && "()V".equals(mn.desc)) reloadM = mn;
            else if ("isDisabled".equals(mn.name)
                    && "(Ldev/emi/emi/api/stack/EmiIngredient;)Z".equals(mn.desc)) isDisabledM = mn;
        }
        if (clinit == null || isDisabledM == null || clinit.instructions == null || isDisabledM.instructions == null) {
            return null;
        }
        // Locate `GETSTATIC EmiHidden.disabledFilterLookup` inside isDisabled
        FieldInsnNode lookupGet = null;
        for (AbstractInsnNode insn : isDisabledM.instructions) {
            if (insn instanceof FieldInsnNode fin
                    && fin.getOpcode() == Opcodes.GETSTATIC
                    && EMI_HIDDEN_CLS.equals(fin.owner)
                    && "disabledFilterLookup".equals(fin.name)) {
                lookupGet = fin;
                break;
            }
        }
        if (lookupGet == null) {
            return null;
        }
        // Locate `ISTORE 3` right after `Boolean.booleanValue()`
        VarInsnNode storeFlag = null;
        for (AbstractInsnNode cur = lookupGet.getNext(); cur != null; cur = cur.getNext()) {
            if (cur instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ISTORE && vin.var == 3) {
                storeFlag = vin;
                break;
            }
        }
        if (storeFlag == null) {
            return null;
        }

        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "sunlit$idFilterCache", "Ljava/util/concurrent/ConcurrentHashMap;", null, null));

        InsnList initCache = new InsnList();
        initCache.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        initCache.add(new InsnNode(Opcodes.DUP));
        initCache.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V", false));
        initCache.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, EMI_HIDDEN_CLS, "sunlit$idFilterCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
        clinit.instructions.insert(initCache);

        for (MethodNode m : new MethodNode[] {clearM, reloadM}) {
            if (m != null && m.instructions != null) {
                InsnList clr = new InsnList();
                clr.add(new FieldInsnNode(
                        Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "sunlit$idFilterCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
                clr.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "clear", "()V", false));
                m.instructions.insert(clr);
            }
        }

        // Before lookupGet:
        //   if (disabledFilters.isEmpty()) return false;
        //   ResourceLocation rl = stack.getId();
        //   Boolean cached = rl != null ? (Boolean) sunlit$idFilterCache.get(rl) : null;
        //   if (cached != null) { flag = cached.booleanValue(); goto afterLookup; }
        // After storeFlag (on miss):
        //   if (rl != null) sunlit$idFilterCache.put(rl, Boolean.valueOf(flag));
        //   afterLookup: ...
        // Use local slot 5 for rl and 6 for cached (isDisabled max locals is 5: 0..4)
        LabelNode runOrig = new LabelNode();
        LabelNode afterLookup = new LabelNode();
        LabelNode skipPut = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "disabledFilters", "Ljava/util/List;"));
        pre.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        LabelNode notEmpty = new LabelNode();
        pre.add(new JumpInsnNode(Opcodes.IFEQ, notEmpty));
        pre.add(new InsnNode(Opcodes.ICONST_0));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(notEmpty);
        pre.add(new FrameNode(
                Opcodes.F_FULL, 3,
                new Object[] {"dev/emi/emi/api/stack/EmiIngredient", "java/util/Iterator", "dev/emi/emi/api/stack/EmiStack"},
                0, new Object[0]));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "dev/emi/emi/api/stack/EmiStack", "getId",
                "()Lnet/minecraft/resources/ResourceLocation;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 5));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, runOrig));
        pre.add(new FieldInsnNode(
                Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "sunlit$idFilterCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pre.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        pre.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 6));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 6));
        pre.add(new JumpInsnNode(Opcodes.IFNULL, runOrig));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 6));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
        pre.add(new VarInsnNode(Opcodes.ISTORE, 3));
        pre.add(new JumpInsnNode(Opcodes.GOTO, afterLookup));
        pre.add(runOrig);
        pre.add(new FrameNode(
                Opcodes.F_FULL, 6,
                new Object[] {
                        "dev/emi/emi/api/stack/EmiIngredient", "java/util/Iterator", "dev/emi/emi/api/stack/EmiStack",
                        Opcodes.TOP, Opcodes.TOP, "net/minecraft/resources/ResourceLocation"
                },
                0, new Object[0]));
        isDisabledM.instructions.insertBefore(lookupGet, pre);

        InsnList post = new InsnList();
        post.add(new VarInsnNode(Opcodes.ALOAD, 5));
        post.add(new JumpInsnNode(Opcodes.IFNULL, skipPut));
        post.add(new FieldInsnNode(
                Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "sunlit$idFilterCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
        post.add(new VarInsnNode(Opcodes.ALOAD, 5));
        post.add(new VarInsnNode(Opcodes.ILOAD, 3));
        post.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
        post.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        post.add(new InsnNode(Opcodes.POP));
        post.add(skipPut);
        post.add(afterLookup);
        post.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {
                        "dev/emi/emi/api/stack/EmiIngredient", "java/util/Iterator", "dev/emi/emi/api/stack/EmiStack",
                        Opcodes.INTEGER
                },
                0, new Object[0]));
        isDisabledM.instructions.insert(storeFlag, post);

        // Also add O(1) entry fast-path when all 4 disabled collections are empty
        LabelNode runEntry = new LabelNode();
        InsnList entryFast = new InsnList();
        entryFast.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "disabledStacks", "Ljava/util/Set;"));
        entryFast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", true));
        entryFast.add(new JumpInsnNode(Opcodes.IFEQ, runEntry));
        entryFast.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "pluginDisabledStacks", "Ljava/util/Set;"));
        entryFast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "isEmpty", "()Z", true));
        entryFast.add(new JumpInsnNode(Opcodes.IFEQ, runEntry));
        entryFast.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "pluginDisabledFilters", "Ljava/util/List;"));
        entryFast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        entryFast.add(new JumpInsnNode(Opcodes.IFEQ, runEntry));
        entryFast.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_HIDDEN_CLS, "disabledFilters", "Ljava/util/List;"));
        entryFast.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        entryFast.add(new JumpInsnNode(Opcodes.IFEQ, runEntry));
        entryFast.add(new InsnNode(Opcodes.ICONST_0));
        entryFast.add(new InsnNode(Opcodes.IRETURN));
        entryFast.add(runEntry);
        entryFast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        isDisabledM.instructions.insert(entryFast);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces Forge's {@code f_38728_.stream().map(...).collect(Collectors.toList())} in
     * {@code FoodProperties.m_38749_} ({@code getEffects}) with a zero-allocation empty fast path
     * and direct index loop (~57MB garbage eliminated during food tooltip/EMI scans).
     */
    public static byte[] transformFoodProperties(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String pairCls = "com/mojang/datafixers/util/Pair";
        for (MethodNode mn : cn.methods) {
            if (!"m_38749_".equals(mn.name) || !"()Ljava/util/List;".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            boolean hasStream = false;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min && "java/util/List".equals(min.owner) && "stream".equals(min.name)) {
                    hasStream = true;
                    break;
                }
            }
            if (!hasStream) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode notEmpty = new LabelNode();
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            LabelNode nullFirst = new LabelNode();
            LabelNode makePair = new LabelNode();
            // List src = this.f_38728_; int n = src.size(); if (n == 0) return Collections.emptyList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_FOOD_PROPERTIES_CLS, "f_38728_", "Ljava/util/List;"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IFNE, notEmpty));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            // ArrayList out = new ArrayList(n);
            il.add(notEmpty);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {MC_FOOD_PROPERTIES_CLS, "java/util/List", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(loopCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {MC_FOOD_PROPERTIES_CLS, "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            // Pair p = (Pair) src.get(i);
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, pairCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pairCls, "getFirst", "()Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/function/Supplier"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 6));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new JumpInsnNode(Opcodes.IFNULL, nullFirst));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true));
            il.add(new JumpInsnNode(Opcodes.GOTO, makePair));
            il.add(nullFirst);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {MC_FOOD_PROPERTIES_CLS, "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, pairCls, "java/util/function/Supplier"},
                    1, new Object[] {"java/util/ArrayList"}));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(makePair);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {MC_FOOD_PROPERTIES_CLS, "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, pairCls, "java/util/function/Supplier"},
                    2, new Object[] {"java/util/ArrayList", "java/lang/Object"}));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, pairCls, "getSecond", "()Ljava/lang/Object;", false));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, pairCls, "of", "(Ljava/lang/Object;Ljava/lang/Object;)L" + pairCls + ";", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new IincInsnNode(4, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {MC_FOOD_PROPERTIES_CLS, "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Short-circuits {@code ForgeI18n.parseFormat(String, Object...)} when the format string
     * contains no {@code '{'} and no {@code '\''}, avoiding {@code ExtendedMessageFormat} and
     * {@code MessageFormat.applyPattern} allocations (~46MB+ in tooltips/EMI).
     */
    public static byte[] transformForgeI18n(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"parseFormat".equals(mn.name)
                    || !"(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            LabelNode slow = new LabelNode();
            InsnList fast = new InsnList();
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new JumpInsnNode(Opcodes.IFNULL, slow));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new IntInsnNode(Opcodes.BIPUSH, '{'));
            fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
            fast.add(new JumpInsnNode(Opcodes.IFGE, slow));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new IntInsnNode(Opcodes.BIPUSH, '\''));
            fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
            fast.add(new JumpInsnNode(Opcodes.IFGE, slow));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new InsnNode(Opcodes.ARETURN));
            fast.add(slow);
            fast.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(fast);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Short-circuits Curios {@code ClientEventHandler.onTooltip} as soon as
     * {@code CuriosApi.getItemStackSlots(...)} returns an empty map (99% of all 20,000+ items
     * scanned by EMI search), avoiding {@code new HashMap(map)}, {@code new HashSet()},
     * {@code Set.copyOf(map.keySet())} (~82MB), and {@code new ArrayList(set)}.
     */
    public static byte[] transformCuriosClientEventHandler(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"onTooltip".equals(mn.name)
                    || !"(Lnet/minecraftforge/event/entity/player/ItemTooltipEvent;)V".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            // Anchor 1: `ASTORE 7` immediately followed by `NEW java/util/HashMap; DUP; ALOAD 7; HashMap.<init>(Map)`
            VarInsnNode firstStore7 = null;
            // Anchor 2: `ALOAD 7; INVOKEINTERFACE Map.keySet(); INVOKESTATIC Set.copyOf`
            VarInsnNode beforeSetCopyOf = null;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (firstStore7 == null
                        && insn instanceof VarInsnNode vin
                        && vin.getOpcode() == Opcodes.ASTORE
                        && vin.var == 7) {
                    AbstractInsnNode n1 = nextRealInsn(vin);
                    AbstractInsnNode n2 = nextRealInsn(n1);
                    AbstractInsnNode n3 = nextRealInsn(n2);
                    if (n1 instanceof TypeInsnNode tin && tin.getOpcode() == Opcodes.NEW && "java/util/HashMap".equals(tin.desc)
                            && n2 != null && n2.getOpcode() == Opcodes.DUP
                            && n3 instanceof VarInsnNode load7 && load7.getOpcode() == Opcodes.ALOAD && load7.var == 7) {
                        firstStore7 = vin;
                    }
                } else if (beforeSetCopyOf == null
                        && insn instanceof VarInsnNode vin
                        && vin.getOpcode() == Opcodes.ALOAD
                        && vin.var == 7) {
                    AbstractInsnNode n1 = nextRealInsn(vin);
                    AbstractInsnNode n2 = nextRealInsn(n1);
                    if (n1 instanceof MethodInsnNode m1 && "java/util/Map".equals(m1.owner) && "keySet".equals(m1.name)
                            && n2 instanceof MethodInsnNode m2 && "java/util/Set".equals(m2.owner) && "copyOf".equals(m2.name)) {
                        beforeSetCopyOf = vin;
                    }
                } else if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "top/theillusivec4/curios/api/CuriosApi".equals(min.owner)
                        && "getAttributeModifiers".equals(min.name)
                        && "(Ltop/theillusivec4/curios/api/SlotContext;Ljava/util/UUID;Lnet/minecraft/world/item/ItemStack;)Lcom/google/common/collect/Multimap;".equals(min.desc)) {
                    min.owner = CURIOS_HOOKS_CLS;
                    min.name = "sunlit$getAttributeModifiersReadOnly";
                    patched = true;
                }
            }
            if (firstStore7 != null) {
                LabelNode cont1 = new LabelNode();
                InsnList g1 = new InsnList();
                g1.add(new VarInsnNode(Opcodes.ALOAD, 7));
                g1.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
                g1.add(new JumpInsnNode(Opcodes.IFEQ, cont1));
                g1.add(new InsnNode(Opcodes.RETURN));
                g1.add(cont1);
                g1.add(new FrameNode(
                        Opcodes.F_NEW, 8,
                        new Object[] {
                                CURIOS_CLIENT_EVENTS_CLS,
                                "net/minecraftforge/event/entity/player/ItemTooltipEvent",
                                "net/minecraft/world/item/ItemStack",
                                "net/minecraft/world/entity/player/Player",
                                "java/util/List",
                                "net/minecraft/nbt/CompoundTag",
                                Opcodes.INTEGER,
                                "java/util/Map"
                        },
                        0, new Object[0]));
                mn.instructions.insert(firstStore7, g1);
                patched = true;
            }
            if (beforeSetCopyOf != null) {
                FrameNode prevFrame = null;
                for (AbstractInsnNode scan = beforeSetCopyOf.getPrevious(); scan != null; scan = scan.getPrevious()) {
                    if (scan instanceof FrameNode fn) {
                        prevFrame = fn;
                        break;
                    }
                    if (scan.getOpcode() >= 0) {
                        break;
                    }
                }
                if (prevFrame != null) {
                    LabelNode cont2 = new LabelNode();
                    InsnList g2 = new InsnList();
                    g2.add(new VarInsnNode(Opcodes.ALOAD, 7));
                    g2.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
                    g2.add(new JumpInsnNode(Opcodes.IFEQ, cont2));
                    g2.add(new InsnNode(Opcodes.RETURN));
                    g2.add(cont2);
                    g2.add(prevFrame.clone(null));
                    mn.instructions.insertBefore(beforeSetCopyOf, g2);
                    patched = true;
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pre-sizes {@code SuffixArray}'s backing {@code IntArrayList}s in {@code <init>} so EMI's
     * 4 large search indexes skip the first 11-12 array-doubling allocations (~60MB saved in GP +0..5s).
     */
    public static byte[] transformSuffixArray(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "it/unimi/dsi/fastutil/ints/IntArrayList".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    AbstractInsnNode nxt = nextRealInsn(min);
                    if (nxt instanceof FieldInsnNode fin && fin.getOpcode() == Opcodes.PUTFIELD) {
                        int cap = "f_119961_".equals(fin.name) ? 16384 : 262144;
                        mn.instructions.insertBefore(min, new LdcInsnNode(cap));
                        min.desc = "(I)V";
                        patched = true;
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches immutable {@code NbtUtils.writeBlockState(BlockState)} results per {@code BlockState}
     * in JourneyMap's {@code RegionData.setBlockState} (~157MB garbage eliminated across map scans).
     */
    public static byte[] transformJourneyMapRegionData(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
            }
            if ("setBlockState".equals(mn.name)
                    && "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Ljourneymap/client/model/MapType;)V"
                            .equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "net/minecraft/nbt/NbtUtils".equals(min.owner)
                            && "m_129202_".equals(min.name)
                            && "(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/nbt/CompoundTag;".equals(min.desc)) {
                        min.owner = JM_REGION_DATA_CLS;
                        min.name = "sunlit$writeBlockStateCached";
                        patched = true;
                    } else if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/lang/String".equals(min.owner)
                            && "valueOf".equals(min.name)
                            && "(I)Ljava/lang/String;".equals(min.desc)) {
                        min.owner = PAYLOAD_CLS;
                        min.name = "fastIntToString";
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "sunlit$stateNbtCache", "Ljava/util/concurrent/ConcurrentHashMap;", null, null));
        InsnList initMap = new InsnList();
        initMap.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/ConcurrentHashMap"));
        initMap.add(new InsnNode(Opcodes.DUP));
        initMap.add(new LdcInsnNode(512));
        initMap.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "(I)V", false));
        initMap.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, JM_REGION_DATA_CLS, "sunlit$stateNbtCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
        if (clinit != null && clinit.instructions != null) {
            clinit.instructions.insert(initMap);
        } else {
            MethodNode newClinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            newClinit.instructions.add(initMap);
            newClinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(newClinit);
        }

        final String bsCls = "net/minecraft/world/level/block/state/BlockState";
        final String ctCls = "net/minecraft/nbt/CompoundTag";
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlit$writeBlockStateCached",
                "(L" + bsCls + ";)L" + ctCls + ";",
                null,
                null);
        {
            InsnList il = helper.instructions;
            LabelNode miss = new LabelNode();
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, JM_REGION_DATA_CLS, "sunlit$stateNbtCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ctCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(miss);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/nbt/NbtUtils", "m_129202_", "(L" + bsCls + ";)L" + ctCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new FieldInsnNode(
                    Opcodes.GETSTATIC, JM_REGION_DATA_CLS, "sunlit$stateNbtCache", "Ljava/util/concurrent/ConcurrentHashMap;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        cn.methods.add(helper);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~302MB of regex {@code String.split("\\R")} line allocations in Iris
     * {@code ConstDirectiveParser.findDirectives} by scanning for candidate lines containing
     * {@code "const"}, {@code '='}, and {@code ';'} in {@link BootJarPayload}.
     */
    public static byte[] transformIrisConstDirectiveParser(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"findDirectives".equals(mn.name)
                    || !"(Ljava/lang/String;)Ljava/util/List;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            LabelNode nextIter = new LabelNode();
            // List cands = BootJarPayload.extractConstCandidateLines(source);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "extractConstCandidateLines",
                    "(Ljava/lang/String;)Ljava/util/List;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2));
            // ArrayList out = new ArrayList(n);
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));
            il.add(loopCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/lang/String", "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            // Optional opt = findDirectiveInLine((String) cands.get(i));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, IRIS_CONST_DIRECTIVE_CLS, "findDirectiveInLine",
                    "(Ljava/lang/String;)Ljava/util/Optional;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(nextIter);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/lang/String", "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(4, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code list.stream().flatMap(i -> i.getEmiStacks().stream()).toList()} and
     * {@code Arrays.stream(items).map(EmiStack::of).toList()} in {@code EmiIngredient.of} with
     * direct index loops (~155MB garbage eliminated during post-join EMI recipe baking).
     */
    public static byte[] transformEmiIngredient(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedFlat = false;
        boolean patchedArr = false;
        for (MethodNode mn : cn.methods) {
            if ("of".equals(mn.name)
                    && "(Ljava/util/List;J)Ldev/emi/emi/api/stack/EmiIngredient;".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode m1
                            && m1.getOpcode() == Opcodes.INVOKEINTERFACE
                            && "java/util/List".equals(m1.owner)
                            && "stream".equals(m1.name)
                            && "()Ljava/util/stream/Stream;".equals(m1.desc)) {
                        AbstractInsnNode n1 = nextRealInsn(m1);
                        AbstractInsnNode n2 = nextRealInsn(n1);
                        AbstractInsnNode n3 = nextRealInsn(n2);
                        if (n1 instanceof InvokeDynamicInsnNode
                                && n2 instanceof MethodInsnNode m2
                                && "java/util/stream/Stream".equals(m2.owner) && "flatMap".equals(m2.name)
                                && n3 instanceof MethodInsnNode m3
                                && "java/util/stream/Stream".equals(m3.owner) && "toList".equals(m3.name)) {
                            mn.instructions.set(m1, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    EMI_INGREDIENT_CLS,
                                    "sunlit$flattenStacks",
                                    "(Ljava/util/List;)Ljava/util/List;",
                                    true));
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            mn.instructions.remove(n3);
                            patchedFlat = true;
                            break;
                        }
                    }
                }
            } else if ("of".equals(mn.name)
                    && "(Lnet/minecraft/world/item/crafting/Ingredient;J)Ldev/emi/emi/api/stack/EmiIngredient;".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode m1
                            && m1.getOpcode() == Opcodes.INVOKESTATIC
                            && "java/util/Arrays".equals(m1.owner)
                            && "stream".equals(m1.name)
                            && "([Ljava/lang/Object;)Ljava/util/stream/Stream;".equals(m1.desc)) {
                        AbstractInsnNode n1 = nextRealInsn(m1);
                        AbstractInsnNode n2 = nextRealInsn(n1);
                        AbstractInsnNode n3 = nextRealInsn(n2);
                        if (n1 instanceof InvokeDynamicInsnNode
                                && n2 instanceof MethodInsnNode m2
                                && "java/util/stream/Stream".equals(m2.owner) && "map".equals(m2.name)
                                && n3 instanceof MethodInsnNode m3
                                && "java/util/stream/Stream".equals(m3.owner) && "toList".equals(m3.name)) {
                            mn.instructions.set(m1, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    EMI_INGREDIENT_CLS,
                                    "sunlit$stacksFromItems",
                                    "([Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
                                    true));
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            mn.instructions.remove(n3);
                            patchedArr = true;
                            break;
                        }
                    }
                }
            }
        }
        if (!patchedFlat && !patchedArr) {
            return null;
        }
        if (patchedFlat) {
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$flattenStacks",
                    "(Ljava/util/List;)Ljava/util/List;",
                    null,
                    null);
            InsnList il = helper.instructions;
            LabelNode outerCond = new LabelNode();
            LabelNode outerEnd = new LabelNode();
            LabelNode innerCond = new LabelNode();
            LabelNode innerEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 1));
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(outerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            LabelNode notSingleStack2 = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, "dev/emi/emi/api/stack/EmiStack"));
            il.add(new JumpInsnNode(Opcodes.IFEQ, notSingleStack2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerEnd));
            il.add(notSingleStack2);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, "java/lang/Object"},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, EMI_INGREDIENT_CLS));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, EMI_INGREDIENT_CLS, "getEmiStacks", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));
            il.add(innerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, innerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new IincInsnNode(6, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerCond));
            il.add(innerEnd);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(3, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, outerCond));
            il.add(outerEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(helper);
        }
        if (patchedArr) {
            final String isCls = "net/minecraft/world/item/ItemStack";
            final String esCls = "dev/emi/emi/api/stack/EmiStack";
            MethodNode helper = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$stacksFromItems",
                    "([L" + isCls + ";)Ljava/util/List;",
                    null,
                    null);
            InsnList il = helper.instructions;
            LabelNode loopCond = new LabelNode();
            LabelNode loopEnd = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ARRAYLENGTH));
            il.add(new VarInsnNode(Opcodes.ISTORE, 1));
            il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(loopCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"[L" + isCls + ";", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new InsnNode(Opcodes.AALOAD));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, esCls, "of", "(L" + isCls + ";)L" + esCls + ";", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new IincInsnNode(3, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
            il.add(loopEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(helper);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates ~134MB of {@code Pattern.compile("_")} and {@code BreakIterator.clone()} churn in
     * Create's {@code TooltipHelper.cutTextComponent} during EMI search tooltip indexing.
     */
    public static byte[] transformCreateTooltipHelper(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"cutTextComponent".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "java/text/BreakIterator".equals(min.owner)
                        && "getLineInstance".equals(min.name)
                        && "(Ljava/util/Locale;)Ljava/text/BreakIterator;".equals(min.desc)) {
                    min.owner = PAYLOAD_CLS;
                    min.name = "getCachedLineBreakIterator";
                    patched = true;
                } else if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(min.owner)
                        && "replaceAll".equals(min.name)
                        && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(min.desc)) {
                    AbstractInsnNode p1 = prevRealInsn(min);
                    AbstractInsnNode p2 = prevRealInsn(p1);
                    if (p1 instanceof LdcInsnNode l1 && "".equals(l1.cst)
                            && p2 instanceof LdcInsnNode l2 && "_".equals(l2.cst)) {
                        mn.instructions.remove(p2);
                        mn.instructions.remove(p1);
                        mn.instructions.set(min, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, PAYLOAD_CLS, "stripUnderscores",
                                "(Ljava/lang/String;)Ljava/lang/String;", false));
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces Flywheel {@code StringUtil.countLines(String)} char-by-char loop with vectorized
     * {@code String.indexOf('\n')} scanning in {@link BootJarPayload#fastCountLines}.
     */
    public static byte[] transformFlywheelStringUtil(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("countLines".equals(mn.name) && "(Ljava/lang/String;)I".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "fastCountLines", "(Ljava/lang/String;)I", false));
                il.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pre-sizes {@code IntArrayFIFOQueue} in {@code VisGraph.m_112959_} ({@code floodFill}) from
     * default capacity 4 to 64 to reduce resize allocations during chunk meshing.
     */
    public static byte[] transformVisGraph(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"m_112959_".equals(mn.name) || !"(I)Ljava/util/Set;".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "it/unimi/dsi/fastutil/ints/IntArrayFIFOQueue".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    mn.instructions.insertBefore(min, new IntInsnNode(Opcodes.BIPUSH, 64));
                    min.desc = "(I)V";
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Short-circuits {@code com.wdiscute.libtooltips.Tooltips.hasTags(String)} before allocating
     * a {@code Matcher} when the string contains no {@code '<'} or {@code '/'} (~137MB saved in GP +0..10s).
     */
    public static byte[] transformLibTooltips(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"hasTags".equals(mn.name)
                    || !"(Ljava/lang/String;)Z".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            LabelNode retFalse = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new JumpInsnNode(Opcodes.IFNULL, retFalse));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new IntInsnNode(Opcodes.BIPUSH, '<'));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
            guard.add(new JumpInsnNode(Opcodes.IFLT, retFalse));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
            guard.add(new JumpInsnNode(Opcodes.IFLT, retFalse));
            mn.instructions.insert(guard);
            InsnList tail = new InsnList();
            tail.add(retFalse);
            tail.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] {"java/lang/String"}, 0, new Object[0]));
            tail.add(new InsnNode(Opcodes.ICONST_0));
            tail.add(new InsnNode(Opcodes.IRETURN));
            mn.instructions.add(tail);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces Colorwheel's {@code ProgramSource.colorwheel$parseShaderExtensions(String)}
     * {@code source.lines().forEach(line -> line.trim()...)} with zero-stream {@code #extension}
     * scanning (~125MB saved during shaderpack load).
     */
    public static byte[] transformIrisProgramSource(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("colorwheel$parseShaderExtensions".equals(mn.name)
                    && "(Ljava/lang/String;)Ljava/util/List;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "parseColorwheelShaderExtensions",
                        "(Ljava/lang/String;)Ljava/util/List;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Checks {@code entity instanceof Leashable} and {@code vb$getLeashHolder() != null} BEFORE
     * allocating {@code entity.getBoundingBoxForCulling().inflate(0.5)} in VanillaBackport's
     * {@code LeashFeatureRenderer.shouldRender} (~95MB Render-thread AABB churn eliminated).
     */
    public static byte[] transformVanillaBackportLeashRenderer(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String ownerCls = cn.name;
        final String entCls = "net/minecraft/world/entity/Entity";
        final String fruCls = "net/minecraft/client/renderer/culling/Frustum";
        final String aabbCls = "net/minecraft/world/phys/AABB";
        final String leashCls = VB122_LEASH_RENDERER_CLS.equals(ownerCls)
                ? "com/blackgear/vanillabackport/common/api/modules/leash_behavior/Leashable"
                : "com/blackgear/vanillabackport/common/api/leash/Leashable";
        for (MethodNode mn : cn.methods) {
            if (!"shouldRender".equals(mn.name)
                    || !("(L" + entCls + ";L" + fruCls + ";ZZ)Z").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode retOrig = new LabelNode();
            LabelNode boxFallback = new LabelNode();
            LabelNode boxReady = new LabelNode();
            LabelNode retTrue = new LabelNode();
            // if (shouldRender) return originalReturn;
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IFNE, retOrig));
            // if (!(entity instanceof Leashable)) return originalReturn;
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, leashCls));
            il.add(new JumpInsnNode(Opcodes.IFEQ, retOrig));
            // Entity holder = ((Leashable) entity).vb$getLeashHolder();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, leashCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, leashCls, "vb$getLeashHolder", "()L" + entCls + ";", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IFNULL, retOrig));
            // AABB box = entity.m_6921_().m_82400_(0.5D);
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_6921_", "()L" + aabbCls + ";", false));
            il.add(new LdcInsnNode(0.5D));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, aabbCls, "m_82400_", "(D)L" + aabbCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 6));
            // if (box.m_82392_() || box.m_82309_() == 0.0D) box = new AABB(x-2, y-2, z-2, x+2, y+2, z+2);
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, aabbCls, "m_82392_", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, boxFallback));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, aabbCls, "m_82309_", "()D", false));
            il.add(new InsnNode(Opcodes.DCONST_0));
            il.add(new InsnNode(Opcodes.DCMPL));
            il.add(new JumpInsnNode(Opcodes.IFNE, boxReady));
            il.add(boxFallback);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {ownerCls, entCls, fruCls, Opcodes.INTEGER, Opcodes.INTEGER, entCls, aabbCls},
                    0, new Object[0]));
            il.add(new TypeInsnNode(Opcodes.NEW, aabbCls));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20185_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DSUB));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20186_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DSUB));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20189_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DSUB));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20185_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DADD));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20186_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DADD));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_20189_", "()D", false));
            il.add(new LdcInsnNode(2.0D));
            il.add(new InsnNode(Opcodes.DADD));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, aabbCls, "<init>", "(DDDDDD)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 6));
            // boxReady: if (frustum.m_113029_(box)) return true;
            il.add(boxReady);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fruCls, "m_113029_", "(L" + aabbCls + ";)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
            // AABB holderBox = holder.m_6921_();
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, entCls, "m_6921_", "()L" + aabbCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 7));
            // if (frustum.m_113029_(holderBox)) return true;
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fruCls, "m_113029_", "(L" + aabbCls + ";)Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, retTrue));
            // return frustum.m_113029_(box.m_82367_(holderBox));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 6));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, aabbCls, "m_82367_", "(L" + aabbCls + ";)L" + aabbCls + ";", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, fruCls, "m_113029_", "(L" + aabbCls + ";)Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(retTrue);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {ownerCls, entCls, fruCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(retOrig);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new InsnNode(Opcodes.IRETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds cached JEI subtype fields to {@code ItemEmiStack} so {@code JemiPlugin.lambda$parseSubtypes$15}
     * does not allocate a fresh {@code ItemStack} and re-serialize NBT on every hash/equals probe.
     */
    public static byte[] transformEmiItemStack(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (FieldNode fn : cn.fields) {
            if ("sunlit$subMgr".equals(fn.name)) {
                return null;
            }
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, "sunlit$subMgr", "Ljava/lang/Object;", null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC, "sunlit$subVal", "Ljava/lang/String;", null, null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches JEI {@code UidContext.Recipe} subtype results in {@code JemiPlugin.lambda$parseSubtypes$15}
     * (per-{@code Item} when NBT is null, per-{@code ItemEmiStack} instance when NBT is non-null),
     * eliminating ~70MB of temporary {@code ItemStack} and NBT string allocations during EMI bake.
     */
    public static byte[] transformEmiJemiPlugin(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        final String mgrCls = "mezz/jei/api/ingredients/subtypes/ISubtypeManager";
        final String ctxCls = "mezz/jei/api/ingredients/subtypes/UidContext";
        final String isCls = "net/minecraft/world/item/ItemStack";
        for (MethodNode mn : cn.methods) {
            if (!"lambda$parseSubtypes$15".equals(mn.name)
                    || !("(L" + esCls + ";)Ljava/lang/String;").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode hasNbt = new LabelNode();
            LabelNode fallback = new LabelNode();
            LabelNode computeNoNbt = new LabelNode();
            LabelNode computeNbt = new LabelNode();
            // ISubtypeManager mgr = JemiPlugin.subtypeManager;
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_JEMI_PLUGIN_CLS, "subtypeManager", "L" + mgrCls + ";"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            // if (!(stack instanceof ItemEmiStack)) goto fallback;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, EMI_ITEM_STACK_CLS));
            il.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, EMI_ITEM_STACK_CLS));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            // if (ies.getNbt() != null) goto hasNbt;
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_ITEM_STACK_CLS, "getNbt", "()Lnet/minecraft/nbt/CompoundTag;", false));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasNbt));
            // Object key = ies.getKey(); String cached = BootJarPayload.getCachedNoNbtSubtype(mgr, key);
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_ITEM_STACK_CLS, "getKey", "()Ljava/lang/Object;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getCachedNoNbtSubtype",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IFNULL, computeNoNbt));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new InsnNode(Opcodes.ARETURN));
            // computeNoNbt:
            il.add(computeNoNbt);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {esCls, mgrCls, EMI_ITEM_STACK_CLS, "java/lang/Object"},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_ITEM_STACK_CLS, "getItemStack", "()L" + isCls + ";", false));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, ctxCls, "Recipe", "L" + ctxCls + ";"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, mgrCls, "getSubtypeInfo",
                    "(L" + isCls + ";L" + ctxCls + ";)Ljava/lang/String;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "putCachedNoNbtSubtype",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new InsnNode(Opcodes.ARETURN));
            // hasNbt: if (ies.sunlit$subMgr == mgr) return ies.sunlit$subVal;
            il.add(hasNbt);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {esCls, mgrCls, EMI_ITEM_STACK_CLS},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ITEM_STACK_CLS, "sunlit$subMgr", "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IF_ACMPNE, computeNbt));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ITEM_STACK_CLS, "sunlit$subVal", "Ljava/lang/String;"));
            il.add(new InsnNode(Opcodes.ARETURN));
            // computeNbt:
            il.add(computeNbt);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_ITEM_STACK_CLS, "getItemStack", "()L" + isCls + ";", false));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, ctxCls, "Recipe", "L" + ctxCls + ";"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, mgrCls, "getSubtypeInfo",
                    "(L" + isCls + ";L" + ctxCls + ";)Ljava/lang/String;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, EMI_ITEM_STACK_CLS, "sunlit$subVal", "Ljava/lang/String;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, EMI_ITEM_STACK_CLS, "sunlit$subMgr", "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            // fallback:
            il.add(fallback);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 2,
                    new Object[] {esCls, mgrCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "getItemStack", "()L" + isCls + ";", false));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, ctxCls, "Recipe", "L" + ctxCls + ";"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, mgrCls, "getSubtypeInfo",
                    "(L" + isCls + ";L" + ctxCls + ";)Ljava/lang/String;", true));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates capturing lambda allocation in JEI {@code SubtypeManager.getSubtypeInfo}
     * ({@code Optional.map(i -> i.apply(ingredient, context)).orElse("")}, ~28MB saved in GP).
     */
    public static byte[] transformJeiSubtypeManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String itCls = "mezz/jei/api/ingredients/IIngredientTypeWithSubtypes";
        final String ctxCls = "mezz/jei/api/ingredients/subtypes/UidContext";
        final String interpMapCls = "mezz/jei/library/ingredients/subtypes/SubtypeInterpreters";
        final String interpCls = "mezz/jei/api/ingredients/subtypes/IIngredientSubtypeInterpreter";
        for (MethodNode mn : cn.methods) {
            if (!"getSubtypeInfo".equals(mn.name)
                    || !("(L" + itCls + ";Ljava/lang/Object;L" + ctxCls + ";)Ljava/lang/String;").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode empty = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new LdcInsnNode("ingredientType"));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mezz/jei/common/util/ErrorUtil", "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new LdcInsnNode("ingredient"));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mezz/jei/common/util/ErrorUtil", "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new LdcInsnNode("context"));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "mezz/jei/common/util/ErrorUtil", "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, JEI_SUBTYPE_MANAGER_CLS, "interpreters", "L" + interpMapCls + ";"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, interpMapCls, "get",
                    "(L" + itCls + ";Ljava/lang/Object;)Ljava/util/Optional;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, empty));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, interpCls));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, interpCls, "apply",
                    "(Ljava/lang/Object;L" + ctxCls + ";)Ljava/lang/String;", true));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(empty);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {JEI_SUBTYPE_MANAGER_CLS, itCls, "java/lang/Object", ctxCls},
                    0, new Object[0]));
            il.add(new LdcInsnNode(""));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates {@code new BlockPos} allocation in JourneyMap {@code ChunkMD.getPrecipitationHeight(int, int)}
     * by calling {@code LevelChunk.getHeight(Heightmap.Types.WORLD_SURFACE, toWorldX(x), toWorldZ(z))} directly
     * (~27MB saved in GP).
     */
    public static byte[] transformJourneyMapChunkMD(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String lcCls = "net/minecraft/world/level/chunk/LevelChunk";
        final String hmCls = "net/minecraft/world/level/levelgen/Heightmap$Types";
        for (MethodNode mn : cn.methods) {
            if (!"getPrecipitationHeight".equals(mn.name) || !"(II)I".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, JM_CHUNK_MD_CLS, "getChunk", "()L" + lcCls + ";", false));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, hmCls, "WORLD_SURFACE", "L" + hmCls + ";"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, JM_CHUNK_MD_CLS, "toWorldX", "(I)I", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, JM_CHUNK_MD_CLS, "toWorldZ", "(I)I", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, lcCls, "m_5885_", "(L" + hmCls + ";II)I", false));
            il.add(new InsnNode(Opcodes.IRETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds a 16-slot direct-mapped hot cache in front of JourneyMap {@code DataCache.chunkMetadata}
     * Guava cache in {@code getChunkMD(long)}, bypassing {@code LocalCache$Segment.recordRead}
     * (~59MB {@code ConcurrentLinkedQueue$Node} churn in GP) on 63/64 hits while keeping cache
     * invalidation exact across {@code addChunkMD}, {@code removeChunkMD}, and {@code invalidateChunkMDCache}.
     */
    public static byte[] transformJourneyMapDataCache(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patchedGet = false;
        boolean patchedMaint = false;
        final String mdCls = JM_CHUNK_MD_CLS;
        final String lcCls = "net/minecraft/world/level/chunk/LevelChunk";
        for (MethodNode mn : cn.methods) {
            if ("getChunkMD".equals(mn.name)
                    && ("(J)L" + mdCls + ";").equals(mn.desc)
                    && mn.instructions != null) {
                MethodInsnNode getIfPresent = null;
                MethodInsnNode cachePut = null;
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode min
                            && "com/google/common/cache/Cache".equals(min.owner)) {
                        if (getIfPresent == null && "getIfPresent".equals(min.name)) {
                            getIfPresent = min;
                        } else if ("put".equals(min.name)) {
                            cachePut = min;
                        }
                    }
                }
                if (getIfPresent != null) {
                    // Walk back 3 real insns to `aload_0; getfield chunkMetadata; aload 5; getIfPresent`
                    AbstractInsnNode p1 = prevRealInsn(getIfPresent);
                    AbstractInsnNode p2 = prevRealInsn(p1);
                    AbstractInsnNode p3 = prevRealInsn(p2);
                    // Find F_NEW frame right before p3
                    FrameNode entryFrame = null;
                    for (AbstractInsnNode scan = p3 != null ? p3.getPrevious() : null; scan != null; scan = scan.getPrevious()) {
                        if (scan instanceof FrameNode fn) {
                            entryFrame = fn;
                            break;
                        }
                        if (scan.getOpcode() >= 0) {
                            break;
                        }
                    }
                    // Find the hit return `aload 4; aload 3; monitorexit; areturn` shortly after getIfPresent
                    AbstractInsnNode hitReturnStart = null;
                    for (AbstractInsnNode scan = getIfPresent.getNext(); scan != null; scan = scan.getNext()) {
                        if (scan instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 4) {
                            AbstractInsnNode n1 = nextRealInsn(v1);
                            AbstractInsnNode n2 = nextRealInsn(n1);
                            AbstractInsnNode n3 = nextRealInsn(n2);
                            if (n1 instanceof VarInsnNode v2 && v2.getOpcode() == Opcodes.ALOAD && v2.var == 3
                                    && n2 != null && n2.getOpcode() == Opcodes.MONITOREXIT
                                    && n3 != null && n3.getOpcode() == Opcodes.ARETURN) {
                                hitReturnStart = v1;
                                break;
                            }
                        }
                    }
                    if (p3 != null && entryFrame != null && entryFrame.local != null
                            && entryFrame.local.size() >= 6
                            && entryFrame.local.get(1) == Opcodes.LONG
                            && hitReturnStart != null) {
                        LabelNode missLbl = new LabelNode();
                        LabelNode hitReturnLbl = new LabelNode();
                        InsnList pre = new InsnList();
                        // local 4 = (ChunkMD) BootJarPayload.jmChunkCacheGet(chunkLong);
                        pre.add(new VarInsnNode(Opcodes.LLOAD, 1));
                        pre.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCacheGet", "(J)Ljava/lang/Object;", false));
                        pre.add(new TypeInsnNode(Opcodes.CHECKCAST, mdCls));
                        pre.add(new VarInsnNode(Opcodes.ASTORE, 4));
                        pre.add(new VarInsnNode(Opcodes.ALOAD, 4));
                        pre.add(new JumpInsnNode(Opcodes.IFNULL, missLbl));
                        pre.add(new VarInsnNode(Opcodes.ALOAD, 4));
                        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mdCls, "hasChunk", "()Z", false));
                        pre.add(new JumpInsnNode(Opcodes.IFEQ, missLbl));
                        pre.add(new VarInsnNode(Opcodes.ALOAD, 4));
                        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mdCls, "getChunk", "()L" + lcCls + ";", false));
                        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, lcCls, "m_62953_", "()Lnet/minecraft/world/level/Level;", false));
                        pre.add(new VarInsnNode(Opcodes.ALOAD, 6));
                        pre.add(new FieldInsnNode(
                                Opcodes.GETFIELD, "net/minecraft/client/Minecraft",
                                "f_91073_", "Lnet/minecraft/client/multiplayer/ClientLevel;"));
                        pre.add(new JumpInsnNode(Opcodes.IF_ACMPNE, missLbl));
                        pre.add(new JumpInsnNode(Opcodes.GOTO, hitReturnLbl));
                        pre.add(missLbl);
                        pre.add(entryFrame.clone(null));
                        mn.instructions.insertBefore(p3, pre);

                        // Before hitReturnStart: populate hot cache on Guava hit, then hitReturnLbl
                        Object[] hitLocals = entryFrame.local.toArray(new Object[0]);
                        hitLocals[3] = mdCls; // JVM local 4 is index 3 (local 1-2 is LONG)
                        InsnList putAndHit = new InsnList();
                        putAndHit.add(new VarInsnNode(Opcodes.LLOAD, 1));
                        putAndHit.add(new VarInsnNode(Opcodes.ALOAD, 4));
                        putAndHit.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCachePut", "(JLjava/lang/Object;)V", false));
                        putAndHit.add(hitReturnLbl);
                        putAndHit.add(new FrameNode(Opcodes.F_NEW, hitLocals.length, hitLocals, 0, new Object[0]));
                        mn.instructions.insertBefore(hitReturnStart, putAndHit);

                        if (cachePut != null) {
                            InsnList put2 = new InsnList();
                            put2.add(new VarInsnNode(Opcodes.LLOAD, 1));
                            put2.add(new VarInsnNode(Opcodes.ALOAD, 4));
                            put2.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCachePut", "(JLjava/lang/Object;)V", false));
                            mn.instructions.insert(cachePut, put2);
                        }
                        patchedGet = true;
                    }
                }
            } else if ("addChunkMD".equals(mn.name)
                    && ("(L" + mdCls + ";)V").equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && "com/google/common/cache/Cache".equals(min.owner)
                            && "put".equals(min.name)) {
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mdCls, "getLongCoord", "()J", false));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCachePut", "(JLjava/lang/Object;)V", false));
                        mn.instructions.insert(min, hook);
                        patchedMaint = true;
                        break;
                    }
                }
            } else if ("removeChunkMD".equals(mn.name)
                    && ("(L" + mdCls + ";)V").equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && "com/google/common/cache/Cache".equals(min.owner)
                            && "invalidate".equals(min.name)) {
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mdCls, "getLongCoord", "()J", false));
                        hook.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCacheRemove", "(J)V", false));
                        mn.instructions.insert(min, hook);
                        patchedMaint = true;
                        break;
                    }
                }
            } else if ("invalidateChunkMDCache".equals(mn.name)
                    && "()V".equals(mn.desc)
                    && mn.instructions != null) {
                mn.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "jmChunkCacheClear", "()V", false));
                patchedMaint = true;
            }
        }
        if (!patchedGet && !patchedMaint) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds copy-on-write {@code sunlit$ensureOwnedStacks()} support to {@code TagEmiIngredient}
     * so {@code EmiTags.getValues} can share cached unmodifiable stack lists across recipes safely.
     */
    public static byte[] transformEmiTagIngredient(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedCtor = false;
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)
                    && "(Ldev/emi/emi/runtime/EmiTagKey;J)V".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList setShared = new InsnList();
                        setShared.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        setShared.add(new InsnNode(Opcodes.ICONST_1));
                        setShared.add(new FieldInsnNode(Opcodes.PUTFIELD, EMI_TAG_INGREDIENT_CLS, "sunlit$shared", "Z"));
                        mn.instructions.insertBefore(insn, setShared);
                        patchedCtor = true;
                    }
                }
            }
        }
        if (!patchedCtor) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlit$shared", "Z", null, null));
        MethodNode ensure = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "sunlit$ensureOwnedStacks",
                "()Ljava/util/List;",
                null,
                null);
        InsnList il = ensure.instructions;
        LabelNode alreadyOwned = new LabelNode();
        LabelNode loopCond = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_TAG_INGREDIENT_CLS, "sunlit$shared", "Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, alreadyOwned));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_TAG_INGREDIENT_CLS, "stacks", "Ljava/util/List;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        il.add(new VarInsnNode(Opcodes.ISTORE, 2));
        il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 4));
        il.add(loopCond);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {EMI_TAG_INGREDIENT_CLS, "java/util/List", Opcodes.INTEGER, "java/util/ArrayList", Opcodes.INTEGER},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 2));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new VarInsnNode(Opcodes.ILOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "copy", "()L" + esCls + ";", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new IincInsnNode(4, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
        il.add(loopEnd);
        il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, EMI_TAG_INGREDIENT_CLS, "stacks", "Ljava/util/List;"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, EMI_TAG_INGREDIENT_CLS, "sunlit$shared", "Z"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(alreadyOwned);
        il.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {EMI_TAG_INGREDIENT_CLS}, 0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_TAG_INGREDIENT_CLS, "stacks", "Ljava/util/List;"));
        il.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(ensure);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Optimizes {@code EmiTags}:
     * <ol>
     *   <li>Caches {@code getValues(EmiTagKey)} stack lists via {@code BootJarPayload} (cleared on
     *       {@code reload()}), eliminating ~376MB of duplicate {@code ItemEmiStack} allocations.</li>
     *   <li>Replaces {@code map.keySet().containsAll(tagList)} in {@code getIngredient} with
     *       {@code BootJarPayload.mapContainsAllList} (size pre-check + zero iterator, ~66MB saved)
     *       and pre-sizes {@code Maps.newHashMap()} (~51MB saved).</li>
     * </ol>
     */
    public static byte[] transformEmiTags(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patchedValues = false;
        boolean patchedReload = false;
        boolean patchedGetIng = false;
        final String etkCls = "dev/emi/emi/runtime/EmiTagKey";
        final String eraCls = "dev/emi/emi/api/stack/EmiRegistryAdapter";
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        for (MethodNode mn : cn.methods) {
            if ("reload".equals(mn.name) && "()V".equals(mn.desc) && mn.instructions != null) {
                mn.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "clearEmiTagValues", "()V", false));
                patchedReload = true;
            } else if ("getValues".equals(mn.name)
                    && ("(L" + etkCls + ";)Ljava/util/List;").equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode miss = new LabelNode();
                LabelNode retEmpty = new LabelNode();
                LabelNode loopCond = new LabelNode();
                LabelNode loopEnd = new LabelNode();
                // List cached = BootJarPayload.getEmiTagValues(key); if (cached != null) return cached;
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getEmiTagValues",
                        "(Ljava/lang/Object;)Ljava/util/List;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.ARETURN));
                // miss:
                il.add(miss);
                il.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {etkCls}, 0, new Object[0]));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_TAGS_CLS, "TAG_VALUES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, retEmpty));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, EMI_TAGS_CLS, "ADAPTERS_BY_REGISTRY", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, etkCls, "registry", "()Lnet/minecraft/core/Registry;", false));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, eraCls));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, retEmpty));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                il.add(new VarInsnNode(Opcodes.ISTORE, 3));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IFEQ, retEmpty));
                il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/emi/emi/EmiPort", "emptyExtraData", "()Lnet/minecraft/nbt/CompoundTag;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 5));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 6));
                il.add(loopCond);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 7,
                        new Object[] {etkCls, "java/util/List", eraCls, Opcodes.INTEGER, "java/util/ArrayList", "net/minecraft/nbt/CompoundTag", Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 5));
                il.add(new InsnNode(Opcodes.LCONST_1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, eraCls, "of",
                        "(Ljava/lang/Object;Lnet/minecraft/nbt/CompoundTag;J)L" + esCls + ";", true));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new IincInsnNode(6, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
                il.add(loopEnd);
                il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                il.add(new VarInsnNode(Opcodes.ALOAD, 4));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableList", "(Ljava/util/List;)Ljava/util/List;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 7));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 7));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "putEmiTagValues",
                        "(Ljava/lang/Object;Ljava/util/List;)V", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 7));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(retEmpty);
                il.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {etkCls}, 0, new Object[0]));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedValues = true;
            } else if ("getIngredient".equals(mn.name)
                    && "(Ljava/lang/Class;Ljava/util/List;J)Ldev/emi/emi/api/stack/EmiIngredient;".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESTATIC
                            && "com/google/common/collect/Maps".equals(min.owner)
                            && "newHashMap".equals(min.name)
                            && "()Ljava/util/HashMap;".equals(min.desc)) {
                        InsnList presize = new InsnList();
                        presize.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        presize.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                        mn.instructions.insertBefore(min, presize);
                        min.name = "newHashMapWithExpectedSize";
                        min.desc = "(I)Ljava/util/HashMap;";
                        patchedGetIng = true;
                    } else if (insn instanceof MethodInsnNode mKeySet
                            && mKeySet.getOpcode() == Opcodes.INVOKEINTERFACE
                            && "java/util/Map".equals(mKeySet.owner)
                            && "keySet".equals(mKeySet.name)) {
                        AbstractInsnNode n1 = nextRealInsn(mKeySet);
                        AbstractInsnNode n2 = nextRealInsn(n1);
                        if (n1 instanceof VarInsnNode vList && vList.getOpcode() == Opcodes.ALOAD
                                && n2 instanceof MethodInsnNode mContainsAll
                                && "java/util/Set".equals(mContainsAll.owner)
                                && "containsAll".equals(mContainsAll.name)) {
                            mn.instructions.remove(mKeySet);
                            mn.instructions.set(mContainsAll, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "mapContainsAllList",
                                    "(Ljava/util/Map;Ljava/util/List;)Z", false));
                            patchedGetIng = true;
                        }
                    }
                }
            }
        }
        if (!patchedValues && !patchedReload && !patchedGetIng) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds an O(N) cached-remainder fast path for standard {@code ShapedRecipe} and {@code ShapelessRecipe}
     * in {@code EmiShapedRecipe.setRemainders} (~293MB {@code ItemStack}/{@code HashMap} churn eliminated),
     * plus copy-on-write isolation for shared {@code TagEmiIngredient} stack lists.
     */
    public static byte[] transformEmiShapedRecipe(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        final String ingCls = "dev/emi/emi/api/stack/EmiIngredient";
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        final String isCls = "net/minecraft/world/item/ItemStack";
        final String itemCls = "net/minecraft/world/item/Item";
        final String crCls = "net/minecraft/world/item/crafting/CraftingRecipe";
        for (MethodNode mn : cn.methods) {
            if (!"setRemainders".equals(mn.name)
                    || !("(Ljava/util/List;L" + crCls + ";)V").equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            // Also guard fallback path's TagEmiIngredient.getEmiStacks() so custom recipes never mutate shared tag stacks
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && ingCls.equals(min.owner)
                        && "getEmiStacks".equals(min.name)
                        && "()Ljava/util/List;".equals(min.desc)) {
                    // Only the second getEmiStacks call (before iterator -> setRemainder) needs owned stacks
                    AbstractInsnNode nxt = nextRealInsn(min);
                    if (nxt instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                        mn.instructions.set(min, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, EMI_SHAPED_RECIPE_CLS, "sunlit$getOwnedStacks",
                                "(L" + ingCls + ";)Ljava/util/List;", false));
                    }
                }
            }
            // Prepend fast path: if (sunlit$tryFastRemainders(input, recipe)) return;
            LabelNode fallback = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
            pre.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, EMI_SHAPED_RECIPE_CLS, "sunlit$tryFastRemainders",
                    "(Ljava/util/List;L" + crCls + ";)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
            pre.add(new InsnNode(Opcodes.RETURN));
            pre.add(fallback);
            pre.add(new FrameNode(
                    Opcodes.F_NEW, 2,
                    new Object[] {"java/util/List", crCls},
                    0, new Object[0]));
            mn.instructions.insert(pre);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        // Helper 1: sunlit$getOwnedStacks(EmiIngredient)
        {
            MethodNode own = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$getOwnedStacks",
                    "(L" + ingCls + ";)Ljava/util/List;",
                    null,
                    null);
            InsnList il = own.instructions;
            LabelNode notTag = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, EMI_TAG_INGREDIENT_CLS));
            il.add(new JumpInsnNode(Opcodes.IFEQ, notTag));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, EMI_TAG_INGREDIENT_CLS));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_TAG_INGREDIENT_CLS, "sunlit$ensureOwnedStacks", "()Ljava/util/List;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(notTag);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ingCls, "getEmiStacks", "()Ljava/util/List;", true));
            il.add(new InsnNode(Opcodes.ARETURN));
            cn.methods.add(own);
        }
        // Helper 2: sunlit$tryFastRemainders(List<EmiIngredient> input, CraftingRecipe recipe)
        {
            MethodNode fast = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "sunlit$tryFastRemainders",
                    "(Ljava/util/List;L" + crCls + ";)Z",
                    null,
                    null);
            InsnList il = fast.instructions;
            LabelNode isStandard = new LabelNode();
            LabelNode outerCond = new LabelNode();
            LabelNode outerNext = new LabelNode();
            LabelNode outerEnd = new LabelNode();
            LabelNode innerCond = new LabelNode();
            LabelNode innerNext = new LabelNode();
            LabelNode hasNbt = new LabelNode();
            LabelNode computeRem = new LabelNode();
            LabelNode applyRem = new LabelNode();
            LabelNode notTagApply = new LabelNode();
            LabelNode noDefRem = new LabelNode();
            LabelNode storeRem = new LabelNode();
            // Class cls = recipe.getClass(); if (cls != ShapedRecipe.class && cls != ShapelessRecipe.class) return false;
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new LdcInsnNode(Type.getObjectType("net/minecraft/world/item/crafting/ShapedRecipe")));
            il.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, isStandard));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new LdcInsnNode(Type.getObjectType("net/minecraft/world/item/crafting/ShapelessRecipe")));
            il.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, isStandard));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IRETURN));
            // isStandard:
            il.add(isStandard);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 2,
                    new Object[] {"java/util/List", crCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            // outer loop: i = 0 .. n-1
            il.add(outerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ingCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ingCls, "isEmpty", "()Z", true));
            il.add(new JumpInsnNode(Opcodes.IFNE, outerNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ingCls, "getEmiStacks", "()Ljava/util/List;", true));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 7));
            // inner loop: k = 0 .. stacks.size()-1
            il.add(innerCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 8,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outerNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 8));
            // Check ItemEmiStack without NBT -> cached per-Item remainder
            il.add(new VarInsnNode(Opcodes.ALOAD, 8));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, EMI_ITEM_STACK_CLS));
            il.add(new JumpInsnNode(Opcodes.IFEQ, hasNbt));
            il.add(new VarInsnNode(Opcodes.ALOAD, 8));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "getNbt", "()Lnet/minecraft/nbt/CompoundTag;", false));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasNbt));
            il.add(new VarInsnNode(Opcodes.ALOAD, 8));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "getKey", "()Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, itemCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 9));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getCachedItemRemainder",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 10));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new JumpInsnNode(Opcodes.IFNULL, computeRem));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "isEmpty", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, innerNext));
            il.add(new JumpInsnNode(Opcodes.GOTO, applyRem));
            // computeRem: ItemStack def = item.m_7968_();
            il.add(computeRem);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 10,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, esCls, itemCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, itemCls, "m_7968_", "()L" + isCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 11));
            il.add(new VarInsnNode(Opcodes.ALOAD, 11));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "hasCraftingRemainingItem", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, noDefRem));
            il.add(new VarInsnNode(Opcodes.ALOAD, 11));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "getCraftingRemainingItem", "()L" + isCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 12));
            il.add(new VarInsnNode(Opcodes.ALOAD, 12));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "m_41619_", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, noDefRem));
            il.add(new VarInsnNode(Opcodes.ALOAD, 12));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, esCls, "of", "(L" + isCls + ";)L" + esCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 10));
            il.add(new JumpInsnNode(Opcodes.GOTO, storeRem));
            il.add(noDefRem);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 10,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, esCls, itemCls},
                    0, new Object[0]));
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, esCls, "EMPTY", "L" + esCls + ";"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 10));
            il.add(storeRem);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 11,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, esCls, itemCls, esCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "putCachedItemRemainder",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "isEmpty", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, innerNext));
            il.add(new JumpInsnNode(Opcodes.GOTO, applyRem));
            // hasNbt: ItemStack is = st.getItemStack();
            il.add(hasNbt);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 9,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, esCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 8));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "getItemStack", "()L" + isCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 9));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "hasCraftingRemainingItem", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, innerNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "getCraftingRemainingItem", "()L" + isCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 11));
            il.add(new VarInsnNode(Opcodes.ALOAD, 11));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, isCls, "m_41619_", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, innerNext));
            il.add(new VarInsnNode(Opcodes.ALOAD, 11));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, esCls, "of", "(L" + isCls + ";)L" + esCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 10));
            // applyRem: if (ing instanceof TagEmiIngredient) { stacks = ((TagEmiIngredient) ing).sunlit$ensureOwnedStacks(); st = stacks.get(k); } st.setRemainder(rem);
            il.add(applyRem);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 11,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, esCls, "java/lang/Object", esCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, EMI_TAG_INGREDIENT_CLS));
            il.add(new JumpInsnNode(Opcodes.IFEQ, notTagApply));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, EMI_TAG_INGREDIENT_CLS));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EMI_TAG_INGREDIENT_CLS, "sunlit$ensureOwnedStacks", "()Ljava/util/List;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new VarInsnNode(Opcodes.ILOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 8));
            il.add(notTagApply);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 8));
            il.add(new VarInsnNode(Opcodes.ALOAD, 10));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "setRemainder", "(L" + esCls + ";)L" + esCls + ";", false));
            il.add(new InsnNode(Opcodes.POP));
            // innerNext:
            il.add(innerNext);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 8,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER, ingCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(7, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, innerCond));
            // outerNext:
            il.add(outerNext);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {"java/util/List", crCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(3, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, outerCond));
            // outerEnd: return true;
            il.add(outerEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(fast);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches {@code EmiAnvilEnchantRecipe.getBook()} by {@code (Enchantment, level)} (~109MB
     * {@code CompoundTag} and {@code ItemStack} churn eliminated across anvil enchant recipes).
     */
    public static byte[] transformEmiAnvilEnchantRecipe(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        final String enchCls = "net/minecraft/world/item/enchantment/Enchantment";
        for (MethodNode mn : cn.methods) {
            if (!"getBook".equals(mn.name)
                    || !("()L" + esCls + ";").equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            // Before every ARETURN, store into BootJarPayload.putCachedEnchantedBook(this.enchantment, this.level, result)
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    InsnList put = new InsnList();
                    put.add(new InsnNode(Opcodes.DUP));
                    put.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    put.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ANVIL_ENCHANT_CLS, "enchantment", "L" + enchCls + ";"));
                    put.add(new InsnNode(Opcodes.SWAP));
                    put.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    put.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ANVIL_ENCHANT_CLS, "level", "I"));
                    put.add(new InsnNode(Opcodes.SWAP));
                    put.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, PAYLOAD_CLS, "putCachedEnchantedBook",
                            "(Ljava/lang/Object;ILjava/lang/Object;)V", false));
                    mn.instructions.insertBefore(insn, put);
                }
            }
            // Prepend cache check
            LabelNode miss = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ANVIL_ENCHANT_CLS, "enchantment", "L" + enchCls + ";"));
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new FieldInsnNode(Opcodes.GETFIELD, EMI_ANVIL_ENCHANT_CLS, "level", "I"));
            pre.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getCachedEnchantedBook",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false));
            pre.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
            pre.add(new InsnNode(Opcodes.DUP));
            pre.add(new JumpInsnNode(Opcodes.IFNULL, miss));
            pre.add(new InsnNode(Opcodes.ARETURN));
            pre.add(miss);
            pre.add(new FrameNode(
                    Opcodes.F_NEW, 1, new Object[] {EMI_ANVIL_ENCHANT_CLS},
                    1, new Object[] {esCls}));
            pre.add(new InsnNode(Opcodes.POP));
            mn.instructions.insert(pre);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Optimizes Iris {@code IncludeProcessor.process(AbsolutePackPath)}:
     * <ol>
     *   <li>Returns {@code node.getLines()} directly when {@code node.getIncludes().isEmpty()} (leaf shaders).</li>
     *   <li>Pre-computes exact output size and uses {@code ImmutableList.builderWithExpectedSize(total)}
     *       to eliminate ~196MB of {@code ImmutableList$Builder.getReadyToExpandTo} array copies.</li>
     * </ol>
     */
    public static byte[] transformIrisIncludeProcessor(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String pathCls = IRIS_ABS_PACK_PATH_CLS;
        final String graphCls = "net/irisshaders/iris/shaderpack/include/IncludeGraph";
        final String nodeCls = "net/irisshaders/iris/shaderpack/include/FileNode";
        final String ilCls = "com/google/common/collect/ImmutableList";
        final String ilbCls = "com/google/common/collect/ImmutableList$Builder";
        final String imCls = "com/google/common/collect/ImmutableMap";
        for (MethodNode mn : cn.methods) {
            if (!"process".equals(mn.name)
                    || !("(L" + pathCls + ";)L" + ilCls + ";").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode notNull = new LabelNode();
            LabelNode hasIncludes = new LabelNode();
            LabelNode sumCond = new LabelNode();
            LabelNode sumEnd = new LabelNode();
            LabelNode buildCond = new LabelNode();
            LabelNode noIncAtLine = new LabelNode();
            LabelNode nextLine = new LabelNode();
            LabelNode buildEnd = new LabelNode();
            // FileNode node = this.graph.getNodes().get(path); if (node == null) return null;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, IRIS_INCLUDE_PROC_CLS, "graph", "L" + graphCls + ";"));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, graphCls, "getNodes", "()L" + imCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, imCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, nodeCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new InsnNode(Opcodes.ARETURN));
            // notNull: lines = node.getLines(); includes = node.getIncludes(); if (includes.isEmpty()) return lines;
            il.add(notNull);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {IRIS_INCLUDE_PROC_CLS, pathCls, nodeCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, nodeCls, "getLines", "()L" + ilCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, nodeCls, "getIncludes", "()L" + imCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 4));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, imCls, "isEmpty", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, hasIncludes));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            // hasIncludes: int total = lines.size(); for (AbsolutePackPath p : includes.values()) total += getIncludedFile(p).size() - 1;
            il.add(hasIncludes);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {IRIS_INCLUDE_PROC_CLS, pathCls, nodeCls, ilCls, imCls},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilCls, "size", "()I", false));
            il.add(new VarInsnNode(Opcodes.ISTORE, 5)); // lineCount
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6)); // total
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, imCls, "values", "()Lcom/google/common/collect/ImmutableCollection;", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/google/common/collect/ImmutableCollection", "iterator", "()Lcom/google/common/collect/UnmodifiableIterator;", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 7));
            il.add(sumCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 8,
                    new Object[] {IRIS_INCLUDE_PROC_CLS, pathCls, nodeCls, ilCls, imCls, Opcodes.INTEGER, Opcodes.INTEGER, "java/util/Iterator"},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
            il.add(new JumpInsnNode(Opcodes.IFEQ, sumEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, pathCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, IRIS_INCLUDE_PROC_CLS, "getIncludedFile", "(L" + pathCls + ";)L" + ilCls + ";", false));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, ilCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilCls, "size", "()I", false));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.ISUB));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new VarInsnNode(Opcodes.ISTORE, 6));
            il.add(new JumpInsnNode(Opcodes.GOTO, sumCond));
            // sumEnd: ImmutableList.Builder builder = ImmutableList.builderWithExpectedSize(total);
            il.add(sumEnd);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 7,
                    new Object[] {IRIS_INCLUDE_PROC_CLS, pathCls, nodeCls, ilCls, imCls, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 6));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ilCls, "builderWithExpectedSize", "(I)L" + ilbCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 7));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 8));
            il.add(buildCond);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 9,
                    new Object[] {IRIS_INCLUDE_PROC_CLS, pathCls, nodeCls, ilCls, imCls, Opcodes.INTEGER, Opcodes.INTEGER, ilbCls, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 8));
            il.add(new VarInsnNode(Opcodes.ILOAD, 5));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, buildEnd));
            il.add(new VarInsnNode(Opcodes.ALOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 8));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, imCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, pathCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 9));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new JumpInsnNode(Opcodes.IFNULL, noIncAtLine));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 9));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, IRIS_INCLUDE_PROC_CLS, "getIncludedFile", "(L" + pathCls + ";)L" + ilCls + ";", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilbCls, "addAll", "(Ljava/lang/Iterable;)L" + ilbCls + ";", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new JumpInsnNode(Opcodes.GOTO, nextLine));
            il.add(noIncAtLine);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 8));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilCls, "get", "(I)Ljava/lang/Object;", false));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilbCls, "add", "(Ljava/lang/Object;)L" + ilbCls + ";", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(nextLine);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new IincInsnNode(8, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, buildCond));
            il.add(buildEnd);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new VarInsnNode(Opcodes.ALOAD, 7));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ilbCls, "build", "()L" + ilCls + ";", false));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches immutable {@code ResourceLocation.toString()} in a lazy field (~160MB ALL / ~79MB GP
     * {@code StringConcatHelper} churn eliminated).
     */
    public static byte[] transformResourceLocation(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"toString".equals(mn.name)
                    || !"()Ljava/lang/String;".equals(mn.desc)
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    InsnList cache = new InsnList();
                    cache.add(new InsnNode(Opcodes.DUP));
                    cache.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    cache.add(new InsnNode(Opcodes.SWAP));
                    cache.add(new FieldInsnNode(Opcodes.PUTFIELD, MC_RESOURCE_LOCATION_CLS, "sunlit$cachedStr", "Ljava/lang/String;"));
                    mn.instructions.insertBefore(insn, cache);
                }
            }
            LabelNode miss = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new FieldInsnNode(Opcodes.GETFIELD, MC_RESOURCE_LOCATION_CLS, "sunlit$cachedStr", "Ljava/lang/String;"));
            pre.add(new InsnNode(Opcodes.DUP));
            pre.add(new JumpInsnNode(Opcodes.IFNULL, miss));
            pre.add(new InsnNode(Opcodes.ARETURN));
            pre.add(miss);
            pre.add(new FrameNode(
                    Opcodes.F_NEW, 1, new Object[] {MC_RESOURCE_LOCATION_CLS},
                    1, new Object[] {"java/lang/String"}));
            pre.add(new InsnNode(Opcodes.POP));
            mn.instructions.insert(pre);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlit$cachedStr", "Ljava/lang/String;", null, null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code stack.getItemStack().getItem() instanceof HorseArmorItem} in REMI's
     * {@code AnimalArmorItemGroup.match} with {@code stack.getKey() instanceof HorseArmorItem},
     * eliminating ~51MB of temporary {@code ItemStack} allocations during EMI stack group reload.
     */
    public static byte[] transformRemiAnimalArmorGroup(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String ingCls = "dev/emi/emi/api/stack/EmiIngredient";
        final String esCls = "dev/emi/emi/api/stack/EmiStack";
        for (MethodNode mn : cn.methods) {
            if (!"match".equals(mn.name)
                    || !("(L" + ingCls + ";)Z").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode retFalse = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, esCls));
            il.add(new JumpInsnNode(Opcodes.IFEQ, retFalse));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, esCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, esCls, "getKey", "()Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, "net/minecraft/world/item/HorseArmorItem"));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(retFalse);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IRETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches Iris {@code OuterWrappedRenderType.wrapExactlyOnce(name, wrapped, extra)} in a bounded
     * 256-slot lock-free direct-mapped cache (~35MB per 10s Render-thread garbage eliminated).
     */
    public static byte[] transformIrisOuterWrappedRenderType(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String rtCls = "net/minecraft/client/renderer/RenderType";
        final String shardCls = "net/minecraft/client/renderer/RenderStateShard";
        final String owCls = IRIS_OUTER_WRAPPED_RT_CLS;
        for (MethodNode mn : cn.methods) {
            if (!"wrapExactlyOnce".equals(mn.name)
                    || !("(Ljava/lang/String;L" + rtCls + ";L" + shardCls + ";)L" + owCls + ";").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode unwrapped = new LabelNode();
            LabelNode miss = new LabelNode();
            // if (wrapped instanceof OuterWrappedRenderType) wrapped = ((OuterWrappedRenderType) wrapped).unwrap();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.INSTANCEOF, owCls));
            il.add(new JumpInsnNode(Opcodes.IFEQ, unwrapped));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, owCls));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owCls, "unwrap", "()L" + rtCls + ";", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(unwrapped);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {"java/lang/String", rtCls, shardCls},
                    0, new Object[0]));
            // Object hit = BootJarPayload.getCachedWrappedRenderType(name, wrapped, extra);
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "getCachedWrappedRenderType",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, owCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            // miss: res = new OuterWrappedRenderType(name, wrapped, extra); putCached...; return res;
            il.add(miss);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {"java/lang/String", rtCls, shardCls},
                    0, new Object[0]));
            il.add(new TypeInsnNode(Opcodes.NEW, owCls));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, owCls, "<init>",
                    "(Ljava/lang/String;L" + rtCls + ";L" + shardCls + ";)V", false));
            il.add(new VarInsnNode(Opcodes.ASTORE, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 2));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "putCachedWrappedRenderType",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 3));
            il.add(new InsnNode(Opcodes.ARETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pre-sizes the tooltip {@code ArrayList} in {@code ItemStack.m_41651_} ({@code getTooltipLines})
     * from default capacity 0/10 to 16 (~37MB {@code ArrayList.grow} churn eliminated during EMI search).
     */
    public static byte[] transformItemStackTooltips(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"m_41651_".equals(mn.name)
                    || !"(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESTATIC
                        && "com/google/common/collect/Lists".equals(min.owner)
                        && "newArrayList".equals(min.name)
                        && "()Ljava/util/ArrayList;".equals(min.desc)) {
                    InsnList rep = new InsnList();
                    rep.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
                    rep.add(new InsnNode(Opcodes.DUP));
                    rep.add(new IntInsnNode(Opcodes.BIPUSH, 16));
                    rep.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false));
                    mn.instructions.insertBefore(min, rep);
                    mn.instructions.remove(min);
                    patched = true;
                    break;
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Short-circuits OBE's {@code @WrapOperation} hooks on Embeddium {@code ChunkBuilderMeshingTask}
     * for non-BlockEntity blocks (~99.9% of all chunk blocks):
     * <ul>
     *   <li>{@code obe$getRenderShape*}: returns {@code state.m_60799_()} directly when {@code !state.m_155947_()}.</li>
     *   <li>{@code obe$wrapRenderModel}: calls {@code renderer.renderModel(ctx, buffers)} directly when
     *       {@code beRef.get() == null}, skipping {@code SectionMeshingUtil.getCorrectedModel},
     *       {@code new BlockPos}, {@code ctx.update}, and varargs {@code Object[3]} allocation.</li>
     * </ul>
     */
    public static byte[] transformSodiumChunkBuilderMeshingTask(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        final String bsCls = "net/minecraft/world/level/block/state/BlockState";
        final String rsCls = "net/minecraft/world/level/block/RenderShape";
        final String opCls = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
        final String lrCls = "com/llamalad7/mixinextras/sugar/ref/LocalRef";
        final String wsCls = "me/jellysquid/mods/sodium/client/world/WorldSlice";
        final String mbpCls = "net/minecraft/core/BlockPos$MutableBlockPos";
        final String brCls = "me/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer";
        final String brcCls = "me/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext";
        final String cbbCls = "me/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers";
        final String shapeDesc = "(L" + bsCls + ";L" + opCls + ";L" + lrCls + ";L" + wsCls + ";L" + mbpCls + ";)L" + rsCls + ";";
        final String modelDesc = "(L" + brCls + ";L" + brcCls + ";L" + cbbCls + ";L" + opCls + ";L" + lrCls + ";)V";

        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) {
                continue;
            }
            if (mn.name.contains("obe$getRenderShape")
                    && !mn.name.contains("$mixinextras$bridge")
                    && mn.desc.startsWith("(L" + bsCls + ";")
                    && mn.desc.endsWith(")L" + rsCls + ";")) {
                Type[] argTypes = Type.getArgumentTypes(mn.desc);
                Object[] frameLocals = new Object[1 + argTypes.length];
                frameLocals[0] = cn.name;
                for (int i = 0; i < argTypes.length; i++) {
                    frameLocals[1 + i] = asmFrameType(argTypes[i]);
                }
                LabelNode orig = new LabelNode();
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, bsCls, "m_155947_", "()Z", false));
                pre.add(new JumpInsnNode(Opcodes.IFNE, orig));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, bsCls, "m_60799_", "()L" + rsCls + ";", false));
                pre.add(new InsnNode(Opcodes.ARETURN));
                pre.add(orig);
                pre.add(new FrameNode(Opcodes.F_NEW, frameLocals.length, frameLocals, 0, new Object[0]));
                mn.instructions.insert(pre);
                patched = true;
            } else if (mn.name.contains("obe$wrapRenderModel")
                    && !mn.name.contains("$mixinextras$bridge")
                    && modelDesc.equals(mn.desc)) {
                LabelNode orig = new LabelNode();
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 5));
                pre.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, lrCls, "get", "()Ljava/lang/Object;", true));
                pre.add(new JumpInsnNode(Opcodes.IFNONNULL, orig));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 3));
                pre.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, brCls, "renderModel",
                        "(L" + brcCls + ";L" + cbbCls + ";)V", false));
                pre.add(new InsnNode(Opcodes.RETURN));
                pre.add(orig);
                pre.add(new FrameNode(
                        Opcodes.F_NEW, 6,
                        new Object[] {cn.name, brCls, brcCls, cbbCls, opCls, lrCls},
                        0, new Object[0]));
                mn.instructions.insert(pre);
                patched = true;
            } else if (mn.name.contains("oculus$overrideRenderTypes")) {
                if (patchIrisBlockRenderTypeLookup(mn)) {
                    patched = true;
                }
            }
        }
        // Pool MixinExtras LocalRefImpl instances and fast-skip vanilla air blocks inside execute(...)
        final String localRefImplCls = "com/llamalad7/mixinextras/sugar/impl/ref/generated/LocalRefImpl";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        int localRefSiteCount = 0;
        for (MethodNode mn : cn.methods) {
            if (!"execute".equals(mn.name)
                    || !"(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"
                            .equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            // 1. Replace every `NEW LocalRefImpl; DUP; INVOKESPECIAL <init>()V` with a per-site cached field getter
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && localRefImplCls.equals(tin.desc)) {
                    AbstractInsnNode n1 = nextRealInsn(tin);
                    AbstractInsnNode n2 = nextRealInsn(n1);
                    if (n1 != null && n1.getOpcode() == Opcodes.DUP
                            && n2 instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && localRefImplCls.equals(min.owner)
                            && "<init>".equals(min.name)
                            && "()V".equals(min.desc)) {
                        String fieldName = "sunlit$lr" + localRefSiteCount;
                        String getterName = "sunlit$getLr" + localRefSiteCount;
                        localRefSiteCount++;
                        InsnList rep = new InsnList();
                        rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        rep.add(new MethodInsnNode(
                                Opcodes.INVOKESPECIAL, cn.name, getterName,
                                "()L" + localRefImplCls + ";", false));
                        mn.instructions.insertBefore(tin, rep);
                        mn.instructions.remove(tin);
                        mn.instructions.remove(n1);
                        mn.instructions.remove(n2);
                        cn.fields.add(new FieldNode(
                                Opcodes.ACC_PRIVATE, fieldName, "L" + localRefImplCls + ";", null, null));
                        MethodNode getter = new MethodNode(
                                Opcodes.ACC_PRIVATE, getterName, "()L" + localRefImplCls + ";", null, null);
                        InsnList gil = getter.instructions;
                        LabelNode ready = new LabelNode();
                        gil.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        gil.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, fieldName, "L" + localRefImplCls + ";"));
                        gil.add(new VarInsnNode(Opcodes.ASTORE, 1));
                        gil.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        gil.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
                        gil.add(new TypeInsnNode(Opcodes.NEW, localRefImplCls));
                        gil.add(new InsnNode(Opcodes.DUP));
                        gil.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, localRefImplCls, "<init>", "()V", false));
                        gil.add(new VarInsnNode(Opcodes.ASTORE, 1));
                        gil.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        gil.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        gil.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, fieldName, "L" + localRefImplCls + ";"));
                        gil.add(ready);
                        gil.add(new FrameNode(
                                Opcodes.F_NEW, 2,
                                new Object[] {cn.name, localRefImplCls},
                                0, new Object[0]));
                        gil.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        gil.add(new InsnNode(Opcodes.ARETURN));
                        cn.methods.add(getter);
                        patched = true;
                    }
                }
            }
            // 2. Fast-skip vanilla AIR / CAVE_AIR / VOID_AIR right after WorldSlice.getBlockState -> ASTORE stateVar
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode getBs
                        && getBs.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && wsCls.equals(getBs.owner)
                        && "getBlockState".equals(getBs.name)
                        && ("(III)L" + bsCls + ";").equals(getBs.desc)) {
                    AbstractInsnNode storeNode = nextRealInsn(getBs);
                    if (storeNode instanceof VarInsnNode stBs && stBs.getOpcode() == Opcodes.ASTORE) {
                        int bsSlot = stBs.var;
                        // Locate the air-skip GOTO following the blockState.m_60795_() check (within 40 real insns)
                        LabelNode continueLabel = null;
                        boolean sawIsAir = false;
                        boolean sawHasBe = false;
                        AbstractInsnNode cur = nextRealInsn(storeNode);
                        for (int steps = 0; cur != null && steps < 45; steps++, cur = nextRealInsn(cur)) {
                            if (cur instanceof MethodInsnNode m) {
                                if (bsCls.equals(m.owner) && "m_60795_".equals(m.name) && "()Z".equals(m.desc)) {
                                    sawIsAir = true;
                                } else if (sawIsAir && bsCls.equals(m.owner) && "m_155947_".equals(m.name) && "()Z".equals(m.desc)) {
                                    sawHasBe = true;
                                }
                            } else if (sawHasBe && cur instanceof JumpInsnNode jmp && jmp.getOpcode() == Opcodes.GOTO) {
                                continueLabel = jmp.label;
                                break;
                            }
                        }
                        if (continueLabel != null) {
                            FrameNode contFrame = null;
                            for (AbstractInsnNode fnScan = continueLabel.getNext(); fnScan != null; fnScan = fnScan.getNext()) {
                                if (fnScan instanceof FrameNode fn) {
                                    contFrame = fn;
                                    break;
                                }
                                if (fnScan.getOpcode() >= 0) {
                                    break;
                                }
                            }
                            InsnList airSkip = new InsnList();
                            if (contFrame != null && contFrame.local != null) {
                                for (int k = bsSlot + 1; k < contFrame.local.size(); k++) {
                                    Object locType = contFrame.local.get(k);
                                    if ("java/lang/Object".equals(locType)) {
                                        airSkip.add(new InsnNode(Opcodes.ACONST_NULL));
                                        airSkip.add(new VarInsnNode(Opcodes.ASTORE, k));
                                    }
                                }
                            }
                            airSkip.add(new VarInsnNode(Opcodes.ALOAD, bsSlot));
                            airSkip.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, perfCls, "isVanillaAir", "(L" + bsCls + ";)Z", false));
                            airSkip.add(new JumpInsnNode(Opcodes.IFNE, continueLabel));
                            mn.instructions.insert(storeNode, airSkip);
                            patched = true;
                        }
                    }
                    break;
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code ForgeRegistries.BLOCKS.getDelegateOrThrow(state.m_60734_()) -> map.get(holder)}
     * inside Iris's {@code oculus$overrideRenderTypes} and {@code iris$setCustomRenderType} with
     * an identity-cached {@code Block -> ChunkRenderTypeSet} lookup in {@code EmfRenderPerf}.
     */
    private static boolean patchIrisBlockRenderTypeLookup(MethodNode mn) {
        final String bsCls = "net/minecraft/world/level/block/state/BlockState";
        final String blkCls = "net/minecraft/world/level/block/Block";
        final String regCls = "net/minecraftforge/registries/ForgeRegistries";
        final String iregCls = "net/minecraftforge/registries/IForgeRegistry";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof FieldInsnNode getBlocks
                    && getBlocks.getOpcode() == Opcodes.GETSTATIC
                    && regCls.equals(getBlocks.owner)
                    && "BLOCKS".equals(getBlocks.name)) {
                AbstractInsnNode n1 = nextRealInsn(getBlocks);
                AbstractInsnNode n2 = nextRealInsn(n1);
                AbstractInsnNode n3 = nextRealInsn(n2);
                AbstractInsnNode n4 = nextRealInsn(n3);
                if (n1 instanceof VarInsnNode loadBs && loadBs.getOpcode() == Opcodes.ALOAD
                        && n2 instanceof MethodInsnNode getBlock
                        && getBlock.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && bsCls.equals(getBlock.owner)
                        && "m_60734_".equals(getBlock.name)
                        && ("()L" + blkCls + ";").equals(getBlock.desc)
                        && n3 instanceof MethodInsnNode getDel
                        && getDel.getOpcode() == Opcodes.INVOKEINTERFACE
                        && iregCls.equals(getDel.owner)
                        && "getDelegateOrThrow".equals(getDel.name)
                        && n4 instanceof MethodInsnNode mapGet
                        && mapGet.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/Map".equals(mapGet.owner)
                        && "get".equals(mapGet.name)
                        && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(mapGet.desc)) {
                    mn.instructions.remove(getBlocks);
                    mn.instructions.remove(getDel);
                    mn.instructions.set(mapGet, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, perfCls, "getIrisBlockRenderTypeOverride",
                            "(Ljava/util/Map;L" + blkCls + ";)Ljava/lang/Object;", false));
                    patched = true;
                }
            }
        }
        return patched;
    }

    /**
     * Enables Embeddium pose-stack matrix caching around {@code SodiumWorldRenderer.renderBlockEntity}
     * (main & shadow passes), eliminating ~250MB of per-frame {@code Matrix4f}/{@code Matrix3f}/{@code Pose}
     * churn across all visible block entities.
     */
    public static byte[] transformSodiumWorldRenderer(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String psCls = "com/mojang/blaze3d/vertex/PoseStack";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"renderBlockEntity".equals(mn.name)
                    || mn.desc == null
                    || !mn.desc.startsWith("(L" + psCls + ";")
                    || (mn.access & Opcodes.ACC_STATIC) == 0
                    || mn.instructions == null) {
                continue;
            }
            MethodInsnNode pushInsn = null;
            MethodInsnNode popInsn = null;
            int pushCount = 0;
            int popCount = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && psCls.equals(min.owner)
                        && "()V".equals(min.desc)) {
                    if ("m_85836_".equals(min.name)) {
                        pushInsn = min;
                        pushCount++;
                    } else if ("m_85849_".equals(min.name)) {
                        popInsn = min;
                        popCount++;
                    }
                }
            }
            if (pushCount != 1 || popCount != 1 || pushInsn == null || popInsn == null) {
                continue;
            }
            MethodInsnNode newPush = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "pushWithPoseCaching", "(L" + psCls + ";)V", false);
            MethodInsnNode newPop = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "popWithPoseCaching", "(L" + psCls + ";)V", false);
            mn.instructions.set(pushInsn, newPush);
            mn.instructions.set(popInsn, newPop);

            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode catchHandler = new LabelNode();
            mn.instructions.insert(newPush, tryStart);
            AbstractInsnNode beforePop = prevRealInsn(newPop);
            AbstractInsnNode tryEndAnchor = (beforePop instanceof VarInsnNode vin
                    && vin.getOpcode() == Opcodes.ALOAD && vin.var == 0) ? beforePop : newPop;
            mn.instructions.insertBefore(tryEndAnchor, tryEnd);

            Type[] argTypes = Type.getArgumentTypes(mn.desc);
            Object[] frameLocals = new Object[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                frameLocals[i] = asmFrameType(argTypes[i]);
            }
            InsnList handler = new InsnList();
            handler.add(catchHandler);
            handler.add(new FrameNode(
                    Opcodes.F_NEW,
                    frameLocals.length,
                    frameLocals,
                    1,
                    new Object[] {"java/lang/Throwable"}));
            handler.add(new VarInsnNode(Opcodes.ALOAD, 0));
            handler.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "disablePoseCaching", "(L" + psCls + ";)V", false));
            handler.add(new InsnNode(Opcodes.ATHROW));
            mn.instructions.add(handler);
            if (mn.tryCatchBlocks == null) {
                mn.tryCatchBlocks = new java.util.ArrayList<>();
            }
            mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, null));
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Redirects Emberbium {@code RadixSort.radixSortDescending(float[])} to
     * {@code EmfRenderPerf.radixSortDescending(float[])}, pooling internal temporary arrays on
     * the render thread to eliminate ~135MB+ of translucent-sort {@code int[]} GC churn.
     */
    public static byte[] transformSodiumRadixSort(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("radixSortDescending".equals(mn.name) && "([F)[I".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "radixSortDescending", "([F)[I", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Reuses a per-instance {@code PoseStack.Pose} in Indigo {@code AbstractRenderContext.bufferQuad}
     * instead of allocating {@code new PoseStack()} (ArrayDeque + Matrix4f + Matrix3f + Pose) on
     * every buffered quad (~38MB+ saved on chunk builder threads).
     */
    public static byte[] transformIndigoAbstractRenderContext(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String psCls = "com/mojang/blaze3d/vertex/PoseStack";
        final String poseCls = "com/mojang/blaze3d/vertex/PoseStack$Pose";
        for (MethodNode mn : cn.methods) {
            if (!"bufferQuad".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && psCls.equals(tin.desc)) {
                    AbstractInsnNode n1 = nextRealInsn(tin);
                    AbstractInsnNode n2 = nextRealInsn(n1);
                    AbstractInsnNode n3 = nextRealInsn(n2);
                    if (n1 != null && n1.getOpcode() == Opcodes.DUP
                            && n2 instanceof MethodInsnNode mInit
                            && mInit.getOpcode() == Opcodes.INVOKESPECIAL
                            && psCls.equals(mInit.owner) && "<init>".equals(mInit.name) && "()V".equals(mInit.desc)
                            && n3 instanceof MethodInsnNode mLast
                            && mLast.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && psCls.equals(mLast.owner) && "m_85850_".equals(mLast.name)
                            && ("()L" + poseCls + ";").equals(mLast.desc)) {
                        InsnList call = new InsnList();
                        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        call.add(new MethodInsnNode(
                                Opcodes.INVOKEVIRTUAL, INDIGO_ABSTRACT_RENDER_CTX_CLS,
                                "sunlit$getReusedPose", "()L" + poseCls + ";", false));
                        mn.instructions.insertBefore(tin, call);
                        mn.instructions.remove(tin);
                        mn.instructions.remove(n1);
                        mn.instructions.remove(n2);
                        mn.instructions.remove(n3);
                        patched = true;
                        break;
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlit$reusedPose", "L" + poseCls + ";", null, null));
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "sunlit$getReusedPose",
                "()L" + poseCls + ";",
                null,
                null);
        InsnList il = helper.instructions;
        LabelNode ready = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, INDIGO_ABSTRACT_RENDER_CTX_CLS, "sunlit$reusedPose", "L" + poseCls + ";"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, ready));
        il.add(new TypeInsnNode(Opcodes.NEW, psCls));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, psCls, "<init>", "()V", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, psCls, "m_85850_", "()L" + poseCls + ";", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, INDIGO_ABSTRACT_RENDER_CTX_CLS, "sunlit$reusedPose", "L" + poseCls + ";"));
        il.add(ready);
        il.add(new FrameNode(
                Opcodes.F_FULL, 2,
                new Object[] {INDIGO_ABSTRACT_RENDER_CTX_CLS, poseCls},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(helper);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces the 512x512 per-pixel {@code BufferedImage.getRGB(x, y)} loop in FTB Chunks
     * {@code MapRegionData.read()} with bulk scanline reads via {@link BootJarPayload#readFtbRegionImages}
     * (~120MB {@code ByteInterleavedRaster} churn eliminated).
     */
    public static byte[] transformFtbMapRegionData(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"read".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof IntInsnNode iin
                        && iin.getOpcode() == Opcodes.SIPUSH
                        && iin.operand == 512) {
                    AbstractInsnNode prev = prevRealInsn(iin);
                    AbstractInsnNode nxt = nextRealInsn(iin);
                    if (prev instanceof VarInsnNode loadY && loadY.getOpcode() == Opcodes.ILOAD && loadY.var == 11
                            && nxt instanceof JumpInsnNode jmpExit && jmpExit.getOpcode() == Opcodes.IF_ICMPGE) {
                        // Walk back past loop label & frame to `iconst_0; istore 11`
                        AbstractInsnNode storeY = prevRealInsn(loadY);
                        AbstractInsnNode zero = prevRealInsn(storeY);
                        if (storeY instanceof VarInsnNode stY && stY.getOpcode() == Opcodes.ISTORE && stY.var == 11
                                && zero != null && zero.getOpcode() == Opcodes.ICONST_0) {
                            FrameNode entryFrame = null;
                            for (AbstractInsnNode scan = zero.getPrevious(); scan != null; scan = scan.getPrevious()) {
                                if (scan instanceof FrameNode fn) {
                                    entryFrame = fn;
                                    break;
                                }
                                if (scan.getOpcode() >= 0) {
                                    break;
                                }
                            }
                            if (entryFrame != null) {
                                LabelNode fallbackLbl = new LabelNode();
                                LabelNode exitLbl = jmpExit.label;
                                InsnList bulk = new InsnList();
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 5));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 6));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 7));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 8));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 9));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS, "height", "[S"));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS, "waterLightAndBiome", "[S"));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS, "foliage", "[I"));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS, "grass", "[I"));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS, "water", "[I"));
                                bulk.add(new VarInsnNode(Opcodes.ILOAD, 10));
                                bulk.add(new MethodInsnNode(
                                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "readFtbRegionImages",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[S[S[I[I[II)I",
                                        false));
                                bulk.add(new VarInsnNode(Opcodes.ISTORE, 11));
                                bulk.add(new VarInsnNode(Opcodes.ILOAD, 11));
                                bulk.add(new JumpInsnNode(Opcodes.IFLT, fallbackLbl));
                                bulk.add(new VarInsnNode(Opcodes.ILOAD, 11));
                                bulk.add(new JumpInsnNode(Opcodes.IFEQ, exitLbl));
                                bulk.add(new VarInsnNode(Opcodes.ALOAD, 0));
                                bulk.add(new FieldInsnNode(
                                        Opcodes.GETFIELD, FTB_MAP_REGION_DATA_CLS,
                                        "region", "Ldev/ftb/mods/ftbchunks/client/map/MapRegion;"));
                                bulk.add(new MethodInsnNode(
                                        Opcodes.INVOKEVIRTUAL, "dev/ftb/mods/ftbchunks/client/map/MapRegion",
                                        "markDirty", "()V", false));
                                bulk.add(new JumpInsnNode(Opcodes.GOTO, exitLbl));
                                bulk.add(fallbackLbl);
                                bulk.add(entryFrame.clone(null));
                                mn.instructions.insertBefore(zero, bulk);
                                patched = true;
                                break;
                            }
                        }
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pre-sizes {@code targetMap} value lists in REMI's {@code EmiStackGroup.lambda$new$0} from
     * default capacity 10 to 1 (most target IDs map to a single ingredient; ~55MB saved in GP).
     */
    public static byte[] transformRemiEmiStackGroup(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"lambda$new$0".equals(mn.name)
                    || !"(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "java/util/ArrayList".equals(min.owner)
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    mn.instructions.insertBefore(min, new InsnNode(Opcodes.ICONST_1));
                    min.desc = "(I)V";
                    patched = true;
                    break;
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces JourneyMap {@code RGB.multiply(int, int)} float[3] allocations (~28MB GP) with
     * zero-allocation bit-exact {@link BootJarPayload#fastRgbMultiply}.
     */
    public static byte[] transformJourneyMapRgb(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("multiply".equals(mn.name) && "(II)I".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ILOAD, 0));
                il.add(new VarInsnNode(Opcodes.ILOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "fastRgbMultiply", "(II)I", false));
                il.add(new InsnNode(Opcodes.IRETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches {@code EmiUtil.subId(ResourceLocation)} in a bounded 2048-slot cache (~41MB GP saved).
     */
    public static byte[] transformEmiUtil(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String rlCls = "net/minecraft/resources/ResourceLocation";
        for (MethodNode mn : cn.methods) {
            if ("subId".equals(mn.name)
                    && ("(L" + rlCls + ";)Ljava/lang/String;").equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135827_", "()Ljava/lang/String;", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, rlCls, "m_135815_", "()Ljava/lang/String;", false));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAYLOAD_CLS, "fastEmiSubId",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Lowers Embeddium/Sodium chunk builder worker thread priority to NORM_PRIORITY - 2 (3) so
     * intense post-join and camera-pan chunk meshing never starves the Render thread on Windows.
     */
    public static byte[] transformSodiumWorkerThread(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList prio = new InsnList();
                    prio.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prio.add(new InsnNode(Opcodes.ICONST_3));
                    prio.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "setPriority", "(I)V", false));
                    mn.instructions.insertBefore(insn, prio);
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Lowers JourneyMap background worker threads (JM-task) from NORM_PRIORITY (5) to priority 2
     * so map tile baking and I/O never compete with the Render thread during world join.
     */
    public static byte[] transformJourneyMapThreadFactory(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"newThread".equals(mn.name)
                    || !"(Ljava/lang/Runnable;)Ljava/lang/Thread;".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ICONST_5) {
                    mn.instructions.set(insn, new InsnNode(Opcodes.ICONST_2));
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Enables Embeddium's CachingPoseStack pooling across GeckoLib GeoRenderer.defaultRender calls
     * (with an exception handler to guarantee refcount balance), eliminating ~840MB of per-frame
     * Matrix4f/Matrix3f allocations across GeoEntityRenderer/GeoBlockRenderer/GeoItemRenderer.
     */
    public static byte[] transformGeckoLibGeoRenderer(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String psCls = "com/mojang/blaze3d/vertex/PoseStack";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        final String targetDesc = "(L" + psCls
                + ";Lsoftware/bernie/geckolib/core/animatable/GeoAnimatable;"
                + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                + "Lnet/minecraft/client/renderer/RenderType;"
                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;FFI)V";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"defaultRender".equals(mn.name) || !targetDesc.equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            MethodInsnNode pushInsn = null;
            MethodInsnNode popInsn = null;
            int pushCount = 0;
            int popCount = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && psCls.equals(min.owner)
                        && "()V".equals(min.desc)) {
                    if ("m_85836_".equals(min.name)) {
                        pushInsn = min;
                        pushCount++;
                    } else if ("m_85849_".equals(min.name)) {
                        popInsn = min;
                        popCount++;
                    }
                }
            }
            if (pushCount != 1 || popCount != 1 || pushInsn == null || popInsn == null) {
                continue;
            }
            MethodInsnNode newPush = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "pushWithPoseCaching", "(L" + psCls + ";)V", false);
            MethodInsnNode newPop = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "popWithPoseCaching", "(L" + psCls + ";)V", false);
            mn.instructions.set(pushInsn, newPush);
            mn.instructions.set(popInsn, newPop);

            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode catchHandler = new LabelNode();
            mn.instructions.insert(newPush, tryStart);
            // Place tryEnd right before the ALOAD 1 preceding newPop (or before newPop itself).
            AbstractInsnNode beforePop = prevRealInsn(newPop);
            AbstractInsnNode tryEndAnchor = (beforePop instanceof VarInsnNode vin
                    && vin.getOpcode() == Opcodes.ALOAD && vin.var == 1) ? beforePop : newPop;
            mn.instructions.insertBefore(tryEndAnchor, tryEnd);

            InsnList handler = new InsnList();
            handler.add(catchHandler);
            handler.add(new FrameNode(
                    Opcodes.F_NEW,
                    9,
                    new Object[] {
                        cn.name,
                        psCls,
                        "software/bernie/geckolib/core/animatable/GeoAnimatable",
                        "net/minecraft/client/renderer/MultiBufferSource",
                        "net/minecraft/client/renderer/RenderType",
                        "com/mojang/blaze3d/vertex/VertexConsumer",
                        Opcodes.FLOAT,
                        Opcodes.FLOAT,
                        Opcodes.INTEGER
                    },
                    1,
                    new Object[] {"java/lang/Throwable"}));
            handler.add(new VarInsnNode(Opcodes.ALOAD, 1));
            handler.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, perfCls, "disablePoseCaching", "(L" + psCls + ";)V", false));
            handler.add(new InsnNode(Opcodes.ATHROW));
            mn.instructions.add(handler);
            if (mn.tryCatchBlocks == null) {
                mn.tryCatchBlocks = new java.util.ArrayList<>();
            }
            mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, null));
            patched = true;
            break;
        }
        if (patchGbfGeoRendererIrisFastPath(cn)) {
            patched = true;
        }
        if (patchGeckoLibGeoRendererLoops(cn)) {
            patched = true;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code List.iterator()} loops in GeckoLib {@code GeoRenderer}'s hottest per-bone
     * methods ({@code applyRenderLayersForBone}, {@code applyRenderLayers},
     * {@code renderCubesOfBone}, {@code renderChildBones}) with indexed {@code size()/get(i)}
     * loops, eliminating ~128MB+ of {@code ObjectArrayList$1} iterator allocations.
     */
    private static boolean patchGeckoLibGeoRendererLoops(ClassNode cn) {
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            if (!"applyRenderLayersForBone".equals(mn.name)
                    && !"applyRenderLayers".equals(mn.name)
                    && !"renderCubesOfBone".equals(mn.name)
                    && !"renderChildBones".equals(mn.name)) {
                continue;
            }
            if (replaceSimpleListIteratorLoop(mn)) {
                patched = true;
            }
        }
        return patched;
    }

    private static boolean replaceSimpleListIteratorLoop(MethodNode mn) {
        MethodInsnNode iterCall = null;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof MethodInsnNode min
                    && min.getOpcode() == Opcodes.INVOKEINTERFACE
                    && "java/util/List".equals(min.owner)
                    && "iterator".equals(min.name)
                    && "()Ljava/util/Iterator;".equals(min.desc)) {
                if (iterCall != null) {
                    return false;
                }
                iterCall = min;
            }
        }
        if (iterCall == null) {
            return false;
        }
        AbstractInsnNode stInsn = nextRealInsn(iterCall);
        if (!(stInsn instanceof VarInsnNode stIter) || stIter.getOpcode() != Opcodes.ASTORE) {
            return false;
        }
        int listSlot = stIter.var;
        // Find loop LabelNode right after stIter
        LabelNode loopLabel = null;
        FrameNode loopFrame = null;
        AbstractInsnNode scan = stIter.getNext();
        while (scan != null && scan.getOpcode() < 0) {
            if (scan instanceof LabelNode ln && loopLabel == null) {
                loopLabel = ln;
            } else if (scan instanceof FrameNode fn && loopFrame == null) {
                loopFrame = fn;
            }
            scan = scan.getNext();
        }
        if (loopLabel == null || !(scan instanceof VarInsnNode ld1)
                || ld1.getOpcode() != Opcodes.ALOAD || ld1.var != listSlot) {
            return false;
        }
        AbstractInsnNode hasNextInsn = nextRealInsn(ld1);
        AbstractInsnNode ifeqInsn = nextRealInsn(hasNextInsn);
        AbstractInsnNode ld2Insn = nextRealInsn(ifeqInsn);
        AbstractInsnNode nextInsn = nextRealInsn(ld2Insn);
        if (!(hasNextInsn instanceof MethodInsnNode hn)
                || hn.getOpcode() != Opcodes.INVOKEINTERFACE
                || !"java/util/Iterator".equals(hn.owner)
                || !"hasNext".equals(hn.name)
                || !(ifeqInsn instanceof JumpInsnNode ifeq)
                || ifeq.getOpcode() != Opcodes.IFEQ
                || !(ld2Insn instanceof VarInsnNode ld2)
                || ld2.getOpcode() != Opcodes.ALOAD || ld2.var != listSlot
                || !(nextInsn instanceof MethodInsnNode nx)
                || nx.getOpcode() != Opcodes.INVOKEINTERFACE
                || !"java/util/Iterator".equals(nx.owner)
                || !"next".equals(nx.name)) {
            return false;
        }
        // Find the unique back-edge GOTO loopLabel
        JumpInsnNode backEdge = null;
        for (AbstractInsnNode insn = nextInsn.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode j && j.getOpcode() == Opcodes.GOTO && j.label == loopLabel) {
                if (backEdge != null) {
                    return false;
                }
                backEdge = j;
            }
        }
        if (backEdge == null) {
            return false;
        }
        // Ensure listSlot is not referenced anywhere else
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode v && v.var == listSlot && v != stIter && v != ld1 && v != ld2) {
                return false;
            }
        }
        int idxSlot = Math.max(mn.maxLocals, listSlot + 1);
        int sizeSlot = idxSlot + 1;
        // Verify no existing local uses idxSlot or sizeSlot
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode v && (v.var == idxSlot || v.var == sizeSlot)) {
                return false;
            }
        }

        // Replace `INVOKEINTERFACE List.iterator()` with:
        // DUP; INVOKEINTERFACE List.size()I; ISTORE sizeSlot; ICONST_0; ISTORE idxSlot;
        // (followed by existing ASTORE listSlot!)
        InsnList init = new InsnList();
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        init.add(new VarInsnNode(Opcodes.ISTORE, sizeSlot));
        init.add(new InsnNode(Opcodes.ICONST_0));
        init.add(new VarInsnNode(Opcodes.ISTORE, idxSlot));
        mn.instructions.insertBefore(iterCall, init);
        mn.instructions.remove(iterCall);

        // Replace `ALOAD listSlot; hasNext; IFEQ end` with `ILOAD idxSlot; ILOAD sizeSlot; IF_ICMPGE end`
        InsnList cond = new InsnList();
        cond.add(new VarInsnNode(Opcodes.ILOAD, idxSlot));
        cond.add(new VarInsnNode(Opcodes.ILOAD, sizeSlot));
        cond.add(new JumpInsnNode(Opcodes.IF_ICMPGE, ifeq.label));
        mn.instructions.insertBefore(ld1, cond);
        mn.instructions.remove(ld1);
        mn.instructions.remove(hasNextInsn);
        mn.instructions.remove(ifeqInsn);

        // Replace `ALOAD listSlot; next()` with `ALOAD listSlot; ILOAD idxSlot; INVOKEINTERFACE List.get(I)Object;`
        mn.instructions.insertBefore(nextInsn, new VarInsnNode(Opcodes.ILOAD, idxSlot));
        mn.instructions.set(nextInsn, new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));

        // Insert `IINC idxSlot, 1` right before backEdge GOTO
        mn.instructions.insertBefore(backEdge, new IincInsnNode(idxSlot, 1));

        // Update expanded F_NEW FrameNode at loopLabel
        if (loopFrame != null && loopFrame.local != null) {
            java.util.ArrayList<Object> newLocals = new java.util.ArrayList<>(loopFrame.local);
            while (newLocals.size() <= sizeSlot) {
                newLocals.add(Opcodes.TOP);
            }
            newLocals.set(listSlot, "java/util/List");
            newLocals.set(idxSlot, Opcodes.INTEGER);
            newLocals.set(sizeSlot, Opcodes.INTEGER);
            loopFrame.local = newLocals;
        }
        // Also update any other FrameNode in the method that mentions java/util/Iterator at listSlot
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode fn && fn != loopFrame && fn.local != null && fn.local.size() > listSlot) {
                if ("java/util/Iterator".equals(fn.local.get(listSlot))) {
                    java.util.ArrayList<Object> nl = new java.util.ArrayList<>(fn.local);
                    nl.set(listSlot, "java/util/List");
                    fn.local = nl;
                }
            }
        }
        if (mn.localVariables != null) {
            mn.localVariables.clear();
        }
        return true;
    }

    /**
     * Fixes GBF 1.0.2's broken Iris entity vertex format check in merged {@code GeoRenderer.renderCube}:
     * GBF compared {@code BufferBuilder.format} (VertexFormat) against {@code IrisEntityVertex.FORMAT}
     * (VertexFormatDescription), which was always false and forced every GeckoLib entity under shaders
     * onto the slow per-vertex {@code BufferBuilder.vertex -> iris$finishExtendedVertex} path.
     * Also adds Iris format dispatch for {@code SodiumBufferBuilder}.
     */
    private static boolean patchGbfGeoRendererIrisFastPath(ClassNode cn) {
        final String renderCubeDesc = "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lsoftware/bernie/geckolib/cache/object/GeoCube;"
                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
        final String bbCls = "com/mojang/blaze3d/vertex/BufferBuilder";
        final String vfDesc = "Lcom/mojang/blaze3d/vertex/VertexFormat;";
        final String irisVfCls = "net/irisshaders/iris/vertices/IrisVertexFormats";
        final String irisCompatCls = "com/moepus/gbf/IrisCompat";
        final String irisCubeCls = "com/moepus/gbf/renderer/IrisCubeRenderer";
        final String sbbCls = "me/jellysquid/mods/sodium/client/render/vertex/buffer/SodiumBufferBuilder";
        final String cubeRenderDesc = "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lsoftware/bernie/geckolib/cache/object/GeoCube;"
                + "Lnet/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter;IIFFFF)V";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"renderCube".equals(mn.name) || !renderCubeDesc.equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            // Fix 1: BufferBuilder.f_85658_ == IrisEntityVertex.FORMAT -> IrisCompat.IS_IRIS_INSTALLED && == IrisVertexFormats.ENTITY
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.GETSTATIC
                        && "com/moepus/gbf/renderer/IrisEntityVertex".equals(fin.owner)
                        && "FORMAT".equals(fin.name)) {
                    AbstractInsnNode prev1 = prevRealInsn(fin);
                    AbstractInsnNode prev2 = prevRealInsn(prev1);
                    AbstractInsnNode next1 = nextRealInsn(fin);
                    if (prev1 instanceof FieldInsnNode getFmt
                            && getFmt.getOpcode() == Opcodes.GETFIELD
                            && bbCls.equals(getFmt.owner)
                            && "f_85658_".equals(getFmt.name)
                            && vfDesc.equals(getFmt.desc)
                            && prev2 instanceof VarInsnNode loadBb
                            && loadBb.getOpcode() == Opcodes.ALOAD
                            && next1 instanceof JumpInsnNode jmp
                            && jmp.getOpcode() == Opcodes.IF_ACMPNE) {
                        InsnList guard = new InsnList();
                        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, irisCompatCls, "IS_IRIS_INSTALLED", "Z"));
                        guard.add(new JumpInsnNode(Opcodes.IFEQ, jmp.label));
                        mn.instructions.insertBefore(loadBb, guard);
                        fin.owner = irisVfCls;
                        fin.name = "ENTITY";
                        fin.desc = vfDesc;
                        patched = true;
                    }
                }
            }
            // Fix 2: SodiumBufferBuilder branch also dispatches IrisVertexFormats.ENTITY -> IrisCubeRenderer
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode getOrig
                        && getOrig.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && sbbCls.equals(getOrig.owner)
                        && "getOriginalBufferBuilder".equals(getOrig.name)
                        && ("()L" + bbCls + ";").equals(getOrig.desc)) {
                    AbstractInsnNode loadSbb = prevRealInsn(getOrig);
                    AbstractInsnNode getFmt = nextRealInsn(getOrig);
                    AbstractInsnNode getDvf = nextRealInsn(getFmt);
                    AbstractInsnNode jmp = nextRealInsn(getDvf);
                    if (loadSbb instanceof VarInsnNode sbbVar
                            && sbbVar.getOpcode() == Opcodes.ALOAD
                            && getFmt instanceof FieldInsnNode fmtInsn
                            && fmtInsn.getOpcode() == Opcodes.GETFIELD
                            && bbCls.equals(fmtInsn.owner)
                            && "f_85658_".equals(fmtInsn.name)
                            && getDvf instanceof FieldInsnNode dvfInsn
                            && dvfInsn.getOpcode() == Opcodes.GETSTATIC
                            && "com/mojang/blaze3d/vertex/DefaultVertexFormat".equals(dvfInsn.owner)
                            && "f_85812_".equals(dvfInsn.name)
                            && jmp instanceof JumpInsnNode ifNe
                            && ifNe.getOpcode() == Opcodes.IF_ACMPNE) {
                        // Find the SodiumCubeRenderer.renderCube call and following RETURN right after ifNe
                        MethodInsnNode sodiumCall = null;
                        AbstractInsnNode retInsn = null;
                        int writerSlot = -1;
                        AbstractInsnNode cur = nextRealInsn(ifNe);
                        for (int steps = 0; cur != null && steps < 14; steps++, cur = nextRealInsn(cur)) {
                            if (cur instanceof MethodInsnNode mc
                                    && "com/moepus/gbf/renderer/SodiumCubeRenderer".equals(mc.owner)
                                    && "renderCube".equals(mc.name)) {
                                sodiumCall = mc;
                                AbstractInsnNode n = nextRealInsn(mc);
                                if (n != null && n.getOpcode() == Opcodes.RETURN) {
                                    retInsn = n;
                                }
                                break;
                            }
                            if (steps == 2 && cur instanceof VarInsnNode wv && wv.getOpcode() == Opcodes.ALOAD) {
                                writerSlot = wv.var;
                            }
                        }
                        if (sodiumCall != null && retInsn != null && writerSlot >= 0) {
                            LabelNode origFallback = ifNe.label;
                            LabelNode checkSbbIris = new LabelNode();
                            ifNe.label = checkSbbIris;
                            int localCount = Math.max(sbbVar.var, writerSlot) + 1;
                            Object[] locals = new Object[localCount];
                            java.util.Arrays.fill(locals, Opcodes.TOP);
                            locals[0] = cn.name;
                            locals[1] = "com/mojang/blaze3d/vertex/PoseStack";
                            locals[2] = "software/bernie/geckolib/cache/object/GeoCube";
                            locals[3] = "com/mojang/blaze3d/vertex/VertexConsumer";
                            locals[4] = Opcodes.INTEGER;
                            locals[5] = Opcodes.INTEGER;
                            locals[6] = Opcodes.FLOAT;
                            locals[7] = Opcodes.FLOAT;
                            locals[8] = Opcodes.FLOAT;
                            locals[9] = Opcodes.FLOAT;
                            locals[10] = "net/minecraft/world/phys/Vec3";
                            locals[writerSlot] = "net/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter";
                            locals[sbbVar.var] = sbbCls;
                            InsnList extra = new InsnList();
                            extra.add(checkSbbIris);
                            extra.add(new FrameNode(Opcodes.F_NEW, localCount, locals, 0, new Object[0]));
                            extra.add(new FieldInsnNode(Opcodes.GETSTATIC, irisCompatCls, "IS_IRIS_INSTALLED", "Z"));
                            extra.add(new JumpInsnNode(Opcodes.IFEQ, origFallback));
                            extra.add(new VarInsnNode(Opcodes.ALOAD, sbbVar.var));
                            extra.add(new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL, sbbCls, "getOriginalBufferBuilder",
                                    "()L" + bbCls + ";", false));
                            extra.add(new FieldInsnNode(Opcodes.GETFIELD, bbCls, "f_85658_", vfDesc));
                            extra.add(new FieldInsnNode(Opcodes.GETSTATIC, irisVfCls, "ENTITY", vfDesc));
                            extra.add(new JumpInsnNode(Opcodes.IF_ACMPNE, origFallback));
                            extra.add(new VarInsnNode(Opcodes.ALOAD, 1));
                            extra.add(new VarInsnNode(Opcodes.ALOAD, 2));
                            extra.add(new VarInsnNode(Opcodes.ALOAD, writerSlot));
                            extra.add(new VarInsnNode(Opcodes.ILOAD, 4));
                            extra.add(new VarInsnNode(Opcodes.ILOAD, 5));
                            extra.add(new VarInsnNode(Opcodes.FLOAD, 6));
                            extra.add(new VarInsnNode(Opcodes.FLOAD, 7));
                            extra.add(new VarInsnNode(Opcodes.FLOAD, 8));
                            extra.add(new VarInsnNode(Opcodes.FLOAD, 9));
                            extra.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, irisCubeCls, "renderCube", cubeRenderDesc, false));
                            extra.add(new InsnNode(Opcodes.RETURN));
                            mn.instructions.insert(retInsn, extra);
                            patched = true;
                        }
                    }
                    break;
                }
            }
            break;
        }
        return patched;
    }

    /**
     * Fixes GBF 1.0.2's Iris detection on Forge 1.20.1 where Iris is registered under mod ID
     * {@code "oculus"} (Emberlens/Oculus) rather than {@code "iris"}.
     */
    public static byte[] transformGbfIrisCompat(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean patched = false;
        final String lmlCls = "net/minecraftforge/fml/loading/LoadingModList";
        for (MethodNode mn : cn.methods) {
            if (!"<clinit>".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode isTrue = new LabelNode();
            LabelNode isFalse = new LabelNode();
            LabelNode store = new LabelNode();
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, lmlCls, "get", "()L" + lmlCls + ";", false));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new LdcInsnNode("iris"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, lmlCls, "getModFileById",
                    "(Ljava/lang/String;)Lnet/minecraftforge/fml/loading/moddiscovery/ModFileInfo;", false));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, isTrue));
            il.add(new LdcInsnNode("oculus"));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, lmlCls, "getModFileById",
                    "(Ljava/lang/String;)Lnet/minecraftforge/fml/loading/moddiscovery/ModFileInfo;", false));
            il.add(new JumpInsnNode(Opcodes.IFNULL, isFalse));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new JumpInsnNode(Opcodes.GOTO, store));
            il.add(isTrue);
            il.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {lmlCls}));
            il.add(new InsnNode(Opcodes.POP));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new JumpInsnNode(Opcodes.GOTO, store));
            il.add(isFalse);
            il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(store);
            il.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {Opcodes.INTEGER}));
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, GBF_IRIS_COMPAT_CLS, "IS_IRIS_INSTALLED", "Z"));
            il.add(new InsnNode(Opcodes.RETURN));
            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Widens vanilla {@code Frustum.m_113006_(DDDDDD)Z} ({@code cubeInFrustum}) to public and
     * implements {@code EmfRenderPerf$FastBoxFrustum} so entity frustum checks avoid both
     * {@code new AABB} allocations and {@code MethodHandle.invokeExact} dispatch.
     */
    public static byte[] transformFrustum(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String fastItf = "dev/sunlit/bettersunlit/client/EmfRenderPerf$FastBoxFrustum";
        boolean patched = false;
        boolean hasBridge = false;
        for (MethodNode mn : cn.methods) {
            if ("m_113006_".equals(mn.name) && "(DDDDDD)Z".equals(mn.desc)) {
                mn.access = (mn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                patched = true;
            } else if ("sunlit$isBoxVisible".equals(mn.name) && "(DDDDDD)Z".equals(mn.desc)) {
                hasBridge = true;
            }
        }
        if (!patched) {
            return null;
        }
        if (!hasBridge) {
            if (cn.interfaces == null) {
                cn.interfaces = new java.util.ArrayList<>();
            }
            if (!cn.interfaces.contains(fastItf)) {
                cn.interfaces.add(fastItf);
            }
            MethodNode bridge = new MethodNode(
                    Opcodes.ACC_PUBLIC, "sunlit$isBoxVisible", "(DDDDDD)Z", null, null);
            InsnList il = bridge.instructions;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.DLOAD, 1));
            il.add(new VarInsnNode(Opcodes.DLOAD, 3));
            il.add(new VarInsnNode(Opcodes.DLOAD, 5));
            il.add(new VarInsnNode(Opcodes.DLOAD, 7));
            il.add(new VarInsnNode(Opcodes.DLOAD, 9));
            il.add(new VarInsnNode(Opcodes.DLOAD, 11));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, cn.name, "m_113006_", "(DDDDDD)Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
            cn.methods.add(bridge);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds an allocation-free {@code sunlit$isBoxVisible(DDDDDD)Z} entry point and implements
     * {@code EmfRenderPerf$FastBoxFrustum} on Iris {@code AdvancedShadowCullingFrustum}.
     */
    public static byte[] transformIrisShadowFrustum(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String fastItf = "dev/sunlit/bettersunlit/client/EmfRenderPerf$FastBoxFrustum";
        boolean hasBoxCuller = false;
        boolean hasIsVisible = false;
        for (FieldNode fn : cn.fields) {
            if ("boxCuller".equals(fn.name)
                    && "Lnet/irisshaders/iris/shadows/frustum/BoxCuller;".equals(fn.desc)) {
                hasBoxCuller = true;
            }
        }
        for (MethodNode mn : cn.methods) {
            if ("isVisible".equals(mn.name) && "(DDDDDD)I".equals(mn.desc)) {
                hasIsVisible = true;
            }
            if ("sunlit$isBoxVisible".equals(mn.name)) {
                return null;
            }
        }
        if (!hasBoxCuller || !hasIsVisible) {
            return null;
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>();
        }
        if (!cn.interfaces.contains(fastItf)) {
            cn.interfaces.add(fastItf);
        }
        MethodNode helper = new MethodNode(
                Opcodes.ACC_PUBLIC, "sunlit$isBoxVisible", "(DDDDDD)Z", null, null);
        InsnList il = helper.instructions;
        LabelNode checkFrustum = new LabelNode();
        LabelNode retFalse = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(
                Opcodes.GETFIELD, IRIS_ADV_FRUSTUM_CLS, "boxCuller",
                "Lnet/irisshaders/iris/shadows/frustum/BoxCuller;"));
        il.add(new JumpInsnNode(Opcodes.IFNULL, checkFrustum));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(
                Opcodes.GETFIELD, IRIS_ADV_FRUSTUM_CLS, "boxCuller",
                "Lnet/irisshaders/iris/shadows/frustum/BoxCuller;"));
        il.add(new VarInsnNode(Opcodes.DLOAD, 1));
        il.add(new VarInsnNode(Opcodes.DLOAD, 3));
        il.add(new VarInsnNode(Opcodes.DLOAD, 5));
        il.add(new VarInsnNode(Opcodes.DLOAD, 7));
        il.add(new VarInsnNode(Opcodes.DLOAD, 9));
        il.add(new VarInsnNode(Opcodes.DLOAD, 11));
        il.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "net/irisshaders/iris/shadows/frustum/BoxCuller",
                "isCulled", "(DDDDDD)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFNE, retFalse));
        il.add(checkFrustum);
        il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.DLOAD, 1));
        il.add(new VarInsnNode(Opcodes.DLOAD, 3));
        il.add(new VarInsnNode(Opcodes.DLOAD, 5));
        il.add(new VarInsnNode(Opcodes.DLOAD, 7));
        il.add(new VarInsnNode(Opcodes.DLOAD, 9));
        il.add(new VarInsnNode(Opcodes.DLOAD, 11));
        il.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, IRIS_ADV_FRUSTUM_CLS, "isVisible", "(DDDDDD)I", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, retFalse));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.IRETURN));
        il.add(retFalse);
        il.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IRETURN));
        cn.methods.add(helper);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Pools per-BufferBuilder {@code Vector3f} instances in {@code BufferBuilder.m_166794_}
     * ({@code makeQuadSortingPoints}) and reads vertex coordinates off-heap without
     * {@code ByteBuffer.asFloatBuffer()} (~340MB GC churn eliminated in 55s). Detaches the pool
     * in {@code m_166770_} ({@code getSortState}) so snapshotted {@code SortState} arrays remain
     * strictly isolated.
     */
    public static byte[] transformBufferBuilder(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        final String poolField = "sunlit$sortPool";
        boolean hasMakePoints = false;
        boolean hasGetSortState = false;
        for (FieldNode fn : cn.fields) {
            if (poolField.equals(fn.name)) {
                return null;
            }
        }
        for (MethodNode mn : cn.methods) {
            if ("m_166794_".equals(mn.name) && "()[Lorg/joml/Vector3f;".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode hasPool = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, poolField, "Ljava/lang/Object;"));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasPool));
                il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, perfCls, "newQuadSortPool", "()Ljava/lang/Object;", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, MC_BUFFER_BUILDER_CLS, poolField, "Ljava/lang/Object;"));
                il.add(hasPool);
                il.add(new FrameNode(
                        Opcodes.F_NEW,
                        2,
                        new Object[] {MC_BUFFER_BUILDER_CLS, "java/lang/Object"},
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, "f_85648_", "Ljava/nio/ByteBuffer;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, "f_231157_", "I"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, "f_85658_", "Lcom/mojang/blaze3d/vertex/VertexFormat;"));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/VertexFormat", "m_86017_", "()I", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, "f_85657_", "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;"));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, "com/mojang/blaze3d/vertex/VertexFormat$Mode", "f_166948_", "I"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BUFFER_BUILDER_CLS, "f_85654_", "I"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "makeQuadSortingPointsPooled",
                        "(Ljava/nio/ByteBuffer;IIIILjava/lang/Object;)[Lorg/joml/Vector3f;", false));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                hasMakePoints = true;
            } else if ("m_166770_".equals(mn.name)
                    && "()Lcom/mojang/blaze3d/vertex/BufferBuilder$SortState;".equals(mn.desc)
                    && mn.instructions != null) {
                InsnList detach = new InsnList();
                detach.add(new VarInsnNode(Opcodes.ALOAD, 0));
                detach.add(new InsnNode(Opcodes.ACONST_NULL));
                detach.add(new FieldInsnNode(Opcodes.PUTFIELD, MC_BUFFER_BUILDER_CLS, poolField, "Ljava/lang/Object;"));
                mn.instructions.insert(detach);
                hasGetSortState = true;
            }
        }
        if (!hasMakePoints || !hasGetSortState) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, poolField, "Ljava/lang/Object;", null, null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Prepends a fast early-exit to {@code RenderUtils.fixInvertedFlatCube(GeoCube, Vector3f)}:
     * when all 3 cube size components are non-zero (non-flat 3D cubes, ~98% of geometry),
     * none of the inverted-flat-cube flips can trigger.
     */
    public static byte[] transformGeckoLibRenderUtils(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String desc = "(Lsoftware/bernie/geckolib/cache/object/GeoCube;Lorg/joml/Vector3f;)V";
        final String vec3Cls = "net/minecraft/world/phys/Vec3";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"fixInvertedFlatCube".equals(mn.name) || !desc.equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            LabelNode fallthrough = new LabelNode();
            InsnList fast = new InsnList();
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "software/bernie/geckolib/cache/object/GeoCube",
                    "size", "()L" + vec3Cls + ";", false));
            fast.add(new InsnNode(Opcodes.DUP));
            fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, vec3Cls, "m_7096_", "()D", false));
            fast.add(new InsnNode(Opcodes.DCONST_0));
            fast.add(new InsnNode(Opcodes.DCMPL));
            LabelNode popAndFallthrough = new LabelNode();
            fast.add(new JumpInsnNode(Opcodes.IFEQ, popAndFallthrough));
            fast.add(new InsnNode(Opcodes.DUP));
            fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, vec3Cls, "m_7098_", "()D", false));
            fast.add(new InsnNode(Opcodes.DCONST_0));
            fast.add(new InsnNode(Opcodes.DCMPL));
            fast.add(new JumpInsnNode(Opcodes.IFEQ, popAndFallthrough));
            fast.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, vec3Cls, "m_7094_", "()D", false));
            fast.add(new InsnNode(Opcodes.DCONST_0));
            fast.add(new InsnNode(Opcodes.DCMPL));
            fast.add(new JumpInsnNode(Opcodes.IFEQ, fallthrough));
            fast.add(new InsnNode(Opcodes.RETURN));
            fast.add(popAndFallthrough);
            fast.add(new FrameNode(
                    Opcodes.F_NEW,
                    2,
                    new Object[] {"software/bernie/geckolib/cache/object/GeoCube", "org/joml/Vector3f"},
                    1,
                    new Object[] {vec3Cls}));
            fast.add(new InsnNode(Opcodes.POP));
            fast.add(fallthrough);
            fast.add(new FrameNode(
                    Opcodes.F_NEW,
                    2,
                    new Object[] {"software/bernie/geckolib/cache/object/GeoCube", "org/joml/Vector3f"},
                    0,
                    new Object[0]));
            mn.instructions.insert(fast);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds a zero-allocation 1-element fast slot to GeckoLib's {@code AnimationPointQueue}
     * (which extends {@code LinkedList} and almost exclusively performs single-element
     * {@code add -> poll} pairs per bone per tick), eliminating ~140MB+ of {@code LinkedList$Node}
     * churn while transparently flushing to {@code super} for multi-element or collection operations.
     */
    public static byte[] transformGeckoLibAnimationPointQueue(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (!"java/util/LinkedList".equals(cn.superName)) {
            return null;
        }
        final String self = GECKOLIB_ANIM_POINT_QUEUE_CLS;
        final String ll = "java/util/LinkedList";
        final String slot = "sunlit$single";
        for (FieldNode fn : cn.fields) {
            if (slot.equals(fn.name)) {
                return null;
            }
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, slot, "Ljava/lang/Object;", null, null));

        // private void sunlit$flush()
        MethodNode flushM = new MethodNode(Opcodes.ACC_PRIVATE, "sunlit$flush", "()V", null, null);
        {
            InsnList il = flushM.instructions;
            LabelNode done = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IFNULL, done));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.POP));
            il.add(done);
            il.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {self, "java/lang/Object"}, 0, new Object[0]));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(flushM);

        // public boolean add(Object e)
        MethodNode addM = new MethodNode(Opcodes.ACC_PUBLIC, "add", "(Ljava/lang/Object;)Z", null, null);
        {
            InsnList il = addM.instructions;
            LabelNode slow = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IFNULL, slow));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, slow));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "size", "()I", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, slow));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(slow);
            il.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {self, "java/lang/Object"}, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, self, "sunlit$flush", "()V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
        }
        cn.methods.add(addM);

        // public boolean offer(Object e)
        MethodNode offerM = new MethodNode(Opcodes.ACC_PUBLIC, "offer", "(Ljava/lang/Object;)Z", null, null);
        {
            InsnList il = offerM.instructions;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, self, "add", "(Ljava/lang/Object;)Z", false));
            il.add(new InsnNode(Opcodes.IRETURN));
        }
        cn.methods.add(offerM);

        // public Object poll()
        MethodNode pollM = new MethodNode(Opcodes.ACC_PUBLIC, "poll", "()Ljava/lang/Object;", null, null);
        {
            InsnList il = pollM.instructions;
            LabelNode slow = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IFNULL, slow));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(slow);
            il.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {self, "java/lang/Object"}, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "poll", "()Ljava/lang/Object;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        cn.methods.add(pollM);

        // public Object peek()
        MethodNode peekM = new MethodNode(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
        {
            InsnList il = peekM.instructions;
            LabelNode slow = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new JumpInsnNode(Opcodes.IFNULL, slow));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new InsnNode(Opcodes.ARETURN));
            il.add(slow);
            il.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {self, "java/lang/Object"}, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "peek", "()Ljava/lang/Object;", false));
            il.add(new InsnNode(Opcodes.ARETURN));
        }
        cn.methods.add(peekM);

        // public int size()
        MethodNode sizeM = new MethodNode(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        {
            InsnList il = sizeM.instructions;
            LabelNode slow = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new JumpInsnNode(Opcodes.IFNULL, slow));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(slow);
            il.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] {self}, 0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "size", "()I", false));
            il.add(new InsnNode(Opcodes.IRETURN));
        }
        cn.methods.add(sizeM);

        // public boolean isEmpty()
        MethodNode emptyM = new MethodNode(Opcodes.ACC_PUBLIC, "isEmpty", "()Z", null, null);
        {
            InsnList il = emptyM.instructions;
            LabelNode retFalse = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new JumpInsnNode(Opcodes.IFNONNULL, retFalse));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "size", "()I", false));
            il.add(new JumpInsnNode(Opcodes.IFNE, retFalse));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IRETURN));
            il.add(retFalse);
            il.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] {self}, 0, new Object[0]));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new InsnNode(Opcodes.IRETURN));
        }
        cn.methods.add(emptyM);

        // public void clear()
        MethodNode clearM = new MethodNode(Opcodes.ACC_PUBLIC, "clear", "()V", null, null);
        {
            InsnList il = clearM.instructions;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, slot, "Ljava/lang/Object;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, "clear", "()V", false));
            il.add(new InsnNode(Opcodes.RETURN));
        }
        cn.methods.add(clearM);

        // Delegate all other commonly used LinkedList methods via sunlit$flush() + super.<m>()
        String[][] flushDelegates = new String[][] {
            {"iterator", "()Ljava/util/Iterator;"},
            {"listIterator", "(I)Ljava/util/ListIterator;"},
            {"descendingIterator", "()Ljava/util/Iterator;"},
            {"getFirst", "()Ljava/lang/Object;"},
            {"getLast", "()Ljava/lang/Object;"},
            {"get", "(I)Ljava/lang/Object;"},
            {"remove", "()Ljava/lang/Object;"},
            {"removeFirst", "()Ljava/lang/Object;"},
            {"removeLast", "()Ljava/lang/Object;"},
            {"remove", "(Ljava/lang/Object;)Z"},
            {"remove", "(I)Ljava/lang/Object;"},
            {"contains", "(Ljava/lang/Object;)Z"},
            {"toArray", "()[Ljava/lang/Object;"},
            {"toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;"},
            {"addFirst", "(Ljava/lang/Object;)V"},
            {"addLast", "(Ljava/lang/Object;)V"},
            {"add", "(ILjava/lang/Object;)V"},
            {"addAll", "(Ljava/util/Collection;)Z"}
        };
        for (String[] pair : flushDelegates) {
            String mName = pair[0];
            String mDesc = pair[1];
            MethodNode dm = new MethodNode(Opcodes.ACC_PUBLIC, mName, mDesc, null, null);
            InsnList il = dm.instructions;
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, self, "sunlit$flush", "()V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            Type[] args = Type.getArgumentTypes(mDesc);
            int slotIdx = 1;
            for (Type arg : args) {
                il.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slotIdx));
                slotIdx += arg.getSize();
            }
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ll, mName, mDesc, false));
            Type ret = Type.getReturnType(mDesc);
            il.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            cn.methods.add(dm);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces {@code CopyOnWriteArrayList.isEmpty()/iterator()} overhead on every block in
     * Emberbium {@code BlockRendererRegistry.fillCustomRenderers} with a lock-free volatile
     * {@code RenderPopulator[]} snapshot array updated in {@code registerRenderPopulator}.
     */
    public static byte[] transformEmbeddiumBlockRendererRegistry(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String self = EMBEDDIUM_BLOCK_RENDERER_REGISTRY_CLS;
        final String popCls = self + "$RenderPopulator";
        final String popArrDesc = "[L" + popCls + ";";
        final String arrField = "sunlit$popArray";
        final String fillDesc = "(Ljava/util/List;Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext;)V";
        final String regDesc = "(L" + popCls + ";)V";
        for (FieldNode fn : cn.fields) {
            if (arrField.equals(fn.name)) {
                return null;
            }
        }
        boolean patchedInit = false;
        boolean patchedReg = false;
        boolean patchedFill = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "()V".equals(mn.desc) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList initArr = new InsnList();
                        initArr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        initArr.add(new InsnNode(Opcodes.ICONST_0));
                        initArr.add(new TypeInsnNode(Opcodes.ANEWARRAY, popCls));
                        initArr.add(new FieldInsnNode(Opcodes.PUTFIELD, self, arrField, popArrDesc));
                        mn.instructions.insertBefore(insn, initArr);
                        patchedInit = true;
                    }
                }
            } else if ("registerRenderPopulator".equals(mn.name) && regDesc.equals(mn.desc) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList syncArr = new InsnList();
                        syncArr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        syncArr.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        syncArr.add(new FieldInsnNode(Opcodes.GETFIELD, self, "renderPopulators", "Ljava/util/List;"));
                        syncArr.add(new InsnNode(Opcodes.ICONST_0));
                        syncArr.add(new TypeInsnNode(Opcodes.ANEWARRAY, popCls));
                        syncArr.add(new MethodInsnNode(
                                Opcodes.INVOKEINTERFACE, "java/util/List", "toArray",
                                "([Ljava/lang/Object;)[Ljava/lang/Object;", true));
                        syncArr.add(new TypeInsnNode(Opcodes.CHECKCAST, popArrDesc));
                        syncArr.add(new FieldInsnNode(Opcodes.PUTFIELD, self, arrField, popArrDesc));
                        mn.instructions.insertBefore(insn, syncArr);
                        patchedReg = true;
                    }
                }
            } else if ("fillCustomRenderers".equals(mn.name) && fillDesc.equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode loop = new LabelNode();
                LabelNode done = new LabelNode();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, self, arrField, popArrDesc));
                il.add(new VarInsnNode(Opcodes.ASTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new InsnNode(Opcodes.ARRAYLENGTH));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IFEQ, done));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 5));
                il.add(loop);
                il.add(new FrameNode(
                        Opcodes.F_NEW,
                        6,
                        new Object[] {
                            self,
                            "java/util/List",
                            "me/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext",
                            popArrDesc,
                            Opcodes.INTEGER,
                            Opcodes.INTEGER
                        },
                        0,
                        new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
                il.add(new VarInsnNode(Opcodes.ALOAD, 3));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new InsnNode(Opcodes.AALOAD));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, popCls, "fillCustomRenderers", fillDesc, true));
                il.add(new IincInsnNode(5, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loop));
                il.add(done);
                il.add(new FrameNode(
                        Opcodes.F_NEW,
                        5,
                        new Object[] {
                            self,
                            "java/util/List",
                            "me/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext",
                            popArrDesc,
                            Opcodes.INTEGER
                        },
                        0,
                        new Object[0]));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedFill = true;
            }
        }
        if (!patchedInit || !patchedReg || !patchedFill) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, arrField, popArrDesc, null, null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Redirects Iris {@code EntityVertex.writeQuadVertices} to {@code EmfRenderPerf.writeIrisEntityQuad},
     * eliminating per-quad {@code MemoryStack.stackPush()/close()}, 4x {@code Direction.step()}
     * {@code Vector3f} allocations, 4x {@code CapturedRenderingState} queries, and off-heap tangent readbacks.
     */
    public static byte[] transformIrisEntityVertex(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String targetDesc = "(Lnet/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter;"
                + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                + "Lme/jellysquid/mods/sodium/client/model/quad/ModelQuadView;III)V";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("writeQuadVertices".equals(mn.name) && targetDesc.equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 5));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "writeIrisEntityQuad", targetDesc, false));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Optimizes {@code ClientLevel.m_233612_} ({@code doAnimateTick}, called 26,680 times/sec):
     * <ul>
     *   <li>Replaces redundant {@code this.m_6425_(pos)} ({@code getFluidState}) with {@code blockState.m_60819_()}.</li>
     *   <li>Routes the biome ambient particle check through {@code EmfRenderPerf.maybeSpawnBiomeAmbientParticle}
     *       to skip Voronoi {@code BiomeManager.getBiome} hashes and lambda allocations when no nearby biome has
     *       ambient particles.</li>
     * </ul>
     */
    public static byte[] transformClientLevel(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String targetDesc = "(IIIILnet/minecraft/util/RandomSource;"
                + "Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V";
        final String bsCls = "net/minecraft/world/level/block/state/BlockState";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"m_233612_".equals(mn.name) || !targetDesc.equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            // 1. Replace `ALOAD 0; ALOAD 7; INVOKEVIRTUAL ClientLevel.m_6425_(BlockPos)FluidState`
            //    with `ALOAD 11; INVOKEVIRTUAL BlockState.m_60819_()FluidState`
            int bsSlot = -1;
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode m
                        && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && MC_CLIENT_LEVEL_CLS.equals(m.owner)
                        && "m_8055_".equals(m.name)
                        && "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;".equals(m.desc)) {
                    AbstractInsnNode st = nextRealInsn(m);
                    if (st instanceof VarInsnNode vin && vin.getOpcode() == Opcodes.ASTORE) {
                        bsSlot = vin.var;
                        break;
                    }
                }
            }
            if (bsSlot >= 0) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode m
                            && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && MC_CLIENT_LEVEL_CLS.equals(m.owner)
                            && "m_6425_".equals(m.name)
                            && "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;".equals(m.desc)) {
                        AbstractInsnNode p1 = prevRealInsn(m);
                        AbstractInsnNode p2 = prevRealInsn(p1);
                        if (p1 instanceof VarInsnNode vPos && vPos.getOpcode() == Opcodes.ALOAD && vPos.var == 7
                                && p2 instanceof VarInsnNode vThis && vThis.getOpcode() == Opcodes.ALOAD && vThis.var == 0) {
                            mn.instructions.remove(p2);
                            mn.instructions.set(p1, new VarInsnNode(Opcodes.ALOAD, bsSlot));
                            mn.instructions.set(m, new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL, bsCls, "m_60819_",
                                    "()Lnet/minecraft/world/level/material/FluidState;", false));
                            patched = true;
                            break;
                        }
                    }
                }
            }
            // 2. Replace biome ambient particle lookup + Optional.ifPresent (both vanilla INVOKEDYNAMIC
            //    and Emberbium GETFIELD embeddium$particleSettingsConsumer forms) with
            //    `ALOAD 0; ALOAD 7; INVOKESTATIC EmfRenderPerf.maybeSpawnBiomeAmbientParticle(ClientLevel, MutableBlockPos)V`
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode getBiome
                        && getBiome.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && MC_CLIENT_LEVEL_CLS.equals(getBiome.owner)
                        && "m_204166_".equals(getBiome.name)
                        && "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;".equals(getBiome.desc)) {
                    AbstractInsnNode p1 = prevRealInsn(getBiome);
                    AbstractInsnNode p2 = prevRealInsn(p1);
                    AbstractInsnNode n1 = nextRealInsn(getBiome); // Holder.m_203334_()
                    AbstractInsnNode n2 = nextRealInsn(n1);       // CHECKCAST Biome
                    AbstractInsnNode n3 = nextRealInsn(n2);       // Biome.m_47562_()
                    AbstractInsnNode n4 = nextRealInsn(n3);       // ALOAD 0
                    AbstractInsnNode n5 = nextRealInsn(n4);       // ALOAD 7 (vanilla) or GETFIELD (Emberbium)
                    AbstractInsnNode n6 = nextRealInsn(n5);       // INVOKEDYNAMIC (vanilla) or Optional.ifPresent (Emberbium)
                    if (p1 instanceof VarInsnNode vp && vp.getOpcode() == Opcodes.ALOAD && vp.var == 7
                            && p2 instanceof VarInsnNode vt && vt.getOpcode() == Opcodes.ALOAD && vt.var == 0
                            && n3 instanceof MethodInsnNode mAmb && "m_47562_".equals(mAmb.name)) {
                        if (n4 instanceof VarInsnNode vSelf && vSelf.getOpcode() == Opcodes.ALOAD && vSelf.var == 0
                                && n5 instanceof FieldInsnNode fCons && fCons.getOpcode() == Opcodes.GETFIELD
                                && "java/util/function/Consumer".equals(
                                        fCons.desc.length() > 2 ? fCons.desc.substring(1, fCons.desc.length() - 1) : "")
                                && n6 instanceof MethodInsnNode mIfPres
                                && "java/util/Optional".equals(mIfPres.owner)
                                && "ifPresent".equals(mIfPres.name)) {
                            mn.instructions.set(getBiome, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, perfCls, "maybeSpawnBiomeAmbientParticle",
                                    "(L" + MC_CLIENT_LEVEL_CLS + ";Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
                                    false));
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            mn.instructions.remove(n3);
                            mn.instructions.remove(n4);
                            mn.instructions.remove(n5);
                            mn.instructions.remove(n6);
                            patched = true;
                            break;
                        }
                        AbstractInsnNode n7 = nextRealInsn(n6); // Optional.ifPresent (vanilla)
                        if (n6 instanceof InvokeDynamicInsnNode
                                && n7 instanceof MethodInsnNode mIfPres
                                && "java/util/Optional".equals(mIfPres.owner)
                                && "ifPresent".equals(mIfPres.name)) {
                            mn.instructions.set(getBiome, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, perfCls, "maybeSpawnBiomeAmbientParticle",
                                    "(L" + MC_CLIENT_LEVEL_CLS + ";Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
                                    false));
                            mn.instructions.remove(n1);
                            mn.instructions.remove(n2);
                            mn.instructions.remove(n3);
                            mn.instructions.remove(n4);
                            mn.instructions.remove(n5);
                            mn.instructions.remove(n6);
                            mn.instructions.remove(n7);
                            patched = true;
                            break;
                        }
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Implements {@code EmfRenderPerf$BakedQuadIrisMeta} on vanilla {@code BakedQuad} so
     * {@code EmfRenderPerf.writeIrisEntityQuad} caches object-space tangents and midUV once per quad.
     */
    public static byte[] transformBakedQuad(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String itf = "dev/sunlit/bettersunlit/client/EmfRenderPerf$BakedQuadIrisMeta";
        final String fieldName = "sunlit$irisMeta";
        for (FieldNode fn : cn.fields) {
            if (fieldName.equals(fn.name)) {
                return null;
            }
        }
        if (cn.interfaces == null) {
            cn.interfaces = new java.util.ArrayList<>();
        }
        if (!cn.interfaces.contains(itf)) {
            cn.interfaces.add(itf);
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, fieldName, "[F", null, null));

        MethodNode getM = new MethodNode(Opcodes.ACC_PUBLIC, "sunlit$getIrisMeta", "()[F", null, null);
        getM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getM.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, MC_BAKED_QUAD_CLS, fieldName, "[F"));
        getM.instructions.add(new InsnNode(Opcodes.ARETURN));
        cn.methods.add(getM);

        MethodNode setM = new MethodNode(Opcodes.ACC_PUBLIC, "sunlit$setIrisMeta", "([F)V", null, null);
        setM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setM.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setM.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, MC_BAKED_QUAD_CLS, fieldName, "[F"));
        setM.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(setM);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Applies string-matches optimization and interns variable name strings in
     * {@code ModelVariableFactory.getSupplierOrNull} so entity variable map lookups hit
     * pointer-equality fast paths.
     */
    public static byte[] transformEmfModelVariableFactory(Module module, byte[] bytes) {
        byte[] base = transformStringMatches(module, bytes);
        byte[] src = base != null ? base : bytes;
        ClassNode cn = new ClassNode();
        new ClassReader(src).accept(cn, ClassReader.EXPAND_FRAMES);
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("getSupplierOrNull".equals(mn.name)
                    && mn.desc != null
                    && mn.desc.startsWith("(Ljava/lang/String;")
                    && mn.instructions != null) {
                LabelNode notNull = new LabelNode();
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new JumpInsnNode(Opcodes.IFNULL, notNull));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false));
                pre.add(new VarInsnNode(Opcodes.ASTORE, 1));
                pre.add(notNull);
                Type[] argTypes = Type.getArgumentTypes(mn.desc);
                Object[] locals = new Object[1 + argTypes.length];
                locals[0] = cn.name;
                for (int i = 0; i < argTypes.length; i++) {
                    locals[1 + i] = asmFrameType(argTypes[i]);
                }
                pre.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]));
                mn.instructions.insert(pre);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return base;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds an {@code isEmpty()} fast-return before {@code Object2FloatMap.getOrDefault} in
     * {@code EMFMath.getEntityVariable(String, float)} to avoid hashing and probing empty per-entity
     * variable maps.
     */
    public static byte[] transformEmfMath(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String o2fCls = "it/unimi/dsi/fastutil/objects/Object2FloatMap";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"getEntityVariable".equals(mn.name)
                    || !"(Ljava/lang/String;F)F".equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEINTERFACE
                        && o2fCls.equals(min.owner)
                        && "getOrDefault".equals(min.name)
                        && "(Ljava/lang/Object;F)F".equals(min.desc)) {
                    AbstractInsnNode p1 = prevRealInsn(min);
                    AbstractInsnNode p2 = prevRealInsn(p1);
                    AbstractInsnNode p3 = prevRealInsn(p2);
                    if (p1 instanceof VarInsnNode vDef && vDef.getOpcode() == Opcodes.FLOAD && vDef.var == 1
                            && p2 instanceof VarInsnNode vKey && vKey.getOpcode() == Opcodes.ALOAD && vKey.var == 0
                            && p3 instanceof VarInsnNode vMap && vMap.getOpcode() == Opcodes.ALOAD) {
                        int mapSlot = vMap.var;
                        LabelNode nonEmpty = new LabelNode();
                        InsnList guard = new InsnList();
                        guard.add(new VarInsnNode(Opcodes.ALOAD, mapSlot));
                        guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, o2fCls, "isEmpty", "()Z", true));
                        guard.add(new JumpInsnNode(Opcodes.IFEQ, nonEmpty));
                        guard.add(new VarInsnNode(Opcodes.FLOAD, 1));
                        guard.add(new InsnNode(Opcodes.FRETURN));
                        guard.add(nonEmpty);
                        int localLen = Math.max(5, mapSlot + 1);
                        Object[] locals = new Object[localLen];
                        java.util.Arrays.fill(locals, Opcodes.TOP);
                        locals[0] = "java/lang/String";
                        locals[1] = Opcodes.FLOAT;
                        locals[2] = "traben/entity_model_features/models/animation/state/EMFEntityRenderState";
                        locals[mapSlot] = o2fCls;
                        if (mapSlot != 4) {
                            locals[4] = "java/util/Map";
                        }
                        guard.add(new FrameNode(Opcodes.F_NEW, localLen, locals, 0, new Object[0]));
                        mn.instructions.insertBefore(p3, guard);
                        patched = true;
                        break;
                    }
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Optimizes EMF {@code ASMAnimationHandler}:
     * <ul>
     *   <li>Replaces {@code lambda$finishAndValidate$2}'s 2x {@code List.forEach(capturingLambda)}
     *       with zero-allocation indexed loops.</li>
     *   <li>Interns variable names passed to {@code EMFMath.setEntityVariable} in
     *       {@code buildFloatConsumers} so lookups hit {@code String ==} fast paths.</li>
     * </ul>
     */
    public static byte[] transformEmfAsmAnimationHandler(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String animVarsCls = EMF_ASM_ANIM_HANDLER_CLS + "$AnimVars";
        final String floatConsCls = EMF_ASM_ANIM_HANDLER_CLS + "$FloatConsumerAsm";
        final String boolConsCls = EMF_ASM_ANIM_HANDLER_CLS + "$BoolConsumerAsm";
        final String finishDesc = "(Ljava/util/List;Ljava/util/List;L" + animVarsCls + ";Z)V";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("lambda$finishAndValidate$2".equals(mn.name) && finishDesc.equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode loopF = new LabelNode();
                LabelNode afterF = new LabelNode();
                LabelNode loopB = new LabelNode();
                LabelNode done = new LabelNode();

                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IFEQ, afterF));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, animVarsCls, "floats", "()[F", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 5));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 6));
                il.add(loopF);
                il.add(new FrameNode(
                        Opcodes.F_NEW, 7,
                        new Object[] {"java/util/List", "java/util/List", animVarsCls, Opcodes.INTEGER, Opcodes.INTEGER, "[F", Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, afterF));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, floatConsCls));
                il.add(new VarInsnNode(Opcodes.ALOAD, 5));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, floatConsCls, "accept", "([FZ)V", true));
                il.add(new IincInsnNode(6, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopF));

                il.add(afterF);
                il.add(new FrameNode(
                        Opcodes.F_NEW, 4,
                        new Object[] {"java/util/List", "java/util/List", animVarsCls, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
                il.add(new VarInsnNode(Opcodes.ISTORE, 4));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IFEQ, done));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, animVarsCls, "bools", "()[Z", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 5));
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new VarInsnNode(Opcodes.ISTORE, 6));
                il.add(loopB);
                il.add(new FrameNode(
                        Opcodes.F_NEW, 7,
                        new Object[] {"java/util/List", "java/util/List", animVarsCls, Opcodes.INTEGER, Opcodes.INTEGER, "[Z", Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new VarInsnNode(Opcodes.ILOAD, 4));
                il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ILOAD, 6));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, boolConsCls));
                il.add(new VarInsnNode(Opcodes.ALOAD, 5));
                il.add(new VarInsnNode(Opcodes.ILOAD, 3));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, boolConsCls, "accept", "([ZZ)V", true));
                il.add(new IincInsnNode(6, 1));
                il.add(new JumpInsnNode(Opcodes.GOTO, loopB));

                il.add(done);
                il.add(new FrameNode(
                        Opcodes.F_NEW, 4,
                        new Object[] {"java/util/List", "java/util/List", animVarsCls, Opcodes.INTEGER},
                        0, new Object[0]));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("buildFloatConsumers".equals(mn.name) && "()Ljava/util/List;".equals(mn.desc) && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && "accept".equals(indy.name)
                            && ("(Ljava/lang/String;I)L" + floatConsCls + ";").equals(indy.desc)) {
                        AbstractInsnNode prevInt = prevRealInsn(indy);
                        AbstractInsnNode prevStr = prevRealInsn(prevInt);
                        if (prevStr instanceof VarInsnNode vStr && vStr.getOpcode() == Opcodes.ALOAD) {
                            mn.instructions.insert(prevStr, new MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false));
                            patched = true;
                        }
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds in-place memoized supplier and primitive double storage to GeckoLib {@code LazyVariable},
     * eliminating ~140MB of per-frame {@code MolangParser$1} and {@code set(double)} lambda allocations.
     */
    public static byte[] transformGeckoLibLazyVariable(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String self = GECKOLIB_LAZY_VAR_CLS;
        final String supCls = "java/util/function/DoubleSupplier";
        final String supDesc = "L" + supCls + ";";
        final String varCls = "com/eliotlash/mclib/math/Variable";
        for (FieldNode fn : cn.fields) {
            if ("sunlit$memo".equals(fn.name)) {
                return null;
            }
        }
        boolean patchedCtor = false;
        boolean patchedSetD = false;
        boolean patchedSetS = false;
        boolean patchedGet = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && "(Ljava/lang/String;D)V".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.DCONST_0));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, varCls, "<init>", "(Ljava/lang/String;D)V", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.DLOAD, 2));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$val", "D"));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedCtor = true;
            } else if ("set".equals(mn.name) && "(D)V".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "valueSupplier", supDesc));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$memo", supDesc));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.DLOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$val", "D"));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedSetD = true;
            } else if ("set".equals(mn.name) && ("(" + supDesc + ")V").equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "valueSupplier", supDesc));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new InsnNode(Opcodes.ACONST_NULL));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$memo", supDesc));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedSetS = true;
            } else if ("get".equals(mn.name) && "()D".equals(mn.desc) && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode checkMemo = new LabelNode();
                LabelNode retMemo = new LabelNode();
                LabelNode retVal = new LabelNode();

                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "valueSupplier", supDesc));
                il.add(new VarInsnNode(Opcodes.ASTORE, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new JumpInsnNode(Opcodes.IFNULL, checkMemo));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, supCls, "getAsDouble", "()D", true));
                il.add(new InsnNode(Opcodes.DRETURN));

                il.add(checkMemo);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2, new Object[] {self, supCls}, 0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "sunlit$memo", supDesc));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, retVal));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "sunlit$val", "D"));
                il.add(new VarInsnNode(Opcodes.DSTORE, 3));
                il.add(new VarInsnNode(Opcodes.DLOAD, 3));
                il.add(new LdcInsnNode(Double.MIN_VALUE));
                il.add(new InsnNode(Opcodes.DCMPL));
                il.add(new JumpInsnNode(Opcodes.IFNE, retMemo));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, supCls, "getAsDouble", "()D", true));
                il.add(new VarInsnNode(Opcodes.DSTORE, 3));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.DLOAD, 3));
                il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$val", "D"));

                il.add(retMemo);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 4, new Object[] {self, supCls, supCls, Opcodes.DOUBLE}, 0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.DLOAD, 3));
                il.add(new InsnNode(Opcodes.DRETURN));

                il.add(retVal);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3, new Object[] {self, supCls, supCls}, 0, new Object[0]));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "sunlit$val", "D"));
                il.add(new InsnNode(Opcodes.DRETURN));

                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedGet = true;
            }
        }
        if (!patchedCtor || !patchedSetD || !patchedSetS || !patchedGet) {
            return null;
        }
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlit$memo", supDesc, null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "sunlit$val", "D", null, null));

        MethodNode setMemo = new MethodNode(Opcodes.ACC_PUBLIC, "sunlit$setMemoized", "(" + supDesc + ")V", null, null);
        setMemo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setMemo.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        setMemo.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "valueSupplier", supDesc));
        setMemo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setMemo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setMemo.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$memo", supDesc));
        setMemo.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setMemo.instructions.add(new LdcInsnNode(Double.MIN_VALUE));
        setMemo.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "sunlit$val", "D"));
        setMemo.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(setMemo);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Routes GeckoLib {@code MolangParser.setMemoizedValue} through {@code LazyVariable.sunlit$setMemoized}
     * (zero {@code MolangParser$1} allocations) and adds a fast {@code Map.get} path to {@code getVariable}.
     */
    public static byte[] transformGeckoLibMolangParser(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String self = GECKOLIB_MOLANG_PARSER_CLS;
        final String lazyCls = GECKOLIB_LAZY_VAR_CLS;
        final String supDesc = "Ljava/util/function/DoubleSupplier;";
        boolean patchedSetMemo = false;
        boolean patchedGetVar = false;
        for (MethodNode mn : cn.methods) {
            if ("setMemoizedValue".equals(mn.name)
                    && ("(Ljava/lang/String;" + supDesc + ")V").equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, self, "getVariable",
                        "(Ljava/lang/String;)L" + lazyCls + ";", false));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, lazyCls, "sunlit$setMemoized",
                        "(" + supDesc + ")V", false));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedSetMemo = true;
            } else if ("getVariable".equals(mn.name)
                    && ("(Ljava/lang/String;)L" + lazyCls + ";").equals(mn.desc)
                    && mn.instructions != null) {
                InsnList il = new InsnList();
                LabelNode miss = new LabelNode();
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "VARIABLES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new TypeInsnNode(Opcodes.CHECKCAST, lazyCls));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new JumpInsnNode(Opcodes.IFNULL, miss));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new InsnNode(Opcodes.ARETURN));
                il.add(miss);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 3, new Object[] {self, "java/lang/String", lazyCls}, 0, new Object[0]));
                il.add(new TypeInsnNode(Opcodes.NEW, lazyCls));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new InsnNode(Opcodes.DCONST_0));
                il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, lazyCls, "<init>", "(Ljava/lang/String;D)V", false));
                il.add(new VarInsnNode(Opcodes.ASTORE, 2));
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "VARIABLES", "Ljava/util/Map;"));
                il.add(new VarInsnNode(Opcodes.ALOAD, 1));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.ALOAD, 2));
                il.add(new InsnNode(Opcodes.ARETURN));
                mn.instructions = il;
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patchedGetVar = true;
            }
        }
        if (!patchedSetMemo || !patchedGetVar) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds a batched Iris extended-format fast path at the entry of Emberbium's mixed-in
     * {@code ItemRenderer.renderBakedItemQuads}, collapsing per-quad {@code writer.push()},
     * {@code CapturedRenderingState}, and matrix/determinant work into one call per quad list.
     */
    public static byte[] transformItemRenderer(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String targetDesc = "(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                + "Lnet/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter;"
                + "Ljava/util/List;Lnet/minecraft/world/item/ItemStack;"
                + "Lnet/minecraft/client/color/item/ItemColor;II)V";
        final String helperDesc = targetDesc.substring(0, targetDesc.length() - 1) + "Z";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("renderBakedItemQuads".equals(mn.name) && targetDesc.equals(mn.desc) && mn.instructions != null) {
                LabelNode cont = new LabelNode();
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 3));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 4));
                pre.add(new VarInsnNode(Opcodes.ALOAD, 5));
                pre.add(new VarInsnNode(Opcodes.ILOAD, 6));
                pre.add(new VarInsnNode(Opcodes.ILOAD, 7));
                pre.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "tryRenderIrisBakedItemQuadsBatch", helperDesc, false));
                pre.add(new JumpInsnNode(Opcodes.IFEQ, cont));
                pre.add(new InsnNode(Opcodes.RETURN));
                pre.add(cont);
                pre.add(new FrameNode(
                        Opcodes.F_NEW, 8,
                        new Object[] {
                            cn.name,
                            "com/mojang/blaze3d/vertex/PoseStack$Pose",
                            "net/caffeinemc/mods/sodium/api/vertex/buffer/VertexBufferWriter",
                            "java/util/List",
                            "net/minecraft/world/item/ItemStack",
                            "net/minecraft/client/color/item/ItemColor",
                            Opcodes.INTEGER,
                            Opcodes.INTEGER
                        },
                        0, new Object[0]));
                mn.instructions.insert(pre);
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Optimizes {@code net.minecraft.world.entity.Entity}:
     * <ul>
     *   <li>Guards Horseman's {@code onGetBoundingBox} with {@code AbstractHorse && Boat} BEFORE
     *       querying {@code ForgeConfigSpec$BooleanValue.get()}.</li>
     *   <li>Adds a zero-allocation fast path at the top of {@code m_6783_} ({@code shouldRenderAtSqrDistance})
     *       when {@code entityJs$builder == null}, bypassing EntityJS's per-call
     *       {@code CallbackInfoReturnable} allocation so C2 can inline distance culling.</li>
     *   <li>Replaces EMF's per-entity {@code Object2FloatOpenHashMap} variable map allocations
     *       with lazy-allocated {@code EmfRenderPerf$FastEmfVarMap} (client-only when EMF is woven).</li>
     * </ul>
     */
    public static byte[] transformEntity(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String aabbDesc = "(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/AABB;";
        final String horseCls = "net/minecraft/world/entity/animal/horse/AbstractHorse";
        final String boatCls = "net/minecraft/world/entity/vehicle/Boat";
        final String o2fCls = "it/unimi/dsi/fastutil/objects/Object2FloatOpenHashMap";
        final String hashMapCls = "java/util/HashMap";
        final String fastVarCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf$FastEmfVarMap";
        boolean hasEntityJsBuilder = false;
        boolean hasEmfVarMap = false;
        for (FieldNode fn : cn.fields) {
            if ("entityJs$builder".equals(fn.name) && "Ljava/lang/Object;".equals(fn.desc)) {
                hasEntityJsBuilder = true;
            } else if ("emf$variableMap".equals(fn.name) && "Ljava/util/Map;".equals(fn.desc)) {
                hasEmfVarMap = true;
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            // 1. Horseman onGetBoundingBox guard
            if (mn.name != null
                    && mn.name.endsWith("$onGetBoundingBox")
                    && aabbDesc.equals(mn.desc)
                    && (mn.access & Opcodes.ACC_STATIC) == 0) {
                boolean hasHorsemanConfig = false;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode fin
                            && "io/github/mortuusars/horseman/Config$Common".equals(fin.owner)
                            && "HORSE_IN_BOAT".equals(fin.name)) {
                        hasHorsemanConfig = true;
                        break;
                    }
                }
                if (hasHorsemanConfig) {
                    LabelNode fastReturn = new LabelNode();
                    LabelNode proceed = new LabelNode();
                    InsnList pre = new InsnList();
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new TypeInsnNode(Opcodes.INSTANCEOF, horseCls));
                    pre.add(new JumpInsnNode(Opcodes.IFEQ, fastReturn));
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, cn.name, "m_20202_",
                            "()Lnet/minecraft/world/entity/Entity;", false));
                    pre.add(new TypeInsnNode(Opcodes.INSTANCEOF, boatCls));
                    pre.add(new JumpInsnNode(Opcodes.IFNE, proceed));
                    pre.add(fastReturn);
                    pre.add(new FrameNode(
                            Opcodes.F_NEW, 2,
                            new Object[] {cn.name, "net/minecraft/world/phys/AABB"},
                            0, new Object[0]));
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    pre.add(new InsnNode(Opcodes.ARETURN));
                    pre.add(proceed);
                    pre.add(new FrameNode(
                            Opcodes.F_NEW, 2,
                            new Object[] {cn.name, "net/minecraft/world/phys/AABB"},
                            0, new Object[0]));
                    mn.instructions.insert(pre);
                    patched = true;
                }
            }
            // 2. EntityJS shouldRenderAtSqrDistance (m_6783_) fast path when entityJs$builder == null
            if (hasEntityJsBuilder
                    && "m_6783_".equals(mn.name)
                    && "(D)Z".equals(mn.desc)
                    && (mn.access & Opcodes.ACC_STATIC) == 0) {
                boolean hasEntityJsHook = false;
                boolean hasOtherMixinHook = false;
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode min && cn.name.equals(min.owner)) {
                        if (min.name.contains("shouldRenderAtSqrDistance")) {
                            hasEntityJsHook = true;
                        } else if (min.name.startsWith("handler$")
                                || min.name.startsWith("redirect$")
                                || min.name.startsWith("modify")
                                || min.name.startsWith("wrapOperation$")) {
                            hasOtherMixinHook = true;
                        }
                    }
                }
                if (hasEntityJsHook && !hasOtherMixinHook) {
                    LabelNode slowPath = new LabelNode();
                    LabelNode notNan = new LabelNode();
                    LabelNode retFalse = new LabelNode();
                    InsnList pre = new InsnList();
                    // if (this.entityJs$builder != null) goto slowPath;
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "entityJs$builder", "Ljava/lang/Object;"));
                    pre.add(new JumpInsnNode(Opcodes.IFNONNULL, slowPath));
                    // double d0 = this.m_20191_().m_82309_();
                    pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    pre.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, cn.name, "m_20191_",
                            "()Lnet/minecraft/world/phys/AABB;", false));
                    pre.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL, "net/minecraft/world/phys/AABB", "m_82309_",
                            "()D", false));
                    pre.add(new VarInsnNode(Opcodes.DSTORE, 3));
                    // if (Double.isNaN(d0)) d0 = 1.0D;
                    pre.add(new VarInsnNode(Opcodes.DLOAD, 3));
                    pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "isNaN", "(D)Z", false));
                    pre.add(new JumpInsnNode(Opcodes.IFEQ, notNan));
                    pre.add(new InsnNode(Opcodes.DCONST_1));
                    pre.add(new VarInsnNode(Opcodes.DSTORE, 3));
                    pre.add(notNan);
                    pre.add(new FrameNode(
                            Opcodes.F_NEW, 3,
                            new Object[] {cn.name, Opcodes.DOUBLE, Opcodes.DOUBLE},
                            0, new Object[0]));
                    // d0 *= 64.0D * Entity.f_19846_; return distSqr < d0 * d0;
                    pre.add(new VarInsnNode(Opcodes.DLOAD, 3));
                    pre.add(new LdcInsnNode(64.0D));
                    pre.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "f_19846_", "D"));
                    pre.add(new InsnNode(Opcodes.DMUL));
                    pre.add(new InsnNode(Opcodes.DMUL));
                    pre.add(new VarInsnNode(Opcodes.DSTORE, 3));
                    pre.add(new VarInsnNode(Opcodes.DLOAD, 1));
                    pre.add(new VarInsnNode(Opcodes.DLOAD, 3));
                    pre.add(new VarInsnNode(Opcodes.DLOAD, 3));
                    pre.add(new InsnNode(Opcodes.DMUL));
                    pre.add(new InsnNode(Opcodes.DCMPG));
                    pre.add(new JumpInsnNode(Opcodes.IFGE, retFalse));
                    pre.add(new InsnNode(Opcodes.ICONST_1));
                    pre.add(new InsnNode(Opcodes.IRETURN));
                    pre.add(retFalse);
                    pre.add(new FrameNode(
                            Opcodes.F_NEW, 3,
                            new Object[] {cn.name, Opcodes.DOUBLE, Opcodes.DOUBLE},
                            0, new Object[0]));
                    pre.add(new InsnNode(Opcodes.ICONST_0));
                    pre.add(new InsnNode(Opcodes.IRETURN));
                    pre.add(slowPath);
                    pre.add(new FrameNode(
                            Opcodes.F_NEW, 2,
                            new Object[] {cn.name, Opcodes.DOUBLE},
                            0, new Object[0]));
                    mn.instructions.insert(pre);
                    patched = true;
                }
            }
            // 3. Replace EMF's per-entity variable map allocations (Object2FloatOpenHashMap in EMF 3.3+,
            //    HashMap in EMF 3.2.x) with FastEmfVarMap
            if (hasEmfVarMap
                    && ("<init>".equals(mn.name) || "emf$getVariableMap".equals(mn.name))) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof TypeInsnNode tin
                            && tin.getOpcode() == Opcodes.NEW
                            && (o2fCls.equals(tin.desc) || hashMapCls.equals(tin.desc))) {
                        String allocCls = tin.desc;
                        // Scan ahead up to 8 real insns for matching <init> + PUTFIELD emf$variableMap*
                        MethodInsnNode ctor = null;
                        FieldInsnNode putf = null;
                        AbstractInsnNode cur = nextRealInsn(tin);
                        for (int steps = 0; cur != null && steps < 8; steps++, cur = nextRealInsn(cur)) {
                            if (ctor == null
                                    && cur instanceof MethodInsnNode min
                                    && min.getOpcode() == Opcodes.INVOKESPECIAL
                                    && allocCls.equals(min.owner)
                                    && "<init>".equals(min.name)
                                    && ("()V".equals(min.desc) || "(Ljava/util/Map;)V".equals(min.desc))) {
                                ctor = min;
                            } else if (ctor != null
                                    && cur instanceof FieldInsnNode fin
                                    && fin.getOpcode() == Opcodes.PUTFIELD
                                    && cn.name.equals(fin.owner)
                                    && fin.name != null
                                    && fin.name.startsWith("emf$variableMap")) {
                                putf = fin;
                                break;
                            }
                        }
                        if (ctor != null && putf != null) {
                            tin.desc = fastVarCls;
                            ctor.owner = fastVarCls;
                            patched = true;
                        }
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Eliminates {@code Float.valueOf} boxing and empty-map hashing in EMF 3.2.4's
     * {@code EMFAnimationEntityContext.getEntityVariable(String, float)} and
     * {@code setEntityVariable(String, float)} by routing through
     * {@code EmfRenderPerf.getEntityVariableFast} / {@code setEntityVariableFast}.
     */
    public static byte[] transformEmfAnimationEntityContext(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String stateCls = "traben/entity_model_features/models/animation/state/EMFEntityRenderState";
        final String stateDesc = "L" + stateCls + ";";
        final String perfCls = "dev/sunlit/bettersunlit/client/EmfRenderPerf";
        boolean hasStateField = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("emfState".equals(fn.name)
                        && (fn.access & Opcodes.ACC_STATIC) != 0
                        && stateDesc.equals(fn.desc)) {
                    hasStateField = true;
                    break;
                }
            }
        }
        if (!hasStateField) {
            return null;
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_STATIC) == 0 || mn.instructions == null) {
                continue;
            }
            if ("getEntityVariable".equals(mn.name) && "(Ljava/lang/String;F)F".equals(mn.desc)) {
                InsnList il = new InsnList();
                LabelNode hasState = new LabelNode();
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "emfState", stateDesc));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasState));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new VarInsnNode(Opcodes.FLOAD, 1));
                il.add(new InsnNode(Opcodes.FRETURN));
                il.add(hasState);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {"java/lang/String", Opcodes.FLOAT},
                        1, new Object[] {stateCls}));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, stateCls, "variableMap", "()Ljava/util/Map;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.FLOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "getEntityVariableFast",
                        "(Ljava/util/Map;Ljava/lang/String;F)F", false));
                il.add(new InsnNode(Opcodes.FRETURN));
                mn.instructions.clear();
                mn.instructions.add(il);
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            } else if ("setEntityVariable".equals(mn.name) && "(Ljava/lang/String;F)V".equals(mn.desc)) {
                InsnList il = new InsnList();
                LabelNode hasState = new LabelNode();
                il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "emfState", stateDesc));
                il.add(new InsnNode(Opcodes.DUP));
                il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasState));
                il.add(new InsnNode(Opcodes.POP));
                il.add(new InsnNode(Opcodes.RETURN));
                il.add(hasState);
                il.add(new FrameNode(
                        Opcodes.F_FULL, 2,
                        new Object[] {"java/lang/String", Opcodes.FLOAT},
                        1, new Object[] {stateCls}));
                il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, stateCls, "variableMap", "()Ljava/util/Map;", true));
                il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                il.add(new VarInsnNode(Opcodes.FLOAD, 1));
                il.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, perfCls, "setEntityVariableFast",
                        "(Ljava/util/Map;Ljava/lang/String;F)V", false));
                il.add(new InsnNode(Opcodes.RETURN));
                mn.instructions.clear();
                mn.instructions.add(il);
                if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Replaces O(N^2) {@code ArrayList.Iterator.remove()} compaction in ParticleRain's
     * {@code WindManager.tick(ClientLevel)} with an O(N) in-place write-cursor compaction and
     * single {@code subList(w, size).clear()}.
     */
    public static byte[] transformParticleRainWindManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String self = PARTICLERAIN_WIND_MGR_CLS;
        final String trackedCls = self + "$Tracked";
        final String levelCls = MC_CLIENT_LEVEL_CLS;
        final String partCls = "net/minecraft/client/particle/Particle";
        final String dataCls = "pigcart/particlerain/config/ParticleData";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"tick".equals(mn.name)
                    || !("(L" + levelCls + ";)V").equals(mn.desc)
                    || mn.instructions == null) {
                continue;
            }
            InsnList il = new InsnList();
            LabelNode loop = new LabelNode();
            LabelNode nextIter = new LabelNode();
            LabelNode afterLoop = new LabelNode();
            LabelNode done = new LabelNode();

            // List list = TRACKED; int size = list.size(); if (size == 0) return;
            il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "TRACKED", "Ljava/util/List;"));
            il.add(new VarInsnNode(Opcodes.ASTORE, 1));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            il.add(new VarInsnNode(Opcodes.ISTORE, 2));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IFEQ, done));

            // int w = 0; for (int i = 0; i < size; i++)
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 3));
            il.add(new InsnNode(Opcodes.ICONST_0));
            il.add(new VarInsnNode(Opcodes.ISTORE, 4));

            il.add(loop);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {levelCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, afterLoop));

            // Tracked t = (Tracked) list.get(i);
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            il.add(new TypeInsnNode(Opcodes.CHECKCAST, trackedCls));
            il.add(new VarInsnNode(Opcodes.ASTORE, 5));

            // if (!t.particle.m_107276_() || ++t.ticks > 6000) continue;
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, trackedCls, "particle", "L" + partCls + ";"));
            il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, partCls, "m_107276_", "()Z", false));
            il.add(new JumpInsnNode(Opcodes.IFEQ, nextIter));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, trackedCls, "ticks", "I"));
            il.add(new InsnNode(Opcodes.ICONST_1));
            il.add(new InsnNode(Opcodes.IADD));
            il.add(new InsnNode(Opcodes.DUP_X1));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, trackedCls, "ticks", "I"));
            il.add(new IntInsnNode(Opcodes.SIPUSH, 6000));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGT, nextIter));

            // if (w != i) list.set(w, t); w++;
            LabelNode skipSet = new LabelNode();
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 4));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, skipSet));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "set", "(ILjava/lang/Object;)Ljava/lang/Object;", true));
            il.add(new InsnNode(Opcodes.POP));
            il.add(skipSet);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 6,
                    new Object[] {levelCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, trackedCls},
                    0, new Object[0]));
            il.add(new IincInsnNode(3, 1));

            // applyWind(t.particle, t.data, level);
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, trackedCls, "particle", "L" + partCls + ";"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 5));
            il.add(new FieldInsnNode(Opcodes.GETFIELD, trackedCls, "data", "L" + dataCls + ";"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, self, "applyWind",
                    "(L" + partCls + ";L" + dataCls + ";L" + levelCls + ";)V", false));

            il.add(nextIter);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 5,
                    new Object[] {levelCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new IincInsnNode(4, 1));
            il.add(new JumpInsnNode(Opcodes.GOTO, loop));

            // if (w < size) list.subList(w, size).clear();
            il.add(afterLoop);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 4,
                    new Object[] {levelCls, "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "subList", "(II)Ljava/util/List;", true));
            il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true));

            il.add(done);
            il.add(new FrameNode(
                    Opcodes.F_FULL, 3,
                    new Object[] {levelCls, "java/util/List", Opcodes.INTEGER},
                    0, new Object[0]));
            il.add(new InsnNode(Opcodes.RETURN));

            mn.instructions = il;
            if (mn.tryCatchBlocks != null) mn.tryCatchBlocks.clear();
            if (mn.localVariables != null) mn.localVariables.clear();
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Adds a 512-slot Render-thread ring pool ({@code sunlit$obtain}) to GeckoLib's
     * {@code AnimationPoint}, eliminating ~430MB of ephemeral per-frame record allocations.
     */
    public static byte[] transformGeckoLibAnimationPoint(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String self = GECKOLIB_ANIM_POINT_CLS;
        final String selfDesc = "L" + self + ";";
        final String arrDesc = "[" + selfDesc;
        final String kfDesc = "Lsoftware/bernie/geckolib/core/keyframe/Keyframe;";
        final String ctorDesc = "(" + kfDesc + "DDDD)V";
        final String obtainDesc = "(" + kfDesc + "DDDD)" + selfDesc;
        for (FieldNode fn : cn.fields) {
            if ("SUNLIT_RING".equals(fn.name)) {
                return null;
            }
        }
        int clearedFields = 0;
        for (FieldNode fn : cn.fields) {
            if ("keyFrame".equals(fn.name)
                    || "currentTick".equals(fn.name)
                    || "transitionLength".equals(fn.name)
                    || "animationStartValue".equals(fn.name)
                    || "animationEndValue".equals(fn.name)) {
                fn.access &= ~Opcodes.ACC_FINAL;
                clearedFields++;
            }
        }
        if (clearedFields != 5) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "SUNLIT_RING", arrDesc, null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "sunlitRingIdx", "I", null, null));
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "sunlitRenderThread", "Ljava/lang/Thread;", null, null));

        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name) && "()V".equals(mn.desc)) {
                clinit = mn;
                break;
            }
        }
        InsnList initRing = new InsnList();
        initRing.add(new IntInsnNode(Opcodes.SIPUSH, 512));
        initRing.add(new TypeInsnNode(Opcodes.ANEWARRAY, self));
        initRing.add(new FieldInsnNode(Opcodes.PUTSTATIC, self, "SUNLIT_RING", arrDesc));
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(initRing);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            cn.methods.add(clinit);
        } else {
            clinit.instructions.insert(initRing);
        }

        MethodNode obtain = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sunlit$obtain", obtainDesc, null, null);
        InsnList il = obtain.instructions;
        LabelNode useRing = new LabelNode();
        LabelNode allocFresh = new LabelNode();
        LabelNode allocSlot = new LabelNode();

        // Thread cur = Thread.currentThread(); Thread rt = sunlitRenderThread;
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 9));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "sunlitRenderThread", "Ljava/lang/Thread;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 10));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new VarInsnNode(Opcodes.ALOAD, 10));
        il.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, useRing));
        il.add(new VarInsnNode(Opcodes.ALOAD, 10));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, allocFresh));
        il.add(new LdcInsnNode("Render thread"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName", "()Ljava/lang/String;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
        il.add(new JumpInsnNode(Opcodes.IFEQ, allocFresh));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, self, "sunlitRenderThread", "Ljava/lang/Thread;"));

        // useRing:
        il.add(useRing);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {"software/bernie/geckolib/core/keyframe/Keyframe",
                        Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE},
                0, new Object[0]));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "sunlitRingIdx", "I"));
        il.add(new VarInsnNode(Opcodes.ISTORE, 9));
        il.add(new VarInsnNode(Opcodes.ILOAD, 9));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.IADD));
        il.add(new IntInsnNode(Opcodes.SIPUSH, 511));
        il.add(new InsnNode(Opcodes.IAND));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, self, "sunlitRingIdx", "I"));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "SUNLIT_RING", arrDesc));
        il.add(new VarInsnNode(Opcodes.ASTORE, 10));
        il.add(new VarInsnNode(Opcodes.ALOAD, 10));
        il.add(new VarInsnNode(Opcodes.ILOAD, 9));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ASTORE, 11));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new JumpInsnNode(Opcodes.IFNULL, allocSlot));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "keyFrame", kfDesc));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new VarInsnNode(Opcodes.DLOAD, 1));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "currentTick", "D"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new VarInsnNode(Opcodes.DLOAD, 3));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "transitionLength", "D"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new VarInsnNode(Opcodes.DLOAD, 5));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "animationStartValue", "D"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new VarInsnNode(Opcodes.DLOAD, 7));
        il.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "animationEndValue", "D"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new InsnNode(Opcodes.ARETURN));

        // allocSlot:
        il.add(allocSlot);
        il.add(new FrameNode(
                Opcodes.F_FULL, 7,
                new Object[] {"software/bernie/geckolib/core/keyframe/Keyframe",
                        Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE,
                        Opcodes.INTEGER, arrDesc},
                0, new Object[0]));
        il.add(new TypeInsnNode(Opcodes.NEW, self));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.DLOAD, 1));
        il.add(new VarInsnNode(Opcodes.DLOAD, 3));
        il.add(new VarInsnNode(Opcodes.DLOAD, 5));
        il.add(new VarInsnNode(Opcodes.DLOAD, 7));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, self, "<init>", ctorDesc, false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 11));
        il.add(new VarInsnNode(Opcodes.ALOAD, 10));
        il.add(new VarInsnNode(Opcodes.ILOAD, 9));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new InsnNode(Opcodes.AASTORE));
        il.add(new VarInsnNode(Opcodes.ALOAD, 11));
        il.add(new InsnNode(Opcodes.ARETURN));

        // allocFresh:
        il.add(allocFresh);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {"software/bernie/geckolib/core/keyframe/Keyframe",
                        Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE, Opcodes.DOUBLE},
                0, new Object[0]));
        il.add(new TypeInsnNode(Opcodes.NEW, self));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.DLOAD, 1));
        il.add(new VarInsnNode(Opcodes.DLOAD, 3));
        il.add(new VarInsnNode(Opcodes.DLOAD, 5));
        il.add(new VarInsnNode(Opcodes.DLOAD, 7));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, self, "<init>", ctorDesc, false));
        il.add(new InsnNode(Opcodes.ARETURN));

        cn.methods.add(obtain);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Rewrites {@code new AnimationPoint(kf, tick, len, start, end)} sites in GeckoLib
     * {@code AnimationController} and {@code BoneAnimationQueue} to call
     * {@code AnimationPoint.sunlit$obtain(...)}.
     */
    public static byte[] transformGeckoLibAnimationPointCallers(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String apCls = GECKOLIB_ANIM_POINT_CLS;
        final String kfDesc = "Lsoftware/bernie/geckolib/core/keyframe/Keyframe;";
        final String ctorDesc = "(" + kfDesc + "DDDD)V";
        final String obtainDesc = "(" + kfDesc + "DDDD)L" + apCls + ";";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof TypeInsnNode tin
                        && tin.getOpcode() == Opcodes.NEW
                        && apCls.equals(tin.desc)) {
                    AbstractInsnNode dup = nextRealInsn(tin);
                    if (dup == null || dup.getOpcode() != Opcodes.DUP) {
                        continue;
                    }
                    // Find matching INVOKESPECIAL <init> with no nested NEW or control-flow labels
                    MethodInsnNode init = null;
                    boolean safe = true;
                    for (AbstractInsnNode cur = dup.getNext(); cur != null; cur = cur.getNext()) {
                        if (cur instanceof LabelNode || cur instanceof FrameNode || cur instanceof JumpInsnNode) {
                            safe = false;
                            break;
                        }
                        if (cur instanceof TypeInsnNode nested && nested.getOpcode() == Opcodes.NEW) {
                            safe = false;
                            break;
                        }
                        if (cur instanceof MethodInsnNode min
                                && min.getOpcode() == Opcodes.INVOKESPECIAL
                                && apCls.equals(min.owner)
                                && "<init>".equals(min.name)
                                && ctorDesc.equals(min.desc)) {
                            init = min;
                            break;
                        }
                    }
                    if (safe && init != null) {
                        mn.instructions.remove(tin);
                        mn.instructions.remove(dup);
                        mn.instructions.set(init, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, apCls, "sunlit$obtain", obtainDesc, false));
                        patched = true;
                    }
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Smooths Embeddium {@code ChunkBuilder.getSchedulingBudget()} during the first 5s post-join
     * and whenever chunk uploads are backlogged via {@code StartupLoadPerf.clampChunkSchedulingBudget}.
     */
    public static byte[] transformSodiumChunkBuilder(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasQueue = false;
        String queueDesc = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkJobQueue;";
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("queue".equals(fn.name) && queueDesc.equals(fn.desc)) {
                    hasQueue = true;
                    break;
                }
            }
        }
        if (!hasQueue) {
            return null;
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"getSchedulingBudget".equals(mn.name) || !"()I".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.IRETURN) {
                    InsnList clamp = new InsnList();
                    clamp.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    clamp.add(new FieldInsnNode(Opcodes.GETFIELD, SODIUM_CHUNK_BUILDER_CLS, "queue", queueDesc));
                    clamp.add(new MethodInsnNode(
                            Opcodes.INVOKEVIRTUAL,
                            "me/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkJobQueue",
                            "size",
                            "()I",
                            false));
                    clamp.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "dev/sunlit/bettersunlit/client/StartupLoadPerf",
                            "clampChunkSchedulingBudget",
                            "(II)I",
                            false));
                    mn.instructions.insertBefore(insn, clamp);
                    patched = true;
                }
            }
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caps per-frame chunk mesh uploads inside {@code RenderSectionManager.uploadChunks()} using
     * {@code StartupLoadPerf.getChunkUploadBudget()} while leaving {@code destroy()}'s
     * {@code collectChunkBuildResults()} completely uncapped so no native buffers ever leak.
     */
    public static byte[] transformSodiumRenderSectionManager(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasBuildResults = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("buildResults".equals(fn.name)
                        && "Ljava/util/concurrent/ConcurrentLinkedDeque;".equals(fn.desc)) {
                    hasBuildResults = true;
                    break;
                }
            }
        }
        if (!hasBuildResults) {
            return null;
        }
        for (MethodNode mn : cn.methods) {
            if ("sunlit$collectChunkBuildResultsCapped".equals(mn.name)) {
                return null;
            }
        }
        boolean patchedUpload = false;
        for (MethodNode mn : cn.methods) {
            if (!"uploadChunks".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && SODIUM_RENDER_SECTION_MGR_CLS.equals(min.owner)
                        && "collectChunkBuildResults".equals(min.name)
                        && "()Ljava/util/ArrayList;".equals(min.desc)) {
                    min.name = "sunlit$collectChunkBuildResultsCapped";
                    patchedUpload = true;
                }
            }
            break;
        }
        if (!patchedUpload) {
            return null;
        }

        final String self = SODIUM_RENDER_SECTION_MGR_CLS;
        final String dequeCls = "java/util/concurrent/ConcurrentLinkedDeque";
        final String jobResultCls = "me/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkJobResult";
        final String buildOutputCls = "me/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput";
        final String startupPerfCls = "dev/sunlit/bettersunlit/client/StartupLoadPerf";

        MethodNode capped = new MethodNode(
                Opcodes.ACC_PRIVATE,
                "sunlit$collectChunkBuildResultsCapped",
                "()Ljava/util/ArrayList;",
                null,
                null);
        InsnList il = capped.instructions;
        LabelNode loopHead = new LabelNode();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();
        LabelNode suppressExisting = new LabelNode();
        LabelNode afterLoop = new LabelNode();
        LabelNode hasBacklogFalse = new LabelNode();
        LabelNode setBacklog = new LabelNode();
        LabelNode cleanLoop = new LabelNode();
        LabelNode cleanDone = new LabelNode();
        LabelNode returnOk = new LabelNode();

        capped.tryCatchBlocks.add(new TryCatchBlockNode(
                tryStart, tryEnd, catchHandler, "java/lang/RuntimeException"));

        // int cap = StartupLoadPerf.getChunkUploadBudget();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, startupPerfCls, "getChunkUploadBudget", "()I", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 1));
        // ArrayList results = new ArrayList();
        il.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        // RuntimeException suppressed = null;
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // while (results.size() < cap)
        il.add(loopHead);
        il.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "size", "()I", false));
        il.add(new VarInsnNode(Opcodes.ILOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, afterLoop));

        // ChunkJobResult job = (ChunkJobResult) this.buildResults.poll();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "buildResults", "L" + dequeCls + ";"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, dequeCls, "poll", "()Ljava/lang/Object;", false));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, jobResultCls));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        il.add(new JumpInsnNode(Opcodes.IFNULL, afterLoop));

        // try { results.add((ChunkBuildOutput) job.unwrap()); }
        il.add(tryStart);
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, jobResultCls, "unwrap", "()Ljava/lang/Object;", false));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, buildOutputCls));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(tryEnd);
        il.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        // catch (RuntimeException e)
        il.add(catchHandler);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException", jobResultCls},
                1, new Object[] {"java/lang/RuntimeException"}));
        il.add(new VarInsnNode(Opcodes.ASTORE, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, suppressExisting));
        il.add(new VarInsnNode(Opcodes.ALOAD, 5));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        il.add(suppressExisting);
        il.add(new FrameNode(
                Opcodes.F_FULL, 6,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException",
                        jobResultCls, "java/lang/RuntimeException"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 5));
        il.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/RuntimeException", "addSuppressed", "(Ljava/lang/Throwable;)V", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopHead));

        // StartupLoadPerf.setChunkUploadBacklogged(this.buildResults.peek() != null);
        il.add(afterLoop);
        il.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(Opcodes.GETFIELD, self, "buildResults", "L" + dequeCls + ";"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, dequeCls, "peek", "()Ljava/lang/Object;", false));
        il.add(new JumpInsnNode(Opcodes.IFNULL, hasBacklogFalse));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new JumpInsnNode(Opcodes.GOTO, setBacklog));

        il.add(hasBacklogFalse);
        il.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException"},
                0, new Object[0]));
        il.add(new InsnNode(Opcodes.ICONST_0));

        il.add(setBacklog);
        il.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException"},
                1, new Object[] {Opcodes.INTEGER}));
        il.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, startupPerfCls, "setChunkUploadBacklogged", "(Z)V", false));

        // if (suppressed != null) { for (ChunkBuildOutput out : results) out.delete(); throw suppressed; }
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOk));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "iterator", "()Ljava/util/Iterator;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));

        il.add(cleanLoop);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException", "java/util/Iterator"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        il.add(new JumpInsnNode(Opcodes.IFEQ, cleanDone));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, buildOutputCls));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, buildOutputCls, "delete", "()V", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, cleanLoop));

        il.add(cleanDone);
        il.add(new FrameNode(
                Opcodes.F_FULL, 5,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException", "java/util/Iterator"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ATHROW));

        il.add(returnOk);
        il.add(new FrameNode(
                Opcodes.F_FULL, 4,
                new Object[] {self, Opcodes.INTEGER, "java/util/ArrayList", "java/lang/RuntimeException"},
                0, new Object[0]));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new InsnNode(Opcodes.ARETURN));

        cn.methods.add(capped);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Caches the immutable baked {@code ModelPart} in SmallShips {@code CannonModel.<init>()} so
     * {@code ShipRenderer.renderCannon()} does not rebuild {@code LayerDefinition} + {@code ModelPart}
     * (~100+ MB/min) on every rendered ship cannon every frame.
     */
    public static byte[] transformSmallShipsCannonModel(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        final String self = SMALLSHIPS_CANNON_MODEL_CLS;
        final String mpDesc = "Lnet/minecraft/client/model/geom/ModelPart;";
        boolean hasCannonField = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("sunlit$cachedCannon".equals(fn.name)) {
                    return null;
                }
                if ("Cannon".equals(fn.name) && mpDesc.equals(fn.desc)) {
                    hasCannonField = true;
                }
            }
        }
        if (!hasCannonField) {
            return null;
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            AbstractInsnNode superInit = null;
            FieldInsnNode putCannon = null;
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (superInit == null
                        && insn instanceof MethodInsnNode min
                        && min.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(min.name)
                        && "()V".equals(min.desc)) {
                    superInit = min;
                } else if (insn instanceof FieldInsnNode fin
                        && fin.getOpcode() == Opcodes.PUTFIELD
                        && self.equals(fin.owner)
                        && "Cannon".equals(fin.name)
                        && mpDesc.equals(fin.desc)) {
                    putCannon = fin;
                }
            }
            if (superInit == null || putCannon == null) {
                continue;
            }
            LabelNode missLabel = new LabelNode();
            InsnList fast = new InsnList();
            fast.add(new FieldInsnNode(Opcodes.GETSTATIC, self, "sunlit$cachedCannon", mpDesc));
            fast.add(new VarInsnNode(Opcodes.ASTORE, 1));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new JumpInsnNode(Opcodes.IFNULL, missLabel));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
            fast.add(new VarInsnNode(Opcodes.ALOAD, 1));
            fast.add(new FieldInsnNode(Opcodes.PUTFIELD, self, "Cannon", mpDesc));
            fast.add(new InsnNode(Opcodes.RETURN));
            fast.add(missLabel);
            fast.add(new FrameNode(
                    Opcodes.F_FULL, 2,
                    new Object[] {self, "net/minecraft/client/model/geom/ModelPart"},
                    0, new Object[0]));
            mn.instructions.insert(superInit, fast);

            InsnList storeCache = new InsnList();
            storeCache.add(new InsnNode(Opcodes.DUP));
            storeCache.add(new FieldInsnNode(Opcodes.PUTSTATIC, self, "sunlit$cachedCannon", mpDesc));
            mn.instructions.insertBefore(putCannon, storeCache);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "sunlit$cachedCannon",
                mpDesc,
                null,
                null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformCrashReportPreload(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if (mn.name != null && mn.name.contains("sunlit$enterCrashReportPreload")) {
                    return null;
                }
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"m_127529_".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof MethodInsnNode min && PAYLOAD_CLS.equals(min.owner)) {
                    return null;
                }
            }
            mn.instructions.insert(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, PAYLOAD_CLS, "enterCrashPreload", "()V", false));
            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                int op = insn.getOpcode();
                if (op == Opcodes.RETURN || op == Opcodes.ATHROW) {
                    mn.instructions.insertBefore(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, PAYLOAD_CLS, "exitCrashPreload", "()V", false));
                }
            }
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformSystemReportPreload(Module module, byte[] bytes) {
        ensureReadsExtraPayload(module);
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if (mn.name != null && mn.name.contains("sunlit$deferPreloadHardwareProbe")) {
                    return null;
                }
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (!"m_143564_".equals(mn.name) || !"()V".equals(mn.desc) || mn.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn instanceof FrameNode) {
                    return null;
                }
                if (insn instanceof MethodInsnNode min && PAYLOAD_CLS.equals(min.owner)) {
                    return null;
                }
            }
            LabelNode cont = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAYLOAD_CLS, "isInCrashPreload", "()Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, cont));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(cont);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            mn.instructions.insert(guard);
            patched = true;
            break;
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformForgeGameData(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if (mn.name != null
                        && (mn.name.contains("sunlit$runPostConstructHooks")
                                || mn.name.contains("sunlit$scheduleRegistryWindowPreload"))) {
                    return null;
                }
            }
        }
        final String statePerf = "dev/sunlit/bettersunlit/perf/StateDefinitionPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || !"()V".equals(mn.desc)) {
                continue;
            }
            if ("unfreezeData".equals(mn.name)) {
                boolean already = false;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min && statePerf.equals(min.owner)) {
                        already = true;
                        break;
                    }
                }
                if (!already) {
                    mn.instructions.insert(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, statePerf, "runPostConstructHooks", "()V", false));
                    patched = true;
                }
            } else if ("postRegisterEvents".equals(mn.name)) {
                boolean already = false;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min && statePerf.equals(min.owner)) {
                        already = true;
                        break;
                    }
                }
                if (!already) {
                    mn.instructions.insert(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, statePerf, "beginRegistryMapReuse", "()V", false));
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        int op = insn.getOpcode();
                        if (op == Opcodes.RETURN || op == Opcodes.ATHROW) {
                            mn.instructions.insertBefore(insn, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, statePerf, "endRegistryMapReuse", "()V", false));
                        }
                    }
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformMysticalTreeLore(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if (mn.name != null && mn.name.contains("sunlit$deferStartupHttpDownloads")) {
                    return null;
                }
            }
        }
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("init".equals(mn.name) && "()V".equals(mn.desc) && (mn.access & Opcodes.ACC_STATIC) != 0) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                mn.maxStack = 0;
                mn.maxLocals = 0;
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformKubeJsGeneratedResourcePack(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasPackType = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                // Skip when client PerfKubeJsPackMixin already instrumented this class.
                if ("sunlit$readyGenerated".equals(fn.name)) {
                    return null;
                }
                if ("packType".equals(fn.name) && "Lnet/minecraft/server/packs/PackType;".equals(fn.desc)) {
                    hasPackType = true;
                }
            }
        }
        if (!hasPackType) {
            return null;
        }
        final String perfCls = "dev/sunlit/bettersunlit/perf/ServerBootPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("m_8031_".equals(mn.name)
                    && "(Lnet/minecraft/server/packs/PackType;Ljava/lang/String;Ljava/lang/String;Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new FieldInsnNode(
                        Opcodes.GETFIELD, KJS_GEN_PACK_CLS, "packType", "Lnet/minecraft/server/packs/PackType;"));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "listGeneratedResources",
                        "(Ldev/latvian/mods/kubejs/script/data/GeneratedResourcePack;"
                                + "Lnet/minecraft/server/packs/PackType;"
                                + "Lnet/minecraft/server/packs/PackType;"
                                + "Ljava/lang/String;Ljava/lang/String;"
                                + "Lnet/minecraft/server/packs/PackResources$ResourceOutput;)V",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                patched = true;
            } else if ("scanForInvalidFiles".equals(mn.name)
                    && "(Ljava/lang/String;Ljava/nio/file/Path;)V".equals(mn.desc)
                    && (mn.access & Opcodes.ACC_STATIC) != 0) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "scanForInvalidFilesAsync",
                        "(Ljava/lang/String;Ljava/nio/file/Path;)V",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                patched = true;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformToolActionIngredient(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasField = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("toolAction".equals(fn.name) && "Lnet/minecraftforge/common/ToolAction;".equals(fn.desc)) {
                    hasField = true;
                    break;
                }
            }
        }
        if (!hasField) {
            return null;
        }
        final String perfCls = "dev/sunlit/bettersunlit/perf/ServerBootPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)
                    && "(Lnet/minecraftforge/common/ToolAction;)V".equals(mn.desc)
                    && mn.instructions != null) {
                boolean callsIngredientInit = false;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min
                            && min.getOpcode() == Opcodes.INVOKESPECIAL
                            && "net/minecraft/world/item/crafting/Ingredient".equals(min.owner)
                            && "<init>".equals(min.name)
                            && "(Ljava/util/stream/Stream;)V".equals(min.desc)) {
                        callsIngredientInit = true;
                        break;
                    }
                }
                if (!callsIngredientInit) {
                    continue;
                }
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "toolActionValuesStream",
                        "(Lnet/minecraftforge/common/ToolAction;)Ljava/util/stream/Stream;",
                        false));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/world/item/crafting/Ingredient",
                        "<init>",
                        "(Ljava/util/stream/Stream;)V",
                        false));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        FD_TOOL_ACTION_ING_CLS,
                        "toolAction",
                        "Lnet/minecraftforge/common/ToolAction;"));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformLootJsByPattern(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean hasPattern = false;
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if ("pattern".equals(fn.name) && "Ljava/util/regex/Pattern;".equals(fn.desc)) {
                    hasPattern = true;
                    break;
                }
            }
        }
        if (!hasPattern) {
            return null;
        }
        final String perfCls = "dev/sunlit/bettersunlit/perf/ServerBootPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("test".equals(mn.name)
                    && "(Lnet/minecraft/resources/ResourceLocation;)Z".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new FieldInsnNode(
                        Opcodes.GETFIELD, LOOTJS_RL_PATTERN_CLS, "pattern", "Ljava/util/regex/Pattern;"));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "testRegexResourceLocation",
                        "(Ljava/util/regex/Pattern;Lnet/minecraft/resources/ResourceLocation;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                patched = true;
                break;
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformKubeJsTagEventJS(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String perfCls = "dev/sunlit/bettersunlit/perf/ServerBootPerf";
        final String fieldName = "sunlit$tagIndex";
        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if (fieldName.equals(fn.name)) {
                    return null;
                }
            }
        }
        boolean patchedGet = false;
        boolean patchedRemoveAll = false;
        for (MethodNode mn : cn.methods) {
            if ("get".equals(mn.name)
                    && "(Lnet/minecraft/resources/ResourceLocation;)Ldev/latvian/mods/kubejs/server/tag/TagWrapper;".equals(mn.desc)
                    && mn.instructions != null) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.ARETURN) {
                        InsnList hook = new InsnList();
                        hook.add(new InsnNode(Opcodes.DUP));
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        hook.add(new FieldInsnNode(Opcodes.GETFIELD, KJS_TAG_EVENT_CLS, fieldName, "Ljava/lang/Object;"));
                        hook.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                perfCls,
                                "onTagWrapperTouched",
                                "(Ldev/latvian/mods/kubejs/server/tag/TagWrapper;Ljava/lang/Object;)V",
                                false));
                        mn.instructions.insertBefore(insn, hook);
                        patchedGet = true;
                    }
                }
            } else if ("removeAllTagsFrom".equals(mn.name)
                    && "([Ljava/lang/Object;)V".equals(mn.desc)
                    && mn.instructions != null) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, KJS_TAG_EVENT_CLS, fieldName, "Ljava/lang/Object;"));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "removeAllTagsFrom",
                        "(Ldev/latvian/mods/kubejs/server/tag/TagEventJS;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                        false));
                mn.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, KJS_TAG_EVENT_CLS, fieldName, "Ljava/lang/Object;"));
                mn.instructions.add(new InsnNode(Opcodes.RETURN));
                patchedRemoveAll = true;
            }
        }
        if (!patchedGet || !patchedRemoveAll) {
            return null;
        }
        cn.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT,
                fieldName,
                "Ljava/lang/Object;",
                null,
                null));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static byte[] transformAliAbstractServer(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        final String perfCls = "dev/sunlit/bettersunlit/perf/ServerBootPerf";
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("predicateItem".equals(mn.name)
                    && "(Lcom/yanny/ali/api/ILootModifier;Ljava/util/List;)Z".equals(mn.desc)
                    && (mn.access & Opcodes.ACC_STATIC) != 0) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                mn.localVariables = null;
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                mn.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        perfCls,
                        "aliPredicateItem",
                        "(Ljava/lang/Object;Ljava/util/List;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));
                patched = true;
            } else if ("readLootTables".equals(mn.name)
                    && "(Lnet/minecraft/world/level/storage/loot/LootDataManager;)V".equals(mn.desc)
                    && mn.instructions != null) {
                boolean already = false;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode min && perfCls.equals(min.owner)) {
                        already = true;
                        break;
                    }
                }
                if (!already) {
                    mn.instructions.insert(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, perfCls, "enterAliRead", "()V", false));
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        int op = insn.getOpcode();
                        if (op == Opcodes.RETURN || op == Opcodes.ATHROW) {
                            mn.instructions.insertBefore(insn, new MethodInsnNode(
                                    Opcodes.INVOKESTATIC, perfCls, "exitAliRead", "()V", false));
                        }
                    }
                    patched = true;
                }
            }
        }
        if (!patched) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static Object asmFrameType(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
            case Type.FLOAT -> Opcodes.FLOAT;
            case Type.LONG -> Opcodes.LONG;
            case Type.DOUBLE -> Opcodes.DOUBLE;
            case Type.ARRAY -> t.getDescriptor();
            default -> t.getInternalName();
        };
    }

    private static AbstractInsnNode prevRealInsn(AbstractInsnNode node) {
        AbstractInsnNode cur = node == null ? null : node.getPrevious();
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getPrevious();
        }
        return cur;
    }
}
