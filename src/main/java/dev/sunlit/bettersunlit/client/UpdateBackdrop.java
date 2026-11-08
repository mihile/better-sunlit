package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

final class UpdateBackdrop extends AbstractTexture {
    private static long sequence;
    private final ResourceLocation id = new ResourceLocation(SunlitFishVisuals.ID, "update_backdrop/" + (++sequence));
    private final int width, height;
    private boolean released;

    private UpdateBackdrop() {
        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.m_91087_();
        var framebuffer = client.m_91385_();
        width = framebuffer.f_83915_;
        height = framebuffer.f_83916_;
        if (width <= 0 || height <= 0) throw new IllegalStateException("Menu framebuffer is not ready");
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._bindTexture(m_117963_());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.f_83920_);
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, width, height, 0);
        } catch (RuntimeException | Error e) {
            m_117966_();
            throw e;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._bindTexture(texture);
        }
        client.m_91097_().m_118495_(id, this);
    }

    static UpdateBackdrop capture() {
        try {
            return new UpdateBackdrop();
        } catch (RuntimeException e) {
            SunlitFishVisuals.LOG.warn("Cannot capture menu backdrop; using plain modal backdrop", e);
            return null;
        }
    }

    void draw(GuiGraphics g, int screenWidth, int screenHeight) {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        g.m_280411_(id, 0, 0, screenWidth, screenHeight, 0f, (float) height, width, -height, width, height);
    }

    void release() {
        if (!released) {
            released = true;
            Minecraft.m_91087_().m_91097_().m_118513_(id);
        }
    }

    @Override
    public void m_6704_(ResourceManager manager) {}
}
