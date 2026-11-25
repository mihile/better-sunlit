package dev.sunlit.fishvisuals;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public final class RealFishingTests {
    public static void main(String[] args) {
        check(!FishingSettings.current().enabled(), "Fresh settings must default to off");
        String timing = "{\"freshSeconds\":3,\"fadingSeconds\":4,\"fadingStepSeconds\":1,\"animationVersion\":1";
        var implicit = FishingSettings.readSettings(timing + "}");
        check(!implicit.enabled() && implicit.timing().equals(new FishTiming(60, 80, 20)),
                "Old config without an explicit flag must default to off and retain timing");
        check(FishingSettings.readSettings(timing + ",\"enabled\":true}").enabled(), "An explicit on must survive");
        check(!FishingSettings.readSettings(timing + ",\"enabled\":false}").enabled(), "An explicit off must survive");
        check(!FishingSettings.readSettings(timing + ",\"enabled\":\"true\"}").enabled(), "Invalid flags must default to off");

        for (String nsAndName : new String[]{"aquaculture:leech", "aquaculture:goldfish", "society:neptuna"}) {
            ResourceLocation expected = new ResourceLocation(nsAndName);
            check(expected.equals(CatchModelIds.resolve(expected)), "Custom fish model: " + nsAndName);
            check(expected.equals(CatchModelIds.resolve(new ResourceLocation(nsAndName.replace(":", ":raw_")))),
                    "Custom raw fish model: " + nsAndName);
            check(CatchModelIds.isCustomModel(expected), "Marked as custom model: " + nsAndName);
            check(dev.sunlit.fishvisuals.client.CustomFishModels.has(expected),
                    "Client custom model registered: " + nsAndName);
            check(dev.sunlit.fishvisuals.client.CustomFishModels.bake(expected) != null,
                    "Client custom model bakes cleanly: " + nsAndName);
            ResourceLocation tex = dev.sunlit.fishvisuals.client.CustomFishModels.texture(expected);
            String resPath = "/assets/" + tex.m_135827_() + "/" + tex.m_135815_();
            check(RealFishingTests.class.getResource(resPath) != null,
                    "Custom fish texture bundled in JAR: " + resPath);
        }
        check(!CatchModelIds.isCustomModel(new ResourceLocation("minecraft:cod")), "Vanilla fish is not custom");

        var pondPos = new net.minecraft.core.BlockPos(10, 64, -20);
        var pondSnap = new FishPondSync.PondSnapshot(pondPos, "society:neptuna", 8, 10);
        var pondPacket = new FishPondSync.PondPacket(
                new ResourceLocation("minecraft:overworld"),
                java.util.List.of(pondSnap),
                java.util.List.of(new net.minecraft.core.BlockPos(1, 64, 2)));
        var pondBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FishPondSync.PondPacket.encode(pondPacket, pondBuf);
            var decoded = FishPondSync.PondPacket.decode(pondBuf);
            check(pondPacket.equals(decoded) && !pondBuf.isReadable(), "Fish pond sync packet round trip");
        } finally {
            pondBuf.release();
        }
        float scale3 = dev.sunlit.fishvisuals.client.FishPondVisuals.computeScale(
                dev.sunlit.fishvisuals.client.FishPondVisuals.Mode.ADAPTIVE, 3, 0.5F, 0.7F);
        float scale8Default = dev.sunlit.fishvisuals.client.FishPondVisuals.computeScale(
                dev.sunlit.fishvisuals.client.FishPondVisuals.Mode.ADAPTIVE, 8, 0.5F, 0.7F);
        float scale8Custom = dev.sunlit.fishvisuals.client.FishPondVisuals.computeScale(
                dev.sunlit.fishvisuals.client.FishPondVisuals.Mode.ADAPTIVE, 8, 0.5F, 0.8F);
        check(Math.abs(scale3 - 1.0F) < 1e-4F, "1-3 fish should stay at full scale");
        check(Math.abs(scale8Default - 0.7F) < 1e-4F, "8 fish at default 0.7 adaptive scale");
        check(Math.abs(scale8Custom - 0.8F) < 1e-4F && scale8Custom > scale8Default,
                "Custom adaptive scale 0.8 must scale 8 fish to 0.8");
        var pondSettings = dev.sunlit.fishvisuals.client.FishPondVisuals.readSettings(
                "{\"mode\":\"adaptive\",\"adaptiveScale\":0.8}");
        check(pondSettings.mode() == dev.sunlit.fishvisuals.client.FishPondVisuals.Mode.ADAPTIVE
                && Math.abs(pondSettings.adaptiveScale() - 0.8F) < 1e-4F,
                "Saved pond settings JSON round trip");
        var legacyCalm = dev.sunlit.fishvisuals.client.FishPondVisuals.readSettings(
                "{\"mode\":\"calm\",\"adaptiveScale\":0.85}");
        check(legacyCalm.mode() == dev.sunlit.fishvisuals.client.FishPondVisuals.Mode.ADAPTIVE
                && Math.abs(legacyCalm.adaptiveScale() - 0.85F) < 1e-4F,
                "Removed calm mode must migrate cleanly to adaptive");
        int wrapped = dev.sunlit.fishvisuals.client.FishPondVisuals.advanceAnimTicks(119_999);
        check(wrapped == 0 && dev.sunlit.fishvisuals.client.FishPondVisuals.advanceAnimTicks(42) == 43,
                "Pond animation ticks must advance monotonically and wrap well below Mth.sin int overflow");
        float sinA = net.minecraft.util.Mth.m_14031_(0.6F * 119_998.0F);
        float sinB = net.minecraft.util.Mth.m_14031_(0.6F * 119_999.0F);
        check(Math.abs(sinA - sinB) > 0.05F, "Tail wag Mth.sin must remain active up to max anim tick");
        var northBounds = dev.sunlit.fishvisuals.client.FishPondVisuals.computeBounds(
                pondPos, net.minecraft.core.Direction.NORTH, false, false);
        check(northBounds.maxX - northBounds.minX == 3.0D && northBounds.maxZ - northBounds.minZ == 4.0D,
                "Pond bounds must match 3x4 basin");

        String absentVersion = net.minecraftforge.network.NetworkRegistry.ABSENT.version();
        String vanillaVersion = net.minecraftforge.network.NetworkRegistry.ACCEPTVANILLA;
        check(FishPondSync.acceptsVersion("1") && FishPondSync.acceptsVersion(absentVersion)
                && FishPondSync.acceptsVersion(vanillaVersion) && !FishPondSync.acceptsVersion("999"),
                "FishPondSync channel must accept protocol 1, ABSENT, and ACCEPTVANILLA");
        check(dev.sunlit.bettersunlit.husbandry.AnimalStatusSync.acceptsVersion("2")
                && dev.sunlit.bettersunlit.husbandry.AnimalStatusSync.acceptsVersion(absentVersion)
                && dev.sunlit.bettersunlit.husbandry.AnimalStatusSync.acceptsVersion(vanillaVersion)
                && !dev.sunlit.bettersunlit.husbandry.AnimalStatusSync.acceptsVersion("999"),
                "AnimalStatusSync channel must accept protocol 2, ABSENT, and ACCEPTVANILLA");

        check(dev.sunlit.bettersunlit.cavern.SkullCavernElevator.acceptsVersion("2")
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.acceptsVersion(absentVersion)
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.acceptsVersion(vanillaVersion)
                && !dev.sunlit.bettersunlit.cavern.SkullCavernElevator.acceptsVersion("999"),
                "SkullCavernElevator channel must accept protocol 2, ABSENT, and ACCEPTVANILLA");
        check(dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(512) == 0
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(504) == 0
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(503) == 1
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(488) == 1
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(487) == 2
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(352) == 10
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(192) == 20
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(32) == 30
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.floorForY(8) == 31,
                "Skull Cavern Y-to-floor mapping must match 16-block cavern layers");
        check(!dev.sunlit.bettersunlit.cavern.SkullCavernElevator.isMilestoneFloor(5)
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.isMilestoneFloor(10)
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.isMilestoneFloor(20)
                && dev.sunlit.bettersunlit.cavern.SkullCavernElevator.isMilestoneFloor(30)
                && !dev.sunlit.bettersunlit.cavern.SkullCavernElevator.isMilestoneFloor(31),
                "Milestone floors must be multiples of 10");

        var elevData = new dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData();
        var mineA = elevData.getOrCreateMine(new net.minecraft.core.BlockPos(0, 512, 0));
        var mineB = elevData.getOrCreateMine(new net.minecraft.core.BlockPos(200, 512, 200));
        check(!mineA.id.equals(mineB.id), "Distant entrances must create separate independent mines");
        elevData.setSurfaceStation(mineA, new net.minecraft.core.BlockPos(2, 512, 0), new net.minecraft.core.BlockPos(1, 512, 0));
        elevData.addOrUpdateStop(mineA, new dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.ElevatorStop(
                "floor_10", dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.KIND_FLOOR, 10,
                new net.minecraft.core.BlockPos(5, 352, 4), new net.minecraft.core.BlockPos(4, 352, 4), "mstj"));
        elevData.addOrUpdateStop(mineA, new dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.ElevatorStop(
                "trial_10_20", dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.KIND_TRIAL_CHAMBER, 14,
                new net.minecraft.core.BlockPos(10, 285, 20), new net.minecraft.core.BlockPos(9, 285, 20), "mstj"));
        var savedTag = elevData.m_7176_(new net.minecraft.nbt.CompoundTag());
        var loadedData = dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.load(savedTag);
        var loadedA = loadedData.findNearestBySurface(10, -10);
        check(loadedA != null && loadedA.stops.size() == 2
                && loadedData.isProtectedBlock(new net.minecraft.core.BlockPos(2, 512, 0))
                && loadedData.isProtectedBlock(new net.minecraft.core.BlockPos(2, 513, 0))
                && loadedData.findNearestBySurface(200, 200).stops.isEmpty(),
                "Per-mine SavedData round trip must isolate mines and protect elevator + lantern");

        var elevBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var stopA = new dev.sunlit.bettersunlit.cavern.SkullCavernElevator.StopEntry(
                    "floor_10", dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.KIND_FLOOR, 10, "mstj", false);
            var stopT = new dev.sunlit.bettersunlit.cavern.SkullCavernElevator.StopEntry(
                    "trial_10_20", dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.KIND_TRIAL_CHAMBER, 14, "mstj", true);
            var openPkt = new dev.sunlit.bettersunlit.cavern.SkullCavernElevator.OpenPacket(
                    pondPos, "trial_10_20", java.util.List.of(stopA, stopT));
            dev.sunlit.bettersunlit.cavern.SkullCavernElevator.OpenPacket.encode(openPkt, elevBuf);
            check(openPkt.equals(dev.sunlit.bettersunlit.cavern.SkullCavernElevator.OpenPacket.decode(elevBuf))
                    && !elevBuf.isReadable(), "SkullCavernElevator OpenPacket round trip");
            elevBuf.clear();
            var toastPkt = new dev.sunlit.bettersunlit.cavern.SkullCavernElevator.ToastPacket(
                    dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData.KIND_TRIAL_CHAMBER, 14, "mstj", true, true);
            dev.sunlit.bettersunlit.cavern.SkullCavernElevator.ToastPacket.encode(toastPkt, elevBuf);
            check(toastPkt.equals(dev.sunlit.bettersunlit.cavern.SkullCavernElevator.ToastPacket.decode(elevBuf))
                    && !elevBuf.isReadable(), "SkullCavernElevator ToastPacket round trip");
            elevBuf.clear();
            var usePkt = new dev.sunlit.bettersunlit.cavern.SkullCavernElevator.UseElevatorPacket(pondPos, "floor_10");
            dev.sunlit.bettersunlit.cavern.SkullCavernElevator.UseElevatorPacket.encode(usePkt, elevBuf);
            check(usePkt.equals(dev.sunlit.bettersunlit.cavern.SkullCavernElevator.UseElevatorPacket.decode(elevBuf))
                    && !elevBuf.isReadable(), "SkullCavernElevator UseElevatorPacket round trip");
        } finally {
            elevBuf.release();
        }

        var packet = new SunlitFishVisuals.CatchPacket(new UUID(1, 2), 42, new ResourceLocation("minecraft:cod"),
                new ResourceLocation("minecraft:overworld"), 10, 11, 12, 0.5F, FishTiming.DEFAULT);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int legacyByte : new int[]{-1, 1, 2}) {
                buf.clear();
                SunlitFishVisuals.CatchPacket.encode(packet, buf);
                if (legacyByte != -1) buf.writeByte(legacyByte);
                check(packet.equals(SunlitFishVisuals.CatchPacket.decode(buf)) && !buf.isReadable(),
                        "Protocol 3 catch packet round trip, legacy byte " + legacyByte);
            }
        } finally {
            buf.release();
        }
        check("PreventRemoteMovement".equals(SunlitFishVisuals.PREVENT_REMOTE_MOVEMENT),
                "Magnet protection key must match Forge/SimpleMagnets/SophisticatedCore convention");
        System.out.println("PASS: RealFishing defaults, saved settings, custom fish models, and protocol 3 catch packets");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
