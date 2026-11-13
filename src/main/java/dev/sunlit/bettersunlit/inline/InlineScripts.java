package dev.sunlit.bettersunlit.inline;

import com.google.gson.*;
import java.util.*;

/** Exact source fingerprints prevent a pack update from changing unintended code. */
public final class InlineScripts {
    private static final class Data {
        static final JsonObject PATCHES=InlineResources.read("assets/better_sunlit/inline/scripts.json");
    }
    private InlineScripts(){}
    public static String[] apply(String location,String[] lines) {
        String original=String.join("\n",lines).replace("\r\n","\n");
        if(original.startsWith("\uFEFF"))original=original.substring(1);
        JsonElement selected=Data.PATCHES.get(InlineBytecode.sha256(original));
        if(selected==null)return lines;
        JsonObject patch=selected.getAsJsonObject();StringBuilder text=new StringBuilder(original);
        List<JsonObject> edits=new ArrayList<>();patch.getAsJsonArray("edits").forEach(e->edits.add(e.getAsJsonObject()));
        edits.sort(Comparator.comparingInt((JsonObject e)->e.get("start").getAsInt()).reversed());
        for(JsonObject edit:edits) {
            int start=edit.get("start").getAsInt(),end=edit.get("end").getAsInt();
            if(!text.substring(start,end).equals(edit.get("before").getAsString()))throw new IllegalStateException("Script localization guard mismatch: "+location);
            text.replace(start,end,edit.get("after").getAsString());
        }
        return text.toString().split("\n",-1);
    }
}
