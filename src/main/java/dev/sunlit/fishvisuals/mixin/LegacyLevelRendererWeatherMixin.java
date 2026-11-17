package dev.sunlit.fishvisuals.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LevelRenderer.class,remap=false)
public abstract class LegacyLevelRendererWeatherMixin {

 @Inject(method="m_109703_(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LightTexture;m_109896_()V"),cancellable=true,require=1)
 private void sunlit$skipLegacyDisabledWeather(LightTexture light,float partial,double x,double y,double z,CallbackInfo ci){if(dev.sunlit.bettersunlit.client.LegacyWeatherConfig.skip())ci.cancel();}
}
