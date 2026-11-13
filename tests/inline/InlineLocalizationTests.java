import com.google.gson.*;
import dev.sunlit.bettersunlit.inline.*;
import dev.sunlit.bettersunlit.client.InlineTextClient;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Runs on real installed bytecode, without starting Forge, Minecraft or OpenGL. */
public final class InlineLocalizationTests {
    static int checks,changed,methods,merged,scriptCount;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static JsonObject read(String resource)throws Exception {
        try(var in=InlineLocalizationTests.class.getClassLoader().getResourceAsStream(resource)) {
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    static final class Locale extends Language {
        final JsonObject map;
        Locale(JsonObject map){this.map=map;}
        public String m_118919_(String key,String fallback){return map.has(key)?map.get(key).getAsString():fallback;}
        public boolean m_6722_(String key){return map.has(key);}
        public boolean m_6627_(){return false;}
        public FormattedCharSequence m_5536_(FormattedText text){return FormattedCharSequence.m_13714_(text.getString(),Style.f_131099_);}
    }
    static String format(String template,List<String> values) {
        // Minecraft translatable components only accept %s, indexed %s and %%.
        return String.format(java.util.Locale.ROOT,template,values.toArray());
    }
    public static void main(String[] args)throws Exception {
        JsonObject rules=read("assets/better_sunlit/inline/rules.json");
        JsonObject ko=read("assets/better_sunlit/lang/ko_kr.json");
        Language original=Language.m_128107_();
        Map<String,String> samples=new HashMap<>();
        for(var group:rules.getAsJsonObject("groups").entrySet())for(var id:group.getValue().getAsJsonArray()) {
            JsonObject rule=rules.getAsJsonObject("rules").getAsJsonObject(id.getAsString());
            JsonArray segments=rule.getAsJsonArray("segments");List<String> values=new ArrayList<>();
            StringBuilder source=new StringBuilder(segments.get(0).getAsString());
            for(int i=1;i<segments.size();i++) {String value="사용자_Value§e"+i;values.add(value);source.append(value).append(segments.get(i).getAsString());}
            String text=source.toString(),key=group.getKey();
            check(InlineText.match(text,key)!=null,"rule did not match "+id);
            Language.m_128114_(new Locale(new JsonObject()));
            check(InlineText.literal(text,key).getString().equals(text),"English server fallback changed "+id+": "+text);
            Language.m_128114_(new Locale(ko));
            String expected=format(ko.get(rule.get("key").getAsString()).getAsString(),values);
            check(InlineText.literal(text,key).getString().equals(expected),"Korean arguments changed "+id);
            if(rule.has("variants"))for(int i=0;i<rule.getAsJsonArray("variants").size();i++)for(var v:rule.getAsJsonArray("variants").get(i).getAsJsonObject().entrySet()) {
                List<String> branch=new ArrayList<>(values);branch.set(i,v.getKey());
                StringBuilder variant=new StringBuilder(segments.get(0).getAsString());for(int n=1;n<segments.size();n++)variant.append(branch.get(n-1)).append(segments.get(n).getAsString());
                String variantText=variant.toString();
                Language.m_128114_(new Locale(new JsonObject()));check(InlineText.literal(variantText,key).getString().equals(variantText),"branch fallback changed");
                branch.set(i,ko.get(v.getValue().getAsString()).getAsString());Language.m_128114_(new Locale(ko));
                check(InlineText.literal(variantText,key).getString().equals(format(ko.get(rule.get("key").getAsString()).getAsString(),branch)),"branch Korean changed");
            }
            samples.put(key,text);
        }
        check(InlineText.literal("player custom chat","unknown").getString().equals("player custom chat"),"user text changed");
        check(InlineText.nullable(null,"unknown").getString().equals(""),"nullable changed");
        check(InlineText.append(Component.m_237113_("prefix:"),"tail","unknown").getString().equals("prefix:tail"),"append changed");
        var sample=samples.entrySet().iterator().next();
        Language.m_128114_(new Locale(new JsonObject()));String first=InlineTextClient.localize(sample.getValue(),sample.getKey());
        Language.m_128114_(new Locale(ko));check(!first.equals(InlineTextClient.localize(sample.getValue(),sample.getKey())),"GUI locale cache did not invalidate");
        Language.m_128114_(original);

        Map<String,byte[]> classes=new HashMap<>();
        try(var files=Files.list(Path.of(args[0]))) {
            for(Path jar:files.filter(p->p.toString().endsWith(".jar")).toList())try(ZipFile z=new ZipFile(jar.toFile())) {
                for(var e:Collections.list(z.entries()))if(e.getName().endsWith(".class"))classes.put(e.getName().substring(0,e.getName().length()-6),z.getInputStream(e).readAllBytes());
            }
        }
        Set<String> targets=new HashSet<>();Map<String,List<JsonObject>> mergedSites=new HashMap<>();
        for(var e:rules.getAsJsonArray("sites")){var s=e.getAsJsonObject();
            if(s.has("sourceMixin"))mergedSites.computeIfAbsent(s.get("sourceMixin").getAsString(),k->new ArrayList<>()).add(s);
            else if(classes.containsKey(s.get("class").getAsString()))targets.add(s.get("class").getAsString());
        }
        boolean client=args[1].equals("client");
        Set<String> configuredTargets=new HashSet<>();
        try(ZipFile addon=new ZipFile(args[3])) {
            JsonObject config=JsonParser.parseString(new String(addon.getInputStream(addon.getEntry("better_sunlit_inline.mixins.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            for(String section:List.of("mixins","client"))if(config.has(section))for(var e:config.getAsJsonArray(section)) {
                if(e.getAsString().equals("InlineScriptSourceMixin"))continue;
                String name="dev/sunlit/fishvisuals/mixin/"+e.getAsString()+".class";
                ClassNode mix=node(addon.getInputStream(addon.getEntry(name)).readAllBytes());
                List<AnnotationNode> annotations=new ArrayList<>();if(mix.visibleAnnotations!=null)annotations.addAll(mix.visibleAnnotations);if(mix.invisibleAnnotations!=null)annotations.addAll(mix.invisibleAnnotations);
                for(var a:annotations)if(a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals("targets"))for(Object t:(List<?>)a.values.get(i+1))configuredTargets.add(t.toString().replace('.','/'));
            }
        }
        for(var e:rules.getAsJsonArray("sites")) {
            var site=e.getAsJsonObject();
            boolean installed=InlineLocalizationTests.class.getClassLoader().getResource(site.get("class").getAsString()+".class")!=null;
            if(client&&installed||!client&&site.get("serverPresent").getAsBoolean()&&!site.get("clientOnly").getAsBoolean())check(configuredTargets.contains(site.get("class").getAsString()),"site missing from built Mixin configuration "+site.get("class"));
            String owner=site.get("newOwner").getAsString();
            try(var in=InlineLocalizationTests.class.getClassLoader().getResourceAsStream(owner+".class")) {
                ClassNode helper=node(in.readAllBytes());
                check(helper.methods.stream().anyMatch(m->m.name.equals(site.get("newName").getAsString())&&m.desc.equals(site.get("newDesc").getAsString())&&(m.access&Opcodes.ACC_STATIC)!=0),"helper descriptor missing "+owner);
            }
        }
        for(String name:targets) {
            ClassNode n=node(classes.get(name));Map<String,String> before=new HashMap<>();
            for(MethodNode m:n.methods)before.put(m.name+m.desc,InlineBytecode.hash(m));
            int edits=InlineMixinPlugin.transform(n,client);changed+=edits;
            int expected=0;Set<String> callIds=new HashSet<>();
            for(var e:rules.getAsJsonArray("sites")){var s=e.getAsJsonObject();if(s.has("sourceMixin")||!name.equals(s.get("class").getAsString())||!client&&s.get("clientOnly").getAsBoolean())continue;
                if(s.get("methodHash").getAsString().equals(before.get(s.get("method").getAsString())))callIds.add(s.get("method").getAsString()+s.get("target").getAsString()+s.get("ordinal"));
            }
            expected=callIds.size();check(edits==expected,"site count mismatch "+name+": "+edits+"/"+expected);
            for(MethodNode m:n.methods)if(!InlineBytecode.hash(m).equals(before.get(m.name+m.desc))) {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(n.name,m);methods++;
            }
            ClassNode changedNode=node(classes.get(name));
            for(MethodNode m:changedNode.methods)m.instructions.insert(new LdcInsnNode("different upstream version"));
            // Mutate the executable fingerprint while leaving every call in place.
            check(InlineMixinPlugin.transform(changedNode,client)==0,"changed version was patched "+name);
        }
        for(var entry:mergedSites.entrySet()) {
            byte[] bytes=classes.get(entry.getKey());if(bytes==null)continue;
            ClassNode source=node(bytes);
            for(JsonObject site:entry.getValue()) {
                if(!client&&site.get("clientOnly").getAsBoolean())continue;
                if(!InlineBytecode.sha256(bytes).equals(site.get("classSha256").getAsString()))continue;
                MethodNode method=source.methods.stream().filter(m->(m.name+m.desc).equals(site.get("method").getAsString())).findFirst().orElseThrow();
                MethodNode copy=new MethodNode(method.access,"merged$renamed",method.desc,method.signature,method.exceptions.toArray(String[]::new));method.accept(copy);copy.name="merged$renamed";
                AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");a.values=new ArrayList<>(List.of("mixin",entry.getKey().replace('/','.')));copy.visibleAnnotations=new ArrayList<>(List.of(a));
                ClassNode target=new ClassNode();target.name=site.get("class").getAsString();target.methods=new ArrayList<>(List.of(copy));
                check(InlineMixinPlugin.transform(target,client)>0,"merged method was not patched "+entry.getKey());
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(target.name,copy);merged++;
            }
        }
        JsonObject scripts=read("assets/better_sunlit/inline/scripts.json");
        for(var entry:scripts.entrySet()) {
            var patch=entry.getValue().getAsJsonObject();Path file=Path.of(args[2]).resolve(patch.get("file").getAsString());
            String[] lines=Files.readAllLines(file,StandardCharsets.UTF_8).toArray(String[]::new);
            byte[] originalBytes=Files.readAllBytes(file);String joined=String.join("\n",lines).replaceFirst("^\uFEFF","");
            check(InlineBytecode.sha256(joined).equals(entry.getKey()),"script version changed "+file);
            String[] result=InlineScripts.apply(file.toString(),lines);String actual=String.join("\n",result);StringBuilder expected=new StringBuilder(joined);
            List<JsonObject> edits=new ArrayList<>();patch.getAsJsonArray("edits").forEach(e->edits.add(e.getAsJsonObject()));edits.sort(Comparator.comparingInt((JsonObject e)->e.get("start").getAsInt()).reversed());
            for(var e:edits)expected.replace(e.get("start").getAsInt(),e.get("end").getAsInt(),e.get("after").getAsString());
            check(actual.equals(expected.toString()),"script literal span mismatch");
            check(Arrays.equals(originalBytes,Files.readAllBytes(file)),"script file was mutated");
            String[] modified=Arrays.copyOf(lines,lines.length+1);modified[lines.length]="// upstream edit";
            check(InlineScripts.apply(file.toString(),modified)==modified,"unreviewed script patched");scriptCount++;
        }
        System.out.printf("PASS %s: %d checks, %d native calls in %d methods, %d merged-method checks, %d scripts; no game launched.%n",args[1],checks,changed,methods,merged,scriptCount);
    }
}
