from pathlib import Path
import argparse,subprocess,zipfile,hashlib,json,shutil,os,re
p=argparse.ArgumentParser();p.add_argument('--instance',type=Path,required=True);p.add_argument('--side',choices=['client','server']);p.add_argument('--build',type=int,default=2026110801);p.add_argument('--label',default='2026.11.08.1');p.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')));p.add_argument('--keys',type=Path,default=Path.home()/'.codex/secrets/better-sunlit');args=p.parse_args()
ROOT=Path(__file__).resolve().parent
INSTANCE=args.instance.resolve();LIBRARIES=INSTANCE.parent.parent/'libraries';JDK=args.java_home/'bin'
side=args.side or dict(line.split('=',1) for line in (ROOT/'release-channel.properties').read_text().splitlines() if '=' in line)['channel']
BUILD=ROOT/'build'/side;CLASSES=BUILD/'classes'
assert CLASSES.resolve().is_relative_to((ROOT/'build').resolve())
if CLASSES.exists():shutil.rmtree(CLASSES)
CLASSES.mkdir(parents=True)
classpath = [(LIBRARIES / "net/minecraftforge/forge/1.20.1-47.4.0") / "forge-1.20.1-47.4.0-client.jar", (LIBRARIES / "net/minecraftforge/forge/1.20.1-47.4.0") / "forge-1.20.1-47.4.0-universal.jar"]
classpath += [LIBRARIES / "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar"]
# Prefer Minecraft 1.20.1's exact library versions where multiple Minecraft installations coexist.
selected = [
    "org/spongepowered/mixin/0.8.5", "org/ow2/asm/asm/9.8", "org/ow2/asm/asm-tree/9.8", "com/google/guava/guava/31.1-jre",
    "com/google/code/gson/gson/2.10", "org/joml/joml/1.10.5",
    "org/lwjgl/lwjgl/3.3.1", "org/lwjgl/lwjgl-opengl/3.3.1",
    "org/apache/logging/log4j/log4j-api/2.19.0", "org/apache/logging/log4j/log4j-core/2.19.0",
    "org/slf4j/slf4j-api/2.0.1", "com/mojang/logging/1.1.1",
    "io/netty/netty-buffer/4.1.82.Final", "io/netty/netty-common/4.1.82.Final",
    "io/netty/netty-transport/4.1.82.Final", "io/netty/netty-codec/4.1.82.Final",
    "io/netty/netty-handler/4.1.82.Final", "com/mojang/brigadier/1.1.8",
    "com/mojang/datafixerupper/6.0.8", "com/mojang/authlib/4.0.43",
    "it/unimi/dsi/fastutil/8.5.9", "org/apache/commons/commons-lang3/3.12.0",
    "com/electronwill/night-config/core/3.6.4", "com/electronwill/night-config/toml/3.6.4",
    "net/minecraftforge/eventbus/6.0.5", "net/minecraftforge/forgespi/7.0.1", "net/minecraftforge/unsafe/0.2.0",
    "net/minecraftforge/mergetool/1.1.5",
    "net/minecraftforge/fmlcore/1.20.1-47.4.0", "net/minecraftforge/fmlloader/1.20.1-47.4.0",
    "net/minecraftforge/javafmllanguage/1.20.1-47.4.0", "cpw/mods/modlauncher/10.0.9",
]
for relative in selected:
    matches = list((LIBRARIES / relative).glob("*.jar"))
    if not matches:
        # A Forge patch may use a different patch version of the same library.
        matches = sorted((LIBRARIES / relative).parent.glob("*/*.jar"), reverse=True)[:1]
    classpath.extend(matches)
classpath += sorted((INSTANCE / "minecraft/mods").glob("*.jar"))

sourceRoot=ROOT/'src/main/java'
sources=sorted(sourceRoot.rglob('*.java'))
CLIENT_ONLY_MIXINS={'ItemEntityRendererMixin.java','ItemPickupParticleMixin.java','XpToastMixin.java','DisplayInfoMixin.java','FontSetMixin.java','StandardFontSetMixin.java'}
CLIENT_ONLY_MIXINS.update({
    'EntryJeiIngredientScopeMixin.java','EntryCoreLibCandidatesMixin.java','EntryJeiTypedCacheMixin.java',
    'PerfButterflyRendererMixin.java','PerfMothRendererMixin.java','PerfButterflyModelMixin.java',
    'PerfButterflyItemRendererMixin.java','PerfMothItemRendererMixin.java','PerfHandbookScreenMixin.java',
    'PerfHandbookIndexButtonMixin.java','PerfHandbookToastMixin.java',
    'PerfParticleSpawnerMixin.java','PerfWeatherParticleMixin.java','PerfCustomParticleMixin.java',
    'PerfSeasonHandlerClientMixin.java','PerfSeasonColorHandlersMixin.java','PerfAmbientBiomeAreaMixin.java',
})
if side=='server':
 sources=[s for s in sources if '/client/' not in s.as_posix() and s.name not in CLIENT_ONLY_MIXINS]
 common=sourceRoot/'dev/sunlit/fishvisuals/SunlitFishVisuals.java';text=common.read_text(encoding='utf8');old='context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,\n                    () -> () -> dev.sunlit.fishvisuals.client.ClientFishVisuals.receive(packet)));'
 assert old in text;text=text.replace(old,'// Presentation packets are never handled on a dedicated server.');generated=BUILD/'generated/dev/sunlit/fishvisuals/SunlitFishVisuals.java';generated.parent.mkdir(parents=True,exist_ok=True);generated.write_text(text,encoding='utf8');sources=[generated if s==common else s for s in sources]
# Generate side-specific, optional localization targets from the reviewed site list.
# A class with any GUI calls belongs entirely to the client mixin on the client.
inlineSites=json.loads((ROOT/'src/main/resources/assets/better_sunlit/inline/rules.json').read_text(encoding='utf8'))['sites']
# Multi-target pseudo mixins still ask Mixin to resolve every advertised target.
# Exclude absent/disabled mods before generating the annotation, rather than
# repeating a failed class lookup whenever another target loads during world join.
if side=='client':
 wanted={s['class']+'.class' for s in inlineSites}
 availableTargets=set()
 for dependency in classpath:
  if dependency.is_file() and wanted:
   with zipfile.ZipFile(dependency) as archive:
    found=wanted.intersection(archive.namelist())
    availableTargets.update(n[:-6] for n in found)
    wanted.difference_update(found)
 inlineSites=[s for s in inlineSites if s['class'] in availableTargets]
clientTargets={s['class'] for s in inlineSites if s['clientOnly']}
inlineLists={'mixins':['InlineScriptSourceMixin'],'client':[]}
for client in [False,True]:
 if client and side=='server':continue
 for interface in [False,True]:
  targets=sorted({s['class'] for s in inlineSites if s['isInterface']==interface and (
    s['class'] in clientTargets if client else
    s['class'] not in clientTargets if side=='client' else s['serverPresent'] and not s['clientOnly'])})
  if not targets:continue
  name='Inline'+('Client' if client else 'Common')+('Interface' if interface else '')+'Mixin'
  targetText=','.join(json.dumps(t.replace('/','.')) for t in targets)
  source='package dev.sunlit.fishvisuals.mixin;\nimport org.spongepowered.asm.mixin.*;\n@Pseudo\n@Mixin(targets={'+targetText+'},remap=false,priority=500)\npublic '+('interface ' if interface else 'abstract class ')+name+' {}\n'
  generated=BUILD/('generated/dev/sunlit/fishvisuals/mixin/'+name+'.java');generated.parent.mkdir(parents=True,exist_ok=True);generated.write_text(source,encoding='utf8');sources.append(generated)
  inlineLists['client' if client else 'mixins'].append(name)
compileArgs=['--release','17','-proc:none','-encoding','UTF-8','-classpath',';'.join(str(s) for s in classpath),'-d',str(CLASSES)]+list(map(str,sources))
argfile=BUILD/'javac.args';argfile.write_text('\n'.join('"'+s.replace('\\','/').replace('"','\\"')+'"' for s in compileArgs),encoding='utf8');subprocess.run([JDK/'javac.exe','@'+str(argfile.resolve())],check=True)
resources=BUILD/'resources'
if resources.exists():shutil.rmtree(resources)
shutil.copytree(ROOT/'src/main/resources',resources)
inlineConfig=resources/'better_sunlit_inline.mixins.json';inlineData=json.loads(inlineConfig.read_text(encoding='utf8'));inlineData.update(inlineLists);inlineData['priority']=500
if side=='server':inlineData.pop('client',None)
inlineConfig.write_text(json.dumps(inlineData,indent=2),encoding='utf8')
if side=='server':
 entryConfig=resources/'better_sunlit_entry.mixins.json';entryData=json.loads(entryConfig.read_text(encoding='utf8'));entryData.pop('client',None);entryConfig.write_text(json.dumps(entryData,indent=2),encoding='utf8')
asset=resources/'assets/better_sunlit';asset.mkdir(parents=True,exist_ok=True)
maint=BUILD/'maintainer-tools';maint.mkdir(exist_ok=True);subprocess.run([JDK/'javac.exe','--release','17','-d',maint,ROOT/'tools/SignRelease.java'],check=True)
args.keys.mkdir(parents=True,exist_ok=True);secret=args.keys/(side+'.pk8');pub=ROOT/'keys'/(side+'-public.der');pub.parent.mkdir(exist_ok=True)
if not secret.exists():
 if pub.exists():raise RuntimeError('Public key exists but signing key is missing; restore the original private key')
 subprocess.run([JDK/'java.exe','-cp',maint,'SignRelease','keygen',secret,pub],check=True)
if not pub.exists():raise RuntimeError('Missing public key; do not silently rotate trust')
shutil.copy2(pub,asset/'update-public-key.der')
identity=f'build={args.build}\nlabel={args.label}\nchannel={side}\npack=sunlit-valley\ntrack=main\n'
rel_notes=[]
for rf in sorted((ROOT/'releases').glob('*.json')):
 data=json.loads(rf.read_text(encoding='utf8'))
 if int(data.get('build',0))==args.build:rel_notes=[f['title']+' :: '+f['detail'] for f in data.get('features',[])]
for i,note in enumerate(rel_notes):
 esc=note.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')
 identity+=f'notes.{i}={esc}\n'
(asset/'update-build.properties').write_text(identity,encoding='utf8')
helper=BUILD/'update-helper.jar'
with zipfile.ZipFile(helper,'w',zipfile.ZIP_DEFLATED) as z:
 for f in sorted((CLASSES/'dev/sunlit/bettersunlit/update').glob('*.class')):z.write(f,f.relative_to(CLASSES).as_posix())
 for name in ['update-build.properties','update-public-key.der']:z.write(asset/name,'assets/better_sunlit/'+name)
helperHash=hashlib.sha256(helper.read_bytes()).hexdigest();shutil.copy2(helper,asset/'update-helper.jar');(asset/'update-build.properties').write_text(identity+'helperSha256='+helperHash+'\n',encoding='utf8')
meta=resources/'META-INF/mods.toml';text=meta.read_text(encoding='utf8').replace('version="1.2.4"','version="1.0.0"').replace('displayName="Sunlit Fishing Visuals"','displayName="Better Sunlit"');meta.write_text(text,encoding='utf8')
if side=='server':
 mix=resources/'sunlit_fish_visuals.mixins.json';data=json.loads(mix.read_text());data.pop('client',None);mix.write_text(json.dumps(data,indent=2),encoding='utf8')
jar=BUILD/'better-sunlit.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nMixinConfigs: sunlit_fish_visuals.mixins.json,\r\n better_sunlit_inline.mixins.json,better_sunlit_entry.mixins.json\r\n\r\n')
 for base in [CLASSES,resources]:
  for f in sorted(base.rglob('*')):
   if f.is_file():z.write(f,f.relative_to(base).as_posix())
if side=='server':
 with zipfile.ZipFile(jar) as z:
  assert not any('/client/' in n or any(m.replace('.java','.class') in n for m in CLIENT_ONLY_MIXINS) for n in z.namelist())
  for n in z.namelist():
   if n.endswith('.class'):assert b'net/minecraft/client/' not in z.read(n),n
report={'side':side,'build':args.build,'label':args.label,'size':jar.stat().st_size,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'helperSha256':helperHash};(BUILD/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report))
