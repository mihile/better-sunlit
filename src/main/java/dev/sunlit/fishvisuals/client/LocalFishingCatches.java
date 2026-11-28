package dev.sunlit.fishvisuals.client;

import dev.sunlit.fishvisuals.FishingSettings;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import dev.sunlit.fishvisuals.mixin.ClientFishingBiteAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** Connects an observed reel to the reward entity the server actually spawned. */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class LocalFishingCatches {
    private static final int REWARD_TIMEOUT = 50;
    private static final int METADATA_TIMEOUT = 8;
    private static final List<Candidate> CANDIDATES = new ArrayList<>();
    private static Pending pending;

    private LocalFishingCatches() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        FishingSettings.registerClientCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void reel(PlayerInteractEvent.RightClickItem event) {
        Minecraft client = Minecraft.m_91087_();
        if (event.getEntity() != client.f_91074_ || !(event.getItemStack().m_41720_() instanceof FishingRodItem)) return;
        var hook = client.f_91074_.f_36083_;
        if (hook != null && hook.m_20088_().m_135370_(ClientFishingBiteAccessor.sunlit$biting())) expectCatch();
    }

    public static void expectCatch() {
        Minecraft client = Minecraft.m_91087_();
        if (client.f_91074_ == null || client.f_91073_ == null || !FishingSettings.current().enabled()) return;
        var hook = client.f_91074_.f_36083_;
        var connection = client.m_91403_();
        if (hook == null || connection == null || SunlitFishVisuals.CHANNEL.isRemotePresent(connection.m_104910_())) return;
        pending = new Pending(client.f_91073_, hook.m_20182_(), client.f_91074_.m_20182_(), client.f_91073_.m_46467_());
    }

    @SubscribeEvent
    public static void itemJoined(EntityJoinLevelEvent event) {
        Pending expected = pending;
        if (expected == null || event.getLevel() != expected.level || !(event.getEntity() instanceof ItemEntity item)
                || CANDIDATES.size() >= 24) return;
        long now = expected.level.m_46467_();
        if (now - expected.tick > REWARD_TIMEOUT || item.m_20182_().m_82557_(expected.hook) > 6.25D) return;
        CANDIDATES.add(new Candidate(item, expected, now));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (pending != null && (level != pending.level || level.m_46467_() - pending.tick > REWARD_TIMEOUT)) {
            pending = null;
        }
        CANDIDATES.removeIf(candidate -> {
            ItemEntity item = candidate.item;
            if (item.m_213877_() || level != candidate.pending.level) return true;
            if (level.m_46467_() - candidate.tick > METADATA_TIMEOUT) return true;
            if (item.m_32055_().m_41619_()) return false;
            Vec3 towards = candidate.pending.angler.m_82546_(candidate.pending.hook);
            Vec3 velocity = item.m_20184_();
            // Native and Stardew rewards travel toward the angler. Do not animate idle drops at the bobber.
            if (towards.f_82479_ * velocity.f_82479_ + towards.f_82481_ * velocity.f_82481_ > 0.015D) {
                ClientFishVisuals.startLocalCatch(item);
                return true;
            }
            return false;
        });
    }

    private record Pending(ClientLevel level, Vec3 hook, Vec3 angler, long tick) {}
    private record Candidate(ItemEntity item, Pending pending, long tick) {}
}
