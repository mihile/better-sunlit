"""Validate original-pack optimization contracts without launching Minecraft."""
from pathlib import Path
import os, subprocess, zipfile, hashlib
root=Path(__file__).resolve().parents[1]
instance=root.parents[1]
base=Path(os.environ.get('SUNLIT_LEGACY_INSTANCE', str(instance.parent/'Society- Sunlit Valley')))
out=root/'build/base-optimizations';out.mkdir(parents=True,exist_ok=True)
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'))/'bin'
a=(root/'build/client/javac.args').read_text(encoding='utf8').splitlines()
modern_cp=[str(root/'build/client/better-sunlit.jar')]+[p for p in a[a.index('"-classpath"')+1].strip('"').split(os.pathsep) if Path(p).name!='better-sunlit.jar']
(out/'modern-classpath.txt').write_text('\n'.join(modern_cp),encoding='utf8')
cp=[str(root/'build/client/better-sunlit.jar')]+[p for p in a[a.index('"-classpath"')+1].strip('"').split(os.pathsep) if not Path(p).resolve().is_relative_to(instance)]
def nested_archives(data,depth=0):
 import io
 if depth>3:return
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  for name in z.namelist():
   if name.endswith('.jar'):
    content=z.read(name);path=out/(hashlib.sha256(content).hexdigest()+'.jar')
    path.write_bytes(content);cp.append(str(path));nested_archives(content,depth+1)
for jar in sorted((base/'minecraft/mods').glob('*.jar')):
 if jar.name=='better-sunlit.jar':continue
 cp.append(str(jar));nested_archives(jar.read_bytes())
(out/'base-classpath.txt').write_text('\n'.join(cp),encoding='utf8')
opts=['--release','17','-proc:none','-encoding','UTF-8','-cp',os.pathsep.join(cp),'-d',str(out),str(root/'tests/base/BaseOptimizationContracts.java'),str(root/'tests/base/RendererContracts.java'),str(root/'tests/base/RendererTestUniform.java')]
f=out/'javac.args';f.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in opts),encoding='utf8')
subprocess.run([jdk/'javac.exe','-J-Dfile.encoding=UTF-8','@'+str(f)],check=True)
line='Class-Path: '+' '.join(Path(p).resolve().as_uri()+('/' if Path(p).is_dir() else '') for p in [str(out),*cp]);lines=[line[:70]];line=line[70:]
while line:lines.append(' '+line[:69]);line=line[69:]
bridge=out/'classpath.jar'
with zipfile.ZipFile(bridge,'w') as z:z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\n'+'\r\n'.join(lines)+'\r\n\r\n')
names=['PerfIrisBlockMappingMixin','LegacyParticleSpawnerMixin','PerfWeatherParticleMixin','LegacyCustomParticleMixin','PerfIrisDimensionMixin','PerfEmfCubeMixin','LegacyEmfModelPartMixin','PerfGraphOrderMixin','PerfPipelineManagerMixin','PerfTransformPatcherMixin','PerfEmfModelPartVariableMixin','LegacyLevelRendererWeatherMixin','PerfIrisLoadingCompleteMixin','PerfIrisAsyncLoadMixin','PerfIrisStandardMacrosMixin','PerfIrisRenderSystemMixin','LegacyClrwlTransformPatcherMixin']
subprocess.run([jdk/'java.exe','-Xmx512m','-Dsunlit.base.classpath='+str(out/'base-classpath.txt'),'-Dsunlit.modern.classpath='+str(out/'modern-classpath.txt'),'-cp',bridge,'BaseOptimizationContracts',*names],check=True)

subprocess.run([jdk/'java.exe','-Xmx512m','-Dsunlit.base.classpath='+str(out/'base-classpath.txt'),'-Dsunlit.modern.classpath='+str(out/'modern-classpath.txt'),'-cp',bridge,'RendererContracts'],check=True)
