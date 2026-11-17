"""Check both installed EMF versions against the built compatibility helper."""
from pathlib import Path
import os, subprocess, zipfile

root = Path(__file__).resolve().parents[1]
instance = root.parents[1]
legacy = Path(os.environ.get('SUNLIT_LEGACY_INSTANCE', str(instance.parent/'Society- Sunlit Valley')))
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'))/'bin'
args = (root/'build/client/javac.args').read_text(encoding='utf8').splitlines()
cp = args[args.index('"-classpath"')+1].strip('"').split(os.pathsep)
cp = [str(root/'build/client/better-sunlit.jar')] + [p for p in cp if Path(p).name != 'better-sunlit.jar']
out = root/'build/emf-compat/tests'
out.mkdir(parents=True, exist_ok=True)
options = ['--release','17','-proc:none','-encoding','UTF-8','-cp',os.pathsep.join(cp),'-d',str(out),
           *map(str, sorted((root/'tests/emf').glob('*.java')))]
argfile = out/'javac.args'
argfile.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in options), encoding='utf8')
subprocess.run([jdk/'javac.exe','-J-Dfile.encoding=UTF-8','@'+str(argfile)], check=True)
base = [p for p in cp if not Path(p).name.startswith(('entity_model_features','entity_texture_features'))]
(out/'classpath.txt').write_text('\n'.join(base), encoding='utf8')
runtime = [str(out),*cp]
line = 'Class-Path: '+' '.join(Path(p).resolve().as_uri()+('/' if Path(p).is_dir() else '') for p in runtime)
wrapped=[line[:70]];line=line[70:]
while line:wrapped.append(' '+line[:69]);line=line[69:]
bridge=out/'classpath.jar'
with zipfile.ZipFile(bridge,'w') as z:z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\n'+'\r\n'.join(wrapped)+'\r\n\r\n')
old_emf=next((legacy/'minecraft/mods').glob('entity_model_features-3.2.4-*.jar'))
old_etf=next((legacy/'minecraft/mods').glob('entity_texture_features*7.1.jar'))
new_emf=next((instance/'minecraft/mods').glob('entity_model_features-3.3.12-*.jar'))
new_etf=next((instance/'minecraft/mods').glob('entity_texture_features-7.2.7-*.jar'))
subprocess.run([jdk/'java.exe','-Xmx512m','-Djava.awt.headless=true','-cp',bridge,'EmfCompatibilityTests',
                old_emf,old_etf,new_emf,new_etf,out/'classpath.txt',out/'changed'],check=True)
