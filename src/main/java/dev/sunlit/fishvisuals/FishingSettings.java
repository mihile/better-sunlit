package dev.sunlit.fishvisuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.sunlit.bettersunlit.ServerUpdateManager;
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
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final Settings DEFAULT = new Settings(FishTiming.DEFAULT, false);
    private static volatile Settings current = DEFAULT;

    public record Settings(FishTiming timing, boolean enabled) {
        String description() {
            return "Flopping " + (enabled ? "ON" : "OFF") + " | " + timing.description();
        }
        Component descriptionComponent() {
            return Component.m_264642_("better_sunlit.realfishing.summary",
                    "Flopping %s | %s", enabled ? "ON" : "OFF", timing.descriptionComponent());
        }
    }

    private static Path file() {return FMLPaths.CONFIGDIR.get().resolve("sunlit-fishing-visuals.json");}
    public static Settings current() {return current;}
    public static void load() {
        try {
            if (!Files.exists(file())) {
                save(DEFAULT);
                current = DEFAULT;
                return;
            }
            current = readSettings(Files.readString(file()));
        } catch (Exception ex) {SunlitFishVisuals.LOG.warn("Cannot load fishing settings; using defaults",ex);current=DEFAULT;}
    }

    static Settings readSettings(String text) {
        JsonObject json = GSON.fromJson(text, JsonObject.class);
        FishTiming timing = new FishTiming(json.get("freshSeconds").getAsInt() * 20,
                json.get("fadingSeconds").getAsInt() * 20, json.get("fadingStepSeconds").getAsInt() * 20);
        var enabled = json.get("enabled");
        boolean active = enabled != null && enabled.isJsonPrimitive()
                && enabled.getAsJsonPrimitive().isBoolean() && enabled.getAsBoolean();
        return new Settings(timing, active);
    }

    private static void save(Settings settings) throws IOException {
        FishTiming value = settings.timing();
        JsonObject json=new JsonObject();
        json.addProperty("freshSeconds",value.freshTicks()/20);
        json.addProperty("fadingSeconds",value.fadingTicks()/20);
        json.addProperty("fadingStepSeconds",value.stepTicks()/20);
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
        return update(source, new Settings(settings.timing(), enabled));
    }

    public static void register(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher(), ServerUpdateManager.isServerChannel());
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerCommands(dispatcher, ServerUpdateManager.isServerChannel());
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, boolean includeServerUpdate) {
        registerCommands(dispatcher, includeServerUpdate, false);
    }

    public static void registerClientCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerCommands(dispatcher, false, true);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, boolean includeServerUpdate,
                                         boolean local) {
        var root = Commands.m_82127_("bettersunlit")
            .requires(source -> local || source.m_6761_(2) || source.m_81377_().m_129792_())
            .executes(context -> {
                context.getSource().m_288197_(() -> Component.m_264642_(
                        includeServerUpdate ? "better_sunlit.command.server_usage" : "better_sunlit.command.singleplayer_usage",
                        includeServerUpdate
                                ? "Better Sunlit | /bettersunlit realfishing <on|off|freshSec fadingSec stepSec> (%s) | /bettersunlit update"
                                : "Better Sunlit | /bettersunlit realfishing <on|off|freshSec fadingSec stepSec> (%s)",
                        current.descriptionComponent()), false);
                return 1;
            })
            .then(Commands.m_82127_("realfishing")
                .executes(context -> {
                    context.getSource().m_288197_(() -> Component.m_264642_(
                            "better_sunlit.realfishing.usage",
                            "%s | /bettersunlit realfishing <on|off> | /bettersunlit realfishing <freshSec> <fadingSec> <stepSec>",
                            current.descriptionComponent()), false);
                    return 1;
                })
                .then(Commands.m_82127_("on").executes(context -> setEnabled(context.getSource(), true)))
                .then(Commands.m_82127_("off").executes(context -> setEnabled(context.getSource(), false)))
                .then(Commands.m_82129_("freshSeconds", IntegerArgumentType.integer(0, 600))
                .then(Commands.m_82129_("fadingSeconds", IntegerArgumentType.integer(0, 600))
                .then(Commands.m_82129_("stepSeconds", IntegerArgumentType.integer(1, 600))
                .executes(context -> {
                    FishTiming value = new FishTiming(
                            IntegerArgumentType.getInteger(context, "freshSeconds") * 20,
                            IntegerArgumentType.getInteger(context, "fadingSeconds") * 20,
                            IntegerArgumentType.getInteger(context, "stepSeconds") * 20);
                    return update(context.getSource(), new Settings(value, current.enabled()));
                })))));
        if (includeServerUpdate) {
            root.then(Commands.m_82127_("update")
                .requires(source -> source.m_6761_(2))
                .executes(context -> ServerUpdateManager.executeUpdateCommand(context.getSource())));
        }
        dispatcher.register(root);
    }
}
