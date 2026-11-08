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
    "org/spongepowered/mixin/0.8.5", "com/google/guava/guava/31.1-jre",
    "com/google/code/gson/gson/2.10", "org/joml/joml/1.10.5",
    "org/apache/logging/log4j/log4j-api/2.19.0", "org/apache/logging/log4j/log4j-core/2.19.0",
    "org/slf4j/slf4j-api/2.0.1", "com/mojang/logging/1.1.1",
    "io/netty/netty-buffer/4.1.82.Final", "io/netty/netty-common/4.1.82.Final",
    "io/netty/netty-transport/4.1.82.Final", "io/netty/netty-codec/4.1.82.Final",
    "io/netty/netty-handler/4.1.82.Final", "com/mojang/brigadier/1.1.8",
    "com/mojang/datafixerupper/6.0.8", "com/mojang/authlib/4.0.43",
    "it/unimi/dsi/fastutil/8.5.9", "org/apache/commons/commons-lang3/3.12.0",
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
if side=='server':
 sources=[s for s in sources if '/client/' not in s.as_posix() and s.name not in ['ItemEntityRendererMixin.java','ItemPickupParticleMixin.java']]
 common=sourceRoot/'dev/sunlit/fishvisuals/SunlitFishVisuals.java';text=common.read_text(encoding='utf8');old='context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,\n                    () -> () -> dev.sunlit.fishvisuals.client.ClientFishVisuals.receive(packet)));'
 assert old in text;text=text.replace(old,'// Presentation packets are never handled on a dedicated server.');generated=BUILD/'generated/dev/sunlit/fishvisuals/SunlitFishVisuals.java';generated.parent.mkdir(parents=True,exist_ok=True);generated.write_text(text,encoding='utf8');sources=[generated if s==common else s for s in sources]
compileArgs=['--release','17','-proc:none','-encoding','UTF-8','-classpath',';'.join(str(s) for s in classpath),'-d',str(CLASSES)]+list(map(str,sources))
argfile=BUILD/'javac.args';argfile.write_text('\n'.join('"'+s.replace('\\','/').replace('"','\\"')+'"' for s in compileArgs),encoding='utf8');subprocess.run([JDK/'javac.exe','@'+str(argfile.resolve())],check=True)
resources=BUILD/'resources'
if resources.exists():shutil.rmtree(resources)
shutil.copytree(ROOT/'src/main/resources',resources)
asset=resources/'assets/better_sunlit';asset.mkdir(parents=True,exist_ok=True)
maint=BUILD/'maintainer-tools';maint.mkdir(exist_ok=True);subprocess.run([JDK/'javac.exe','--release','17','-d',maint,ROOT/'tools/SignRelease.java'],check=True)
args.keys.mkdir(parents=True,exist_ok=True);secret=args.keys/(side+'.pk8');pub=ROOT/'keys'/(side+'-public.der');pub.parent.mkdir(exist_ok=True)
if not secret.exists():
 if pub.exists():raise RuntimeError('Public key exists but signing key is missing; restore the original private key')
 subprocess.run([JDK/'java.exe','-cp',maint,'SignRelease','keygen',secret,pub],check=True)
if not pub.exists():raise RuntimeError('Missing public key; do not silently rotate trust')
shutil.copy2(pub,asset/'update-public-key.der')
identity=f'build={args.build}\nlabel={args.label}\nchannel={side}\npack=sunlit-valley\ntrack=main\n'
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
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nMixinConfigs: sunlit_fish_visuals.mixins.json\r\n\r\n')
 for base in [CLASSES,resources]:
  for f in sorted(base.rglob('*')):
   if f.is_file():z.write(f,f.relative_to(base).as_posix())
if side=='server':
 with zipfile.ZipFile(jar) as z:
  assert not any('/client/' in n or 'ItemEntityRendererMixin' in n or 'ItemPickupParticleMixin' in n for n in z.namelist())
  for n in z.namelist():
   if n.endswith('.class'):assert b'net/minecraft/client/' not in z.read(n),n
report={'side':side,'build':args.build,'label':args.label,'size':jar.stat().st_size,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'helperSha256':helperHash};(BUILD/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report))
