package dev.sunlit.bettersunlit.inline;

import com.google.gson.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only explicitly selected, pinned call sites pass a group ID to these methods. */
public final class InlineText {
    private InlineText() {}

    public record Match(Rule rule, Object[] arguments) {}
    public static final class Rule {
        final String key, fallback;
        final List<String> segments;
        final List<Map<String,String>> variants;
        Rule(JsonObject json) {
            key=json.get("key").getAsString();fallback=json.get("fallback").getAsString();
            segments=new ArrayList<>();json.getAsJsonArray("segments").forEach(v->segments.add(v.getAsString()));
            variants=new ArrayList<>();
            if(json.has("variants"))for(JsonElement e:json.getAsJsonArray("variants")) {
                Map<String,String> map=new HashMap<>();e.getAsJsonObject().entrySet().forEach(v->map.put(v.getKey(),v.getValue().getAsString()));variants.add(map);
            }
        }
        Object[] match(String text) {
            if(text==null||!text.startsWith(segments.get(0)))return null;
            if(segments.size()==1)return text.equals(segments.get(0))?new Object[0]:null;
            int cursor=segments.get(0).length();Object[] values=new Object[segments.size()-1];
            for(int i=0;i<values.length;i++) {
                String next=segments.get(i+1);int end;
                if(i==values.length-1) {
                    if(!text.endsWith(next))return null;
                    end=text.length()-next.length();
                } else end=text.indexOf(next,cursor);
                if(end<cursor)return null;
                String value=text.substring(cursor,end);
                String valueKey=i<variants.size()?variants.get(i).get(value):null;
                values[i]=valueKey==null?value:Component.m_264568_(valueKey,value);
                cursor=end+next.length();
            }
            return cursor==text.length()?values:null;
        }
    }
    private static final class Data {
        static final JsonObject JSON=readResource("assets/better_sunlit/inline/rules.json");
        static final Map<String,Rule> RULES=new HashMap<>();
        static final Map<String,List<Rule>> GROUPS=new HashMap<>();
        static {
            JSON.getAsJsonObject("rules").entrySet().forEach(e->RULES.put(e.getKey(),new Rule(e.getValue().getAsJsonObject())));
            JSON.getAsJsonObject("groups").entrySet().forEach(e->{
                List<Rule> list=new ArrayList<>();e.getValue().getAsJsonArray().forEach(v->list.add(RULES.get(v.getAsString())));GROUPS.put(e.getKey(),list);
            });
        }
    }
    public static JsonObject readResource(String name) {
        try(InputStream in=InlineText.class.getClassLoader().getResourceAsStream(name)) {
            if(in==null)throw new IllegalStateException("Missing inline localization resource: "+name);
            return JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException e) {throw new UncheckedIOException(e);}
    }
    public static Match match(String text,String group) {
        for(Rule rule:Data.GROUPS.getOrDefault(group,List.of())) {
            Object[] args=rule.match(text);if(args!=null)return new Match(rule,args);
        }
        return null;
    }
    public static MutableComponent literal(String text,String group) {
        Match match=match(text,group);
        return match==null?Component.m_237113_(text):Component.m_264642_(match.rule.key,match.rule.fallback,match.arguments);
    }
    public static Component nullable(String text,String group) {
        return text==null?Component.m_130674_(null):literal(text,group);
    }
    public static MutableComponent key(String text,String group) {
        Match match=match(text,group);
        return match==null?Component.m_237115_(text):Component.m_264642_(match.rule.key,match.rule.fallback,match.arguments);
    }
    public static MutableComponent append(MutableComponent receiver,String text,String group) {
        return receiver.m_7220_(literal(text,group));
    }
}
