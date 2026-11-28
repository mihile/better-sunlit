package dev.sunlit.bettersunlit.client;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class BetterSunlitMenu {
    private BetterSunlitMenu() {}

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title)) return;
        event.addListener(Button.m_253074_(Component.m_237113_("Better Sunlit"), button -> open(title))
                .m_252987_(Math.max(4, title.f_96543_ - 108), 6, 102, 20).m_253136_());
    }

    private static void open(TitleScreen parent) {
        try {
            FancyIsolation.register();
        } catch (ReflectiveOperationException e) {
            SunlitFishVisuals.LOG.debug("FancyMenu isolation unavailable for the Better Sunlit screen", e);
        }
        Minecraft client = Minecraft.m_91087_();
        UpdateScreen screen = new UpdateScreen(parent);
        client.m_91152_(screen);
        if (client.f_91080_ != screen && screen.backdrop != null) screen.backdrop.release();
    }
}
