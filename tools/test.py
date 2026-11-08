from pathlib import Path
import subprocess,os
root=Path(__file__).resolve().parents[1];jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'))/'bin'
for side in ['client','server']:
 jar=root/'build'/side/'better-sunlit.jar';out=root/'build'/side/'tests';out.mkdir(exist_ok=True)
 subprocess.run([jdk/'javac.exe','--release','17','-encoding','UTF-8','-cp',jar,'-d',out,*sorted((root/'tests').glob('*.java'))],check=True)
 for test in ['UpdaterTests','ServerRecoveryTests']:
  subprocess.run([jdk/'java.exe','-cp',str(out)+os.pathsep+str(jar),test,root/'build'/side/'update-helper.jar'],check=True)
