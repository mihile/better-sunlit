package dev.sunlit.fishvisuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
    private static FishTiming timing=FishTiming.DEFAULT;
    private static Path file() {return FMLPaths.CONFIGDIR.get().resolve("sunlit-fishing-visuals.json");}
    public static FishTiming current() {return timing;}
    public static void load() {
        try {
            if (!Files.exists(file())) {save(timing);return;}
            JsonObject json=GSON.fromJson(Files.readString(file()),JsonObject.class);
            timing=new FishTiming(json.get("freshSeconds").getAsInt()*20,
                    json.get("fadingSeconds").getAsInt()*20,json.get("fadingStepSeconds").getAsInt()*20);
        } catch (Exception ex) {SunlitFishVisuals.LOG.warn("Cannot load fishing timing; using 3 / 3 / 1",ex);timing=FishTiming.DEFAULT;}
    }
    private static void save(FishTiming value) throws IOException {
        JsonObject json=new JsonObject();
        json.addProperty("freshSeconds",value.freshTicks()/20);
        json.addProperty("fadingSeconds",value.fadingTicks()/20);
        json.addProperty("fadingStepSeconds",value.stepTicks()/20);
        Files.createDirectories(file().getParent());
        Path temp=file().resolveSibling(file().getFileName()+".tmp");
        Files.writeString(temp,GSON.toJson(json)+"\n");
        Files.move(temp,file(),StandardCopyOption.REPLACE_EXISTING);
    }
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.m_82127_("realfishing")
            .requires(source->source.m_6761_(2) || source.m_81377_().m_129792_())
            .executes(context->{context.getSource().m_288197_(() -> Component.m_237113_(
                    timing.description()+" | /realfishing <싱싱한 초> <약해지는 초> <단계 간격 초>"),false);return 1;})
            .then(Commands.m_82129_("freshSeconds",IntegerArgumentType.integer(0,600))
            .then(Commands.m_82129_("fadingSeconds",IntegerArgumentType.integer(0,600))
            .then(Commands.m_82129_("stepSeconds",IntegerArgumentType.integer(1,600))
            .executes(context->{
                FishTiming value=new FishTiming(IntegerArgumentType.getInteger(context,"freshSeconds")*20,
                        IntegerArgumentType.getInteger(context,"fadingSeconds")*20,
                        IntegerArgumentType.getInteger(context,"stepSeconds")*20);
                try {save(value);timing=value;}
                catch (IOException ex) {context.getSource().m_81352_(Component.m_237113_("설정을 저장하지 못했습니다. 이전 설정을 유지합니다."));return 0;}
                context.getSource().m_288197_(() -> Component.m_237113_("설정 저장: "+value.description()+". 다음에 낚는 물고기부터 적용됩니다."),true);
                return 1;
            })))));
    }
}
