package dev.sunlit.fishvisuals.mixin;

import com.github.spacemex.skillsexpnotifier.client.SearchForSkills;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Replaces recursive {@link File#listFiles()} scanning of thousands of {@code kubejs/data} files
 * with NIO {@link Files#walkFileTree}.
 */
@Mixin(value = SearchForSkills.class, remap = false)
public abstract class PerfSearchForSkillsMixin {
    /**
     * @author Better Sunlit
     * @reason Avoid per-file File.isDirectory stat syscalls across thousands of kubejs/data entries
     */
    @Overwrite
    private static void scanDir(Path dir, List<File> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path fileName = file.getFileName();
                    if (fileName != null && "category.json".equals(fileName.toString())) {
                        out.add(file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }
}
