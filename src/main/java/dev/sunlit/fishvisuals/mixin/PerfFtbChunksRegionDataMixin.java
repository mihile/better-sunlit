package dev.sunlit.fishvisuals.mixin;

import dev.ftb.mods.ftbchunks.FTBChunks;
import dev.ftb.mods.ftbchunks.client.FTBChunksClient;
import dev.ftb.mods.ftbchunks.client.map.MapChunk;
import dev.ftb.mods.ftbchunks.client.map.MapRegion;
import dev.ftb.mods.ftbchunks.client.map.MapRegionData;
import dev.sunlit.bettersunlit.client.FtbChunksClientPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.client.map.MapRegionData", remap = false)
public abstract class PerfFtbChunksRegionDataMixin {
    @Shadow(remap = false)
    public MapRegion region;

    @Shadow(remap = false)
    public short[] height;

    @Shadow(remap = false)
    public short[] waterLightAndBiome;

    @Shadow(remap = false)
    public int[] foliage;

    @Shadow(remap = false)
    public int[] grass;

    @Shadow(remap = false)
    public int[] water;

    @Shadow(remap = false)
    private void writeData(
            Collection<MapChunk> chunks,
            BufferedImage dataImg,
            BufferedImage foliageImg,
            BufferedImage grassImg,
            BufferedImage waterImg,
            BufferedImage blocksImg) throws IOException {
    }

    /**
     * @author better-sunlit
     * @reason Populate 512x512 BufferedImage rasters via direct DataBufferInt writes instead of 1.31M BufferedImage.setRGB calls
     */
    @Overwrite(remap = false)
    public void write() throws IOException {
        Collection<MapChunk> chunks;
        synchronized (this.region.dimension.getManager().lock) {
            chunks = this.region.getModifiedChunks();
        }
        if (chunks.isEmpty()) {
            return;
        }
        BufferedImage[] imgs = FtbChunksClientPerf.fillWriteImages(
                this.height, this.waterLightAndBiome, this.foliage, this.grass, this.water);
        FTBChunksClient.MAP_EXECUTOR.execute(() -> {
            try {
                this.writeData(chunks, imgs[0], imgs[1], imgs[2], imgs[3], imgs[4]);
            } catch (Exception e) {
                FTBChunks.LOGGER.error("Failed to save region " + this.region.dimension + ":" + this.region);
                e.printStackTrace();
            }
        });
    }
}
