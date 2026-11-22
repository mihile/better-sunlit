package dev.sunlit.fishvisuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

public final class FishingSettings {
    public static final int DEFAULT_ANIMATION_VERSION = 1;
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Settings DEFAULT = new Settings(FishTiming.DEFAULT, DEFAULT_ANIMATION_VERSION, true);
    private static volatile Settings current = DEFAULT;

    public record Settings(FishTiming timing, int animationVersion, boolean enabled) {
        String description() {
            return "Flopping " + (enabled ? "ON" : "OFF") + " | Version " + animationVersion + " | "
                    + timing.description();
        }
        Component descriptionComponent() {
            return Component.m_264642_("better_sunlit.realfishing.summary",
                    "Flopping %s | Version %s | %s", enabled ? "ON" : "OFF", animationVersion, timing.descriptionComponent());
        }
    }

    private static Path file() {return FMLPaths.CONFIGDIR.get().resolve("sunlit-fishing-visuals.json");}
    public static Settings current() {return current;}
    public static int animationVersionOrDefault(int version) {
        return version == 2 ? 2 : DEFAULT_ANIMATION_VERSION;
    }

    public static void load() {
        try {
            if (!Files.exists(file())) {save(DEFAULT);return;}
            JsonObject json=GSON.fromJson(Files.readString(file()),JsonObject.class);
            FishTiming timing=new FishTiming(json.get("freshSeconds").getAsInt()*20,
                    json.get("fadingSeconds").getAsInt()*20,json.get("fadingStepSeconds").getAsInt()*20);
            var version = json.get("animationVersion");
            int selected = version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                    ? animationVersionOrDefault(version.getAsInt()) : DEFAULT_ANIMATION_VERSION;
            var enabled = json.get("enabled");
            boolean active = enabled == null || !enabled.isJsonPrimitive()
                    || !enabled.getAsJsonPrimitive().isBoolean() || enabled.getAsBoolean();
            current = new Settings(timing, selected, active);
        } catch (Exception ex) {SunlitFishVisuals.LOG.warn("Cannot load fishing settings; using defaults",ex);current=DEFAULT;}
    }

    private static void save(Settings settings) throws IOException {
        FishTiming value = settings.timing();
        JsonObject json=new JsonObject();
        json.addProperty("freshSeconds",value.freshTicks()/20);
        json.addProperty("fadingSeconds",value.fadingTicks()/20);
        json.addProperty("fadingStepSeconds",value.stepTicks()/20);
        json.addProperty("animationVersion",settings.animationVersion());
        json.addProperty("enabled",settings.enabled());
        Files.createDirectories(file().getParent());
        Path temp=file().resolveSibling(file().getFileName()+".tmp");
        Files.writeString(temp,GSON.toJson(json)+"\n");
        Files.move(temp,file(),StandardCopyOption.REPLACE_EXISTING);
    }

    private static int update(CommandSourceStack source, Settings settings) {
        try {
            save(settings);
            current = settings;
        } catch (IOException ex) {
            source.m_81352_(Component.m_264642_("better_sunlit.realfishing.save_failed",
                    "Failed to save settings. Keeping previous settings."));
            return 0;
        }
        source.m_288197_(() -> Component.m_264642_("better_sunlit.realfishing.saved",
                "Settings saved: %s. Applies from the next caught fish.", settings.descriptionComponent()), true);
        return 1;
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        Settings settings = current;
        return update(source, new Settings(settings.timing(), settings.animationVersion(), enabled));
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.m_82127_("realfishing")
            .requires(source->source.m_6761_(2) || source.m_81377_().m_129792_())
            .executes(context->{context.getSource().m_288197_(() -> Component.m_264642_(
                    "better_sunlit.realfishing.usage",
                    "%s | /realfishing <on|off> | /realfishing version <1|2> | /realfishing <freshSec> <fadingSec> <stepSec>",
                    current.descriptionComponent()),false);return 1;})
            .then(Commands.m_82127_("on").executes(context -> setEnabled(context.getSource(), true)))
            .then(Commands.m_82127_("off").executes(context -> setEnabled(context.getSource(), false)))
            .then(Commands.m_82127_("version")
                .then(Commands.m_82129_("version", IntegerArgumentType.integer(1, 2))
                    .executes(context -> update(context.getSource(), new Settings(current.timing(),
                            IntegerArgumentType.getInteger(context, "version"), current.enabled())))))
            .then(Commands.m_82129_("freshSeconds",IntegerArgumentType.integer(0,600))
            .then(Commands.m_82129_("fadingSeconds",IntegerArgumentType.integer(0,600))
            .then(Commands.m_82129_("stepSeconds",IntegerArgumentType.integer(1,600))
            .executes(context->{
                FishTiming value=new FishTiming(IntegerArgumentType.getInteger(context,"freshSeconds")*20,
                        IntegerArgumentType.getInteger(context,"fadingSeconds")*20,
                        IntegerArgumentType.getInteger(context,"stepSeconds")*20);
                return update(context.getSource(), new Settings(value, current.animationVersion(), current.enabled()));
            })))));
    }
}
