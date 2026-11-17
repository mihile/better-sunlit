import java.nio.file.*;import java.net.*;import java.io.*;
import java.util.*;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
public class BaseOptimizationContracts{static ClassLoader l=BaseOptimizationContracts.class.getClassLoader();static boolean isolatedGate(String target,String name,boolean missing,boolean changed,boolean modern)throws Exception {
 String prop=modern?"sunlit.modern.classpath":"sunlit.base.classpath";
 var urls=Files.readAllLines(Path.of(System.getProperty(prop))).stream().map(x->{try{return Path.of(x).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
 try(var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader()) {
  @Override public InputStream getResourceAsStream(String n){
   if(missing&&(n.startsWith("pigcart/")||n.startsWith("net/irisshaders/")||n.startsWith("traben/entity_model_features/")||n.startsWith("traben/entity_texture_features/")||n.startsWith("dev/djefrey/colorwheel/")))return null;
   InputStream in=super.getResourceAsStream(n);
   if(changed&&n.equals(target.replace('.','/')+".class")&&in!=null)try(in){byte[] b=in.readAllBytes();return new ByteArrayInputStream(java.util.Arrays.copyOf(b,b.length+1));}catch(IOException e){throw new RuntimeException(e);}
   return in;
  }
 }) {var type=Class.forName("dev.sunlit.bettersunlit.inline.EntryMixinPlugin",true,loader);return (boolean)type.getMethod("canApplyGate",String.class,String.class).invoke(type.getConstructor().newInstance(),target,name);}
}
static String shaderKey(boolean modern)throws Exception {
 String prop=modern?"sunlit.modern.classpath":"sunlit.base.classpath";
 var urls=Files.readAllLines(Path.of(System.getProperty(prop))).stream().map(x->{try{return Path.of(x).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
 try(var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader())) {
  var cache=Class.forName("dev.sunlit.bettersunlit.client.GlslTransformDiskCache",true,loader);
  var map=Class.forName("it.unimi.dsi.fastutil.objects.Object2ObjectMap",false,loader);
  return (String)cache.getMethod("keyForClrwlString",String.class,String.class,boolean.class,int.class,map).invoke(null,"same-stage","same shader source",false,0,null);
 }
}
static ClassNode node(String n)throws Exception{try(var in=l.getResourceAsStream(n.replace('.','/')+".class")){if(in==null)throw new AssertionError("Missing "+n);var c=new ClassNode();new ClassReader(in).accept(c,0);return c;}}static boolean exists(ClassNode n,String name,String desc,boolean field)throws Exception{if(field?n.fields.stream().anyMatch(f->f.name.equals(name)&&f.desc.equals(desc)):n.methods.stream().anyMatch(m->m.name.equals(name)&&m.desc.equals(desc)))return true;return n.superName!=null&&!n.superName.equals("java/lang/Object")&&exists(node(n.superName),name,desc,field);}static boolean ann(List<AnnotationNode>a,String end){return a!=null&&a.stream().anyMatch(x->x.desc.endsWith(end+";"));}public static void main(String[]args)throws Exception{int count=0;
 var cache = new dev.sunlit.fishvisuals.mixin.LegacyEmfModelPartMixin() {};
 var first = new net.minecraft.client.model.geom.ModelPart(java.util.List.of(),java.util.Map.of());
 var second = new net.minecraft.client.model.geom.ModelPart(java.util.List.of(),java.util.Map.of());
 var children = new java.util.LinkedHashMap<String,net.minecraft.client.model.geom.ModelPart>();
 children.put("child",first);var array=cache.sunlit$getChildrenArray(children);
 if(array.length!=1||array[0]!=first)throw new AssertionError("Child array contents");count++;
 children.put("child",second);
 if(cache.sunlit$getChildrenArray(children)!=array||array[0]!=second)throw new AssertionError("Same-size child replacement stale or allocated array");count++;
 children.put("other",first);var resized=cache.sunlit$getChildrenArray(children);
 if(resized.length!=2||resized[0]!=second||resized[1]!=first)throw new AssertionError("Child array growth/order");count++;
 children.clear();if(cache.sunlit$getChildrenArray(children).length!=0)throw new AssertionError("Child clear");count++;
 var active=dev.sunlit.bettersunlit.client.LegacyBiomeCache.class.getDeclaredField("ACTIVE");active.setAccessible(true);
 @SuppressWarnings("unchecked") var slot=(ThreadLocal<Object>)active.get(null);
 try(var outer=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()) {
  if(slot.get()!=outer)throw new AssertionError("Biome scope not installed");count++;
  try(var inner=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()){if(slot.get()!=inner)throw new AssertionError("Nested biome scope not isolated");count++;}
  if(slot.get()!=outer)throw new AssertionError("Biome scope not restored");count++;
  var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{if(!executor.submit(()->slot.get()==null).get())throw new AssertionError("Biome scope escaped thread");count++;}finally{executor.shutdownNow();}
 }
 if(slot.get()!=null)throw new AssertionError("Biome scope leaked after return");count++;
 try(var scope=dev.sunlit.bettersunlit.client.LegacyBiomeCache.open()){throw new IllegalArgumentException("expected");}catch(IllegalArgumentException expected){}
 if(slot.get()!=null)throw new AssertionError("Biome scope leaked after exception");count++;
 String legacyKey=shaderKey(false),modernKey=shaderKey(true);
 if(legacyKey==null||modernKey==null||legacyKey.equals(modernKey))throw new AssertionError("Shader cache crossed transform engine versions");count++;
 for(String helper:new String[]{"EmfRenderPerf","GlslTransformDiskCache","IrisDimensionPerf","IrisAstCachePerf"}) {
  for(var method:node("dev.sunlit.bettersunlit.client."+helper).methods)for(var insn:method.instructions) {
   String owner=null,name=null,desc=null;boolean field=false;
   if(insn instanceof MethodInsnNode call){owner=call.owner;name=call.name;desc=call.desc;}
   if(insn instanceof FieldInsnNode access){owner=access.owner;name=access.name;desc=access.desc;field=true;}
   if(owner!=null&&(owner.startsWith("net/caffeinemc/")||owner.startsWith("org/embeddedt/embeddium/")||owner.startsWith("net/irisshaders/")||owner.startsWith("dev/djefrey/colorwheel/"))) {
    if(!exists(node(owner),name,desc,field))throw new AssertionError("Legacy helper ABI mismatch "+helper+" -> "+owner+"."+name+desc);count++;
   }
  }
 }
 var gate=new dev.sunlit.bettersunlit.inline.EntryMixinPlugin();for(String name:args){var mix=node("dev.sunlit.fishvisuals.mixin."+name);var anns=mix.invisibleAnnotations;String target=null;var all=new ArrayList<AnnotationNode>();if(anns!=null)all.addAll(anns);if(mix.visibleAnnotations!=null)all.addAll(mix.visibleAnnotations);for(var a:all)if(a.desc.endsWith("/Mixin;"))for(int i=0;i<a.values.size();i+=2)if(a.values.get(i).equals("targets")||a.values.get(i).equals("value")){Object t=((List<?>)a.values.get(i+1)).get(0);target=t instanceof Type ty?ty.getClassName():t.toString();}if(!gate.canApplyGate(target,name))throw new AssertionError("Legacy optimization blocked: "+name);var original=node(target);for(var f:mix.fields)if(ann(f.visibleAnnotations,"/Shadow")||ann(f.invisibleAnnotations,"/Shadow")){if(!exists(original,f.name,f.desc,true))throw new AssertionError(name+" missing field "+f.name+f.desc);count++;}for(var m:mix.methods){for(var insn:m.instructions)if(insn instanceof MethodInsnNode call && call.owner.startsWith("pigcart/")){if(!exists(node(call.owner),call.name,call.desc,false))throw new AssertionError("Missing legacy API "+call.owner+"."+call.name+call.desc);} }for(var m:mix.methods)if(ann(m.visibleAnnotations,"/Overwrite")||ann(m.invisibleAnnotations,"/Overwrite")||ann(m.visibleAnnotations,"/Shadow")||ann(m.invisibleAnnotations,"/Shadow")){if(!exists(original,m.name,m.desc,false))throw new AssertionError(name+" missing method "+m.name+m.desc);count++;}if(isolatedGate(target,name,false,true,false))throw new AssertionError("Altered target allowed "+name);count++;
 if(isolatedGate(target,name,true,false,false))throw new AssertionError("Missing dependency allowed "+name);count++;
 String modernName=name.replace("LegacyParticleSpawnerMixin","PerfParticleSpawnerMixin").replace("LegacyCustomParticleMixin","PerfCustomParticleMixin").replace("LegacyLevelRendererWeatherMixin","PerfLevelRendererWeatherMixin").replace("LegacyEmfModelPartMixin","PerfEmfModelPartMixin").replace("LegacyClrwlTransformPatcherMixin","PerfClrwlTransformPatcherMixin");
 if(!isolatedGate(target,modernName,false,false,true))throw new AssertionError("Existing-pack optimization disabled "+modernName);count++;
 if(name.startsWith("Legacy")&&isolatedGate(target,name,false,false,true))throw new AssertionError("Legacy path enabled on modern pack "+name);
 System.out.println(name+" legacy+modern+missing+changed contracts OK");}for(String[] absent:new String[][]{{"PerfWindManagerMixin","pigcart.particlerain.WindManager"},{"PerfVoxyAsyncUploaderMixin","me.cortex.voxy.client.core.gl.AsyncTextureUploader"},{"PerfGbfMolangCompilerMixin","com.moepus.gbf.molang.compiler.MolangCompiler"},{"PerfEmfLodHandlerMixin","traben.entity_model_features.utils.EMFLODHandler"},{"PerfClrwlComputeTransformPatcherMixin","dev.djefrey.colorwheel.compile.transform.ClrwlComputeTransformPatcher"}}){if(l.getResource(absent[1].replace('.','/')+".class")!=null || gate.canApplyGate(absent[1],absent[0]))throw new AssertionError("Absent optimization allowed "+absent[0]);count++;}if(gate.canApplyGate("net.minecraft.client.renderer.block.BlockModelShaper","PerfObeModelMixin")||gate.canApplyGate("me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask","PerfChunkBuilderMeshingTaskMixin")||gate.canApplyGate("net.minecraft.client.renderer.texture.SpriteContents","PerfSpriteContentsTransparencyMixin"))throw new AssertionError("Unavailable OBE/Embeddium feature forced");count+=3;System.out.println("PASS "+count+" real legacy member/dependency contracts; all 17 base-pack paths enabled and absent targets rejected");}}
