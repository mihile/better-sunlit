package dev.sunlit.bettersunlit.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Release-specific changelog, available offline from the installed JAR. */
public final class ReleaseNotes {
    private ReleaseNotes() {}

    public static List<String> read(Properties metadata) {
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String note = metadata.getProperty("notes." + i);
            if (note == null) break;
            if (!note.isBlank()) notes.add(note);
        }
        return notes.isEmpty() ? List.of("이 버전의 변경 내역이 등록되지 않았습니다.") : List.copyOf(notes);
    }

    public static List<String> latest(long installedBuild, List<String> installed,
                                      long remoteBuild, List<String> remote) {
        return remoteBuild > installedBuild && remote != null && !remote.isEmpty() ? remote : installed;
    }
}
