package dev.sunlit.bettersunlit.inline;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bootstrap-safe: no Minecraft or mod classes may be linked by the Mixin plugin. */
public final class InlineResources {
    private InlineResources(){}
    public static JsonObject read(String name) {
        try(InputStream in=InlineResources.class.getClassLoader().getResourceAsStream(name)) {
            if(in==null)throw new IllegalStateException("Missing inline localization resource: "+name);
            return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
}
