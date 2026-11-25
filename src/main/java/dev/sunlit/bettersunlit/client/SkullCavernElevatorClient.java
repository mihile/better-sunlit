package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.bettersunlit.cavern.SkullCavernElevator;
import dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

/**
 * Client-side hooks for the per-mine Skull Cavern Elevator UI, discovery Toasts,
 * spoiler-free current floor HUD, and subtle 3D floating labels over nearby elevator stations.
 */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class SkullCavernElevatorClient {
    public static final int TRIAL_COLOR = 0xFF4CC9A6;
    private static final double LABEL_MAX_DIST_SQ = 12.0D * 12.0D;

    private static volatile boolean inTrialChamber = false;
    private static volatile List<SkullCavernElevator.MarkerEntry> nearbyMarkers = List.of();

    private SkullCavernElevatorClient() {}

    @Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            SkullCavernElevator.setClientReceivers(
                    SkullCavernElevatorClient::onOpenPacket,
                    SkullCavernElevatorClient::onToastPacket,
                    SkullCavernElevatorClient::onHudPacket);
        }
    }

    private static void onOpenPacket(SkullCavernElevator.OpenPacket packet) {
        Minecraft.m_91087_().m_91152_(new SkullCavernElevatorScreen(packet));
    }

    private static void onToastPacket(SkullCavernElevator.ToastPacket packet) {
        Minecraft.m_91087_().m_91300_().m_94922_(new SkullCavernToast(packet));
    }

    private static void onHudPacket(SkullCavernElevator.HudPacket packet) {
        inTrialChamber = packet.inTrialChamber();
        nearbyMarkers = List.copyOf(packet.markers());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        inTrialChamber = false;
        nearbyMarkers = List.of();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!event.getLevel().f_46443_ || !SkullCavernElevator.isSkullCavern(event.getLevel())) return;
        BlockPos pos = event.getPos();
        if (!isKnownElevatorBlock(pos)) return;
        Minecraft mc = Minecraft.m_91087_();
        ClientPacketListener conn = mc.m_91403_();
        if (conn == null || !SkullCavernElevator.isServerChannelPresent(conn.m_104910_())) return;
        event.setUseItem(Event.Result.DENY);
    }

    private static boolean isKnownElevatorBlock(BlockPos pos) {
        for (SkullCavernElevator.MarkerEntry marker : nearbyMarkers) {
            if (marker.pos().equals(pos) || marker.pos().m_7494_().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91066_.f_92062_ || mc.f_91066_.f_92063_) return;
        Player player = mc.f_91074_;
        ClientLevel level = mc.f_91073_;
        if (player == null || level == null || !SkullCavernElevator.isSkullCavern(level)) return;

        int floor = SkullCavernElevator.floorForY(player.m_146904_());
        String text;
        int accent;
        if (floor == 0) {
            text = tr("better_sunlit.elevator.hud_surface", "Skull Cavern · Surface");
            accent = zoneColor(0);
        } else if (inTrialChamber) {
            text = tr("better_sunlit.elevator.hud_trial", "Skull Cavern · Copper Dungeon (%sF)", floor);
            accent = TRIAL_COLOR;
        } else {
            text = tr("better_sunlit.elevator.hud_floor", "Skull Cavern · %sF (%s)", floor, zoneLabel(floor));
            accent = zoneColor(floor);
        }

        Font font = mc.f_91062_;
        GuiGraphics g = event.getGuiGraphics();
        int textW = font.m_92895_(text);
        int x = 6;
        int y = 38;
        int w = textW + 15;
        int h = 16;

        g.m_280509_(x, y, x + w, y + h, 0xB8181620);
        g.m_280509_(x, y, x + 3, y + h, accent);
        g.m_280488_(font, text, x + 8, y + 4, 0xFFF4ECDD);
    }

    @SubscribeEvent
    public static void onRenderWorldLabels(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        List<SkullCavernElevator.MarkerEntry> markers = nearbyMarkers;
        if (markers.isEmpty()) return;
        Minecraft mc = Minecraft.m_91087_();
        ClientLevel level = mc.f_91073_;
        if (level == null || !SkullCavernElevator.isSkullCavern(level)) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.m_90583_();
        PoseStack pose = event.getPoseStack();
        Font font = mc.f_91062_;
        MultiBufferSource.BufferSource buffers = mc.m_91269_().m_110104_();
        boolean drewAny = false;

        for (SkullCavernElevator.MarkerEntry m : markers) {
            double wx = m.pos().m_123341_() + 0.5D;
            double wy = m.pos().m_123342_() + 1.98D;
            double wz = m.pos().m_123343_() + 0.5D;
            double dx = wx - camPos.f_82479_;
            double dy = wy - camPos.f_82480_;
            double dz = wz - camPos.f_82481_;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > LABEL_MAX_DIST_SQ) continue;

            String label;
            int color;
            if (m.kind() == SkullCavernElevatorData.KIND_SURFACE) {
                label = tr("better_sunlit.elevator.label_surface", "Elevator · Entrance");
                color = 0xFFE8C66D;
            } else if (m.kind() == SkullCavernElevatorData.KIND_TRIAL_CHAMBER) {
                label = tr("better_sunlit.elevator.label_trial", "Elevator · Copper Dungeon");
                color = 0xFF7AE6C5;
            } else {
                label = tr("better_sunlit.elevator.label_floor", "Elevator · %sF", m.floor());
                color = zoneColor(m.floor());
            }

            float scale = (float) Math.min(0.019D, Math.max(0.0105D, Math.sqrt(distSq) * 0.0040D));
            pose.m_85836_();
            pose.m_85837_(dx, dy, dz);
            pose.m_252781_(camera.m_253121_());
            pose.m_85841_(-scale, -scale, scale);
            float halfW = -font.m_92895_(label) / 2.0F;
            font.m_271703_(label, halfW, 0.0F, color, false, pose.m_85850_().m_252922_(),
                    buffers, Font.DisplayMode.NORMAL, 0x7415131B, 0x00F000F0);
            pose.m_85849_();
            drewAny = true;
        }
        if (drewAny) {
            buffers.m_109911_();
        }
    }

    public static int zoneColor(int floor) {
        if (floor <= 0) return 0xFFE8C66D;
        if (floor <= 5) return 0xFF9AC98F;
        if (floor <= 14) return 0xFF7FD2F2;
        if (floor <= 23) return 0xFFE8B46B;
        return 0xFFC08FF5;
    }

    public static String zoneLabel(int floor) {
        if (floor <= 0) return tr("better_sunlit.elevator.zone.surface", "Surface");
        if (floor <= 5) return tr("better_sunlit.elevator.zone.skull", "Skull Caves");
        if (floor <= 14) return tr("better_sunlit.elevator.zone.frozen", "Frozen Caves");
        if (floor <= 23) return tr("better_sunlit.elevator.zone.desert", "Desert Caves");
        return tr("better_sunlit.elevator.zone.blackstone", "Blackstone Caves");
    }

    private static String tr(String key, String fallback, Object... args) {
        String pattern = Language.m_128107_().m_118919_(key, fallback);
        try {
            return args.length == 0 ? pattern : String.format(pattern, args);
        } catch (RuntimeException ignored) {
            return args.length == 0 ? fallback : String.format(fallback, args);
        }
    }
}
