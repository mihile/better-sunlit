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
    private record Site(
            String cls,
            boolean clientOnly,
            String selector,
            String sourceMixin,
            String classSha256,
            String textHash,
            String methodHash,
            String target,
            int ordinal,
            String group,
            String newOwner,
            String newName,
            String newDesc) {}

    private static final class Data {
        static final JsonArray SITES=InlineResources.read("assets/better_sunlit/inline/rules.json").getAsJsonArray("sites");
        static final Map<String, Map<String, List<Site>>> BY_CLASS_SERVER;
        static final Map<String, Map<String, List<Site>>> BY_CLASS_CLIENT;
        static {
            Map<String, Map<String, List<Site>>> srv = new HashMap<>(512);
            Map<String, Map<String, List<Site>>> cli = new HashMap<>(768);
            for (JsonElement e : SITES) {
                JsonObject obj = e.getAsJsonObject();
                String cls = obj.get("class").getAsString();
                boolean clientOnly = obj.get("clientOnly").getAsBoolean();
                String method = obj.get("method").getAsString();
                String sourceMixin = obj.has("sourceMixin") ? obj.get("sourceMixin").getAsString() : null;
                String selector = sourceMixin != null
                        ? "@" + sourceMixin + method.substring(method.indexOf('('))
                        : method;
                Site s = new Site(
                        cls,
                        clientOnly,
                        selector,
                        sourceMixin,
                        sourceMixin != null ? obj.get("classSha256").getAsString() : null,
                        sourceMixin != null ? obj.get("textHash").getAsString() : null,
                        sourceMixin == null ? obj.get("methodHash").getAsString() : null,
                        obj.get("target").getAsString(),
                        obj.get("ordinal").getAsInt(),
                        obj.get("group").getAsString(),
                        obj.get("newOwner").getAsString(),
                        obj.get("newName").getAsString(),
                        obj.get("newDesc").getAsString());
                cli.computeIfAbsent(cls, k -> new HashMap<>(4))
                        .computeIfAbsent(selector, k -> new ArrayList<>(2))
                        .add(s);
                if (!clientOnly) {
                    srv.computeIfAbsent(cls, k -> new HashMap<>(4))
                            .computeIfAbsent(selector, k -> new ArrayList<>(2))
                            .add(s);
                }
            }
            BY_CLASS_SERVER = srv;
            BY_CLASS_CLIENT = cli;
        }
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
    private static boolean sourceMatches(Site site) {
        String name=site.sourceMixin();
        String hash=sourceHashes.computeIfAbsent(name,n->{
            try(var stream=InlineMixinPlugin.class.getClassLoader().getResourceAsStream(n+".class")) {
                return stream==null?"":InlineBytecode.sha256(stream.readAllBytes());
            }catch(java.io.IOException e){return "";}
        });
        return hash.equals(site.classSha256());
    }
    public static int transform(ClassNode node,boolean client) {
        Map<String, List<Site>> planned = (client ? Data.BY_CLASS_CLIENT : Data.BY_CLASS_SERVER).get(node.name);
        if (planned == null || planned.isEmpty()) {
            return 0;
        }
        int changed=0;
        for(MethodNode method:node.methods) {
            List<Site> direct = planned.get(method.name+method.desc);
            String origin=mergedFrom(method);
            List<Site> merged = origin.isEmpty() ? null : planned.get("@"+origin+method.desc);
            if((direct == null || direct.isEmpty()) && (merged == null || merged.isEmpty())) continue;
            List<Site> choices;
            if (direct == null || direct.isEmpty()) {
                choices = merged;
            } else if (merged == null || merged.isEmpty()) {
                choices = direct;
            } else {
                choices = new ArrayList<>(direct.size() + merged.size());
                choices.addAll(direct);
                choices.addAll(merged);
            }
            String hash=InlineBytecode.hash(method);
            String tHash = null;
            List<Site> matching = new ArrayList<>(choices.size());
            for (Site s : choices) {
                if (s.sourceMixin() != null) {
                    if (tHash == null) tHash = InlineBytecode.textHash(method);
                    if (sourceMatches(s) && tHash.equals(s.textHash())) matching.add(s);
                } else if (hash.equals(s.methodHash())) {
                    matching.add(s);
                }
            }
            if(matching.isEmpty()) {
                LoggerFactory.getLogger("BetterSunlit/Localization").warn("Kept original text in changed method {}.{}",node.name,method.name);
                continue;
            }
            Map<String,Site> byCall=new HashMap<>(matching.size() * 2);
            for(Site site:matching)byCall.put(site.target()+"#"+site.ordinal(),site);
            Map<String,Integer> ordinals=new HashMap<>();List<Map.Entry<MethodInsnNode,Site>> edits=new ArrayList<>(matching.size());
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call) {
                String signature=call.owner+"."+call.name+call.desc;
                int ordinal=ordinals.merge(signature,1,Integer::sum)-1;
                Site site=byCall.get(signature+"#"+ordinal);
                if(site!=null)edits.add(Map.entry(call,site));
            }
            if(edits.size()!=byCall.size())throw new IllegalStateException("Inline call-site guard mismatch in "+node.name+"."+method.name);
            for(var edit:edits) {
                MethodInsnNode call=edit.getKey();Site site=edit.getValue();
                method.instructions.insertBefore(call,new LdcInsnNode(site.group()));
                call.setOpcode(Opcodes.INVOKESTATIC);call.owner=site.newOwner();
                call.name=site.newName();call.desc=site.newDesc();call.itf=false;
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
