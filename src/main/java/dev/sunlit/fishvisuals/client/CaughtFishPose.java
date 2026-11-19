package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

/** Keeps normal swimming disabled while a single caught-item proxy renders. */
public final class CaughtFishPose {
    private static CaughtFishPose active;
    private float geoGroundRoll;

    CaughtFishPose begin(float groundRoll) {
        geoGroundRoll = groundRoll;
        CaughtFishPose previous = active;
        active = this;
        return previous;
    }

    void end(CaughtFishPose previous) {
        active = previous;
        geoGroundRoll = 0;
    }

    public static boolean isActive() {
        return active != null;
    }

    static float groundRoll(ResourceLocation model) {
        if (!model.m_135827_().equals("unusualfishmod")) return 0;
        // These Geo models use their authored "flop" animation to lay the body on its side.
        // Suppressing that animation also removes the base angle, so restore just the angle.
        return (float)Math.toRadians(switch (model.m_135815_()) {
            case "aero_mono", "blindsailfin", "drooping_gourami", "hatchet_fish" -> -90;
            case "bark_angelfish", "beaked_herring", "blizzardfin", "circus", "copperflame",
                 "demon_herring", "duality_damselfish", "eyelash", "sneep_snorp", "snowflaketail" -> 90;
            case "forkfish" -> 60;
            default -> 0;
        });
    }

    public static float geoGroundRoll(String joint) {
        return active != null && joint.equalsIgnoreCase("body") ? active.geoGroundRoll : 0;
    }

    public static boolean apply(ModelPart part, PoseStack stack) {
        if (!isActive()) return false;
        float x = part.f_104200_, y = part.f_104201_, z = part.f_104202_;
        float pitch = part.f_104203_, yaw = part.f_104204_, roll = part.f_104205_;
        float sx = part.f_233553_, sy = part.f_233554_, sz = part.f_233555_;
        try {
            // EMF custom parts override resetPose to include their resource-pack scale.
            part.m_233569_();
            stack.m_252880_(part.f_104200_ / 16, part.f_104201_ / 16, part.f_104202_ / 16);
            if (part.f_104203_ != 0 || part.f_104204_ != 0 || part.f_104205_ != 0) {
                stack.m_252781_(new Quaternionf().rotationZYX(part.f_104205_, part.f_104204_, part.f_104203_));
            }
            if (part.f_233553_ != 1 || part.f_233554_ != 1 || part.f_233555_ != 1) {
                stack.m_85841_(part.f_233553_, part.f_233554_, part.f_233555_);
            }
        } finally {
            part.m_104227_(x, y, z);
            part.m_171327_(pitch, yaw, roll);
            part.f_233553_ = sx;
            part.f_233554_ = sy;
            part.f_233555_ = sz;
        }
        return true;
    }
}
