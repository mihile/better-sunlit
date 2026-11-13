package dev.sunlit.bettersunlit.inline;

import com.google.gson.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.*;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fails closed when executable instructions differ from the reviewed mod version. */
public final class InlineMixinPlugin implements IMixinConfigPlugin {
    private static final class Data {
        static final JsonArray SITES=InlineResources.read("assets/better_sunlit/inline/rules.json").getAsJsonArray("sites");
    }
    private static final Map<String,String> sourceHashes=new ConcurrentHashMap<>();
    public static JsonArray sites() {
        return Data.SITES;
    }
    private static String mergedFrom(MethodNode method) {
        if(method.visibleAnnotations!=null)for(AnnotationNode annotation:method.visibleAnnotations)
            if(annotation.desc.endsWith("/MixinMerged;")&&annotation.values!=null)
                for(int i=0;i<annotation.values.size();i+=2)if(annotation.values.get(i).equals("mixin"))return annotation.values.get(i+1).toString().replace('.','/');
        return "";
    }
    private static boolean sourceMatches(JsonObject site) {
        String name=site.get("sourceMixin").getAsString();
        String hash=sourceHashes.computeIfAbsent(name,n->{
            try(var stream=InlineMixinPlugin.class.getClassLoader().getResourceAsStream(n+".class")) {
                return stream==null?"":InlineBytecode.sha256(stream.readAllBytes());
            }catch(java.io.IOException e){return "";}
        });
        return hash.equals(site.get("classSha256").getAsString());
    }
    public static int transform(ClassNode node,boolean client) {
        Map<String,List<JsonObject>> planned=new HashMap<>();
        for(JsonElement e:sites()) {
            JsonObject site=e.getAsJsonObject();
            if(!node.name.equals(site.get("class").getAsString())||(!client&&site.get("clientOnly").getAsBoolean()))continue;
            String method=site.get("method").getAsString();
            String selector=site.has("sourceMixin")?"@"+site.get("sourceMixin").getAsString()+method.substring(method.indexOf('(')):method;
            planned.computeIfAbsent(selector,k->new ArrayList<>()).add(site);
        }
        int changed=0;
        for(MethodNode method:node.methods) {
            List<JsonObject> choices=new ArrayList<>(planned.getOrDefault(method.name+method.desc,List.of()));
            String origin=mergedFrom(method);
            if(!origin.isEmpty())choices.addAll(planned.getOrDefault("@"+origin+method.desc,List.of()));
            if(choices.isEmpty())continue;
            String hash=InlineBytecode.hash(method);
            List<JsonObject> matching=choices.stream().filter(s->s.has("sourceMixin")?
                    sourceMatches(s)&&InlineBytecode.textHash(method).equals(s.get("textHash").getAsString()):hash.equals(s.get("methodHash").getAsString())).toList();
            if(matching.isEmpty()) {
                LoggerFactory.getLogger("BetterSunlit/Localization").warn("Kept original text in changed method {}.{}",node.name,method.name);
                continue;
            }
            Map<String,JsonObject> byCall=new HashMap<>();
            for(JsonObject site:matching)byCall.put(site.get("target").getAsString()+"#"+site.get("ordinal").getAsInt(),site);
            Map<String,Integer> ordinals=new HashMap<>();List<Map.Entry<MethodInsnNode,JsonObject>> edits=new ArrayList<>();
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
                String signature=call.owner+"."+call.name+call.desc;
                int ordinal=ordinals.merge(signature,1,Integer::sum)-1;
                JsonObject site=byCall.get(signature+"#"+ordinal);
                if(site!=null)edits.add(Map.entry(call,site));
            }
            if(edits.size()!=byCall.size())throw new IllegalStateException("Inline call-site guard mismatch in "+node.name+"."+method.name);
            for(var edit:edits) {
                MethodInsnNode call=edit.getKey();JsonObject site=edit.getValue();
                method.instructions.insertBefore(call,new LdcInsnNode(site.get("group").getAsString()));
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner=site.get("newOwner").getAsString();
                call.name=site.get("newName").getAsString();call.desc=site.get("newDesc").getAsString();call.itf=false;
                method.maxStack+=1;changed++;
            }
        }
        return changed;
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig(){return null;}
    @Override public boolean shouldApplyMixin(String targetClassName,String mixinClassName){
        var tracker=org.spongepowered.asm.service.MixinService.getService().getClassTracker();
        // Localization must never force retransformation of a class loaded during bootstrap.
        return tracker==null||!tracker.isClassLoaded(targetClassName);
    }
    @Override public void acceptTargets(Set<String> myTargets,Set<String> otherTargets){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
    @Override public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){
        if(mixinClassName.contains(".InlineCommon"))transform(targetClass,false);
        if(mixinClassName.contains(".InlineClient"))transform(targetClass,true);
    }
}
