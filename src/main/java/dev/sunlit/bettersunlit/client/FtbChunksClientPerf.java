package dev.sunlit.bettersunlit.client;

import dev.ftb.mods.ftbchunks.FTBChunks;
import dev.ftb.mods.ftbchunks.client.map.MapTask;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;

public final class FtbChunksClientPerf {
    public static final int REGION_SIZE = 512;
    public static final int REGION_PIXELS = REGION_SIZE * REGION_SIZE;
    public static final long DEFAULT_TASK_BUDGET_NS = 2_500_000L;

    private FtbChunksClientPerf() {
    }

    public static BufferedImage[] fillWriteImages(
            short[] height,
            short[] waterLightAndBiome,
            int[] foliage,
            int[] grass,
            int[] water) {
        BufferedImage dataImg = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage foliageImg = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_RGB);
        BufferedImage grassImg = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_RGB);
        BufferedImage waterImg = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_RGB);
        BufferedImage blocksImg = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_RGB);

        int[] dBuf = ((DataBufferInt) dataImg.getRaster().getDataBuffer()).getData();
        int[] fBuf = ((DataBufferInt) foliageImg.getRaster().getDataBuffer()).getData();
        int[] gBuf = ((DataBufferInt) grassImg.getRaster().getDataBuffer()).getData();
        int[] wBuf = ((DataBufferInt) waterImg.getRaster().getDataBuffer()).getData();
        int[] bBuf = ((DataBufferInt) blocksImg.getRaster().getDataBuffer()).getData();

        for (int i = 0; i < REGION_PIXELS; i++) {
            dBuf[i] = ((height[i] & 0xFFFF) << 16) | (waterLightAndBiome[i] & 0xFFFF);
            int f = foliage[i];
            int g = grass[i];
            int w = water[i];
            fBuf[i] = f & 0xFFFFFF;
            gBuf[i] = g & 0xFFFFFF;
            wBuf[i] = w & 0xFFFFFF;
            bBuf[i] = (((f >>> 24) & 0xFF) << 16) | (((g >>> 24) & 0xFF) << 8) | ((w >>> 24) & 0xFF);
        }
        return new BufferedImage[]{dataImg, foliageImg, grassImg, waterImg, blocksImg};
    }

    public static void runQueuedTasks(ArrayDeque<MapTask> taskQueue, int maxTasks, long budgetNs) {
        if (taskQueue == null || taskQueue.isEmpty() || maxTasks <= 0) {
            return;
        }
        int limit = Math.min(taskQueue.size(), maxTasks);
        long deadlineNs = System.nanoTime() + Math.max(0L, budgetNs);
        for (int i = 0; i < limit; i++) {
            MapTask task = taskQueue.pollFirst();
            if (task == null) {
                break;
            }
            boolean cancelOthers = task.cancelOtherTasks();
            try {
                task.runMapTask();
            } catch (Exception e) {
                FTBChunks.LOGGER.error("Failed to run task " + task);
                e.printStackTrace();
            }
            if (cancelOthers || System.nanoTime() >= deadlineNs) {
                break;
            }
        }
    }
}
