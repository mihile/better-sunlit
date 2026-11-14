"""Run offline checks on scoped candidate filtering and reviewed entry-time patches."""
from pathlib import Path
import os,subprocess,zipfile
root=Path(__file__).resolve().parents[1]
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'))/'bin'
args=(root/'build/client/javac.args').read_text(encoding='utf8').splitlines()
cp=args[args.index('"-classpath"')+1].strip('"').split(os.pathsep)
cp=[str(root/'build/client/better-sunlit.jar')]+[p for p in cp if Path(p).name!='better-sunlit.jar']
libraries=root.parents[3]/'libraries'
cp += [str(p) for p in (libraries/'com/google/guava/failureaccess/1.0.1').glob('*.jar')]
out=root/'build/world-entry/tests';out.mkdir(parents=True,exist_ok=True)
options=['--release','17','-proc:none','-encoding','UTF-8','-cp',os.pathsep.join(cp),'-d',out,*sorted((root/'tests/entry').glob('*.java'))]
argfile=out/'javac.args';argfile.write_text('\n'.join('"'+str(a).replace('\\','/').replace('"','\\"')+'"' for a in options),encoding='utf8')
subprocess.run([jdk/'javac.exe','-J-Dfile.encoding=UTF-8','@'+str(argfile)],check=True)
urls=' '.join(Path(p).resolve().as_uri()+('/' if Path(p).is_dir() else '') for p in [out,*cp]);line='Class-Path: '+urls;wrapped=[line[:70]];line=line[70:]
while line:wrapped.append(' '+line[:69]);line=line[69:]
bridge=out/'classpath.jar'
with zipfile.ZipFile(bridge,'w') as z:z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\n'+'\r\n'.join(wrapped)+'\r\n\r\n')
result=subprocess.run([jdk/'java.exe','-Xmx512m','-Djava.awt.headless=true','-cp',bridge,'WorldEntryTests'],capture_output=True,text=True,encoding='utf8',errors='replace')
(out/'results.txt').write_text(result.stdout+'\n'+result.stderr,encoding='utf8');print(result.stdout)
if result.returncode:print(result.stderr);result.check_returncode()
