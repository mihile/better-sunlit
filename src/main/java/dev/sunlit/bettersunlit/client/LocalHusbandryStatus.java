package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.husbandry.AnimalStatusSync;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Records only successful harvests observed after this player's own interaction. */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class LocalHusbandryStatus {
    private static final ResourceLocation MILK_SOUND = new ResourceLocation("minecraft", "entity.cow.milk");
    private static final ResourceLocation SHEAR_SOUND = new ResourceLocation("minecraft", "entity.sheep.shear");
    private static final int HARVEST_SOUND_WINDOW = 16;
    private static final Map<UUID, Harvest> OBSERVED = new HashMap<>();
    private static ClientLevel currentLevel;
    private static Interaction pending;

    private LocalHusbandryStatus() {}

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteractSpecific event) {
        observeInteraction(event, event.getTarget());
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract event) {
        observeInteraction(event, event.getTarget());
    }

    private static void observeInteraction(PlayerInteractEvent event, Entity target) {
        Minecraft client = Minecraft.m_91087_();
        ClientLevel level = client.f_91073_;
        if (level == null || event.getLevel() != level || event.getEntity() != client.f_91074_
                || event.getHand() != InteractionHand.MAIN_HAND || !(target instanceof LivingEntity living)
                || living.m_6162_()) return;
        syncLevel(level);
        ResourceLocation tool = ForgeRegistries.ITEMS.getKey(event.getItemStack().m_41720_());
        boolean milk = AnimalStatusSync.MILK_PAIL_ID.equals(tool) && AnimalStatusSync.isMilkable(living);
        boolean magic = AnimalStatusSync.MAGIC_SHEARS_ID.equals(tool) && AnimalStatusSync.isHusbandry(living);
        if (milk || magic) {
            pending = new Interaction(target.m_20148_(), event.getEntity().m_20182_(), currentLevel.m_46467_(), milk);
        }
    }

    @SubscribeEvent
    public static void sound(PlayLevelSoundEvent.AtPosition event) {
        Interaction action = pending;
        if (action == null || currentLevel == null || event.getLevel() != currentLevel || event.getSound() == null
                || event.getSource() != SoundSource.BLOCKS || currentLevel.m_46467_() - action.tick > HARVEST_SOUND_WINDOW
                || event.getPosition().m_82557_(action.playerPos) > 0.25D) return;
        // /playsound can send a direct SoundEvent holder outside the sound registry.
        ResourceLocation sound = event.getSound().m_203334_().m_11660_();
        if (!(action.milk ? MILK_SOUND : SHEAR_SOUND).equals(sound)) return;
        long time = Math.abs(currentLevel.m_46468_());
        if (OBSERVED.size() >= 512) {
            long day = Math.floorDiv(time, AnimalStatusSync.TICKS_PER_DAY);
            OBSERVED.values().removeIf(s -> Math.max(s.milk, s.magic) / AnimalStatusSync.TICKS_PER_DAY < day - 2);
            if (OBSERVED.size() >= 512 && !OBSERVED.containsKey(action.entityId)) return;
        }
        Harvest harvest = OBSERVED.getOrDefault(action.entityId, new Harvest(-1, -1));
        OBSERVED.put(action.entityId, action.milk ? new Harvest(time, harvest.magic) : new Harvest(harvest.milk, time));
        pending = null;
    }

    static long lastHarvest(ClientLevel level, LivingEntity animal, boolean milk) {
        syncLevel(level);
        Harvest harvest = OBSERVED.get(animal.m_20148_());
        return harvest == null ? -1 : milk ? harvest.milk : harvest.magic;
    }

    private static void syncLevel(ClientLevel level) {
        if (level != currentLevel) {
            OBSERVED.clear();
            pending = null;
            currentLevel = level;
        }
    }

    private record Interaction(UUID entityId, Vec3 playerPos, long tick, boolean milk) {}
    private record Harvest(long milk, long magic) {}
}
