package dev.sunlit.fishvisuals.mixin;

import net.minecraftforge.client.model.obj.ObjTokenizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Replaces regex {@code String.split("[\t ]+")} + stream allocations per line in Forge's
 * {@link ObjTokenizer} with a zero-regex index scan.
 */
@Mixin(value = ObjTokenizer.class, remap = false)
public abstract class PerfObjTokenizerMixin {
    @Shadow
    @Final
    private BufferedReader lineReader;

    @Overwrite
    public String[] readAndSplitLine(boolean ignoreEmptyLines) throws IOException {
        ArrayList<String> words = null;
        while (true) {
            String currentLine = this.lineReader.readLine();
            if (currentLine == null) {
                return null;
            }
            if (!currentLine.startsWith("#") && !currentLine.isEmpty()) {
                boolean hasMore;
                do {
                    hasMore = currentLine.charAt(currentLine.length() - 1) == '\\';
                    int endLimit = hasMore ? currentLine.length() - 1 : currentLine.length();
                    int i = 0;
                    while (i < endLimit) {
                        while (i < endLimit) {
                            char c = currentLine.charAt(i);
                            if (c != ' ' && c != '\t') {
                                break;
                            }
                            i++;
                        }
                        if (i >= endLimit) {
                            break;
                        }
                        int start = i;
                        while (i < endLimit) {
                            char c = currentLine.charAt(i);
                            if (c == ' ' || c == '\t') {
                                break;
                            }
                            i++;
                        }
                        if (words == null) {
                            words = new ArrayList<>(8);
                        }
                        words.add(currentLine.substring(start, i));
                    }
                    if (hasMore) {
                        currentLine = this.lineReader.readLine();
                        if (currentLine == null || currentLine.isEmpty() || currentLine.startsWith("#")) {
                            break;
                        }
                    }
                } while (hasMore);
            }
            if (words != null && !words.isEmpty()) {
                return words.toArray(new String[0]);
            }
            if (!ignoreEmptyLines) {
                return new String[0];
            }
        }
    }
}
