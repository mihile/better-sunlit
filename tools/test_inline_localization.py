"""Verify pinned localization against installed JARs without launching Minecraft."""
from pathlib import Path
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
instance = root.parent.parent
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')) / 'bin'
args = (root/'build/client/javac.args').read_text(encoding='utf8').splitlines()
original = args[args.index('"-classpath"')+1].strip('"').split(os.pathsep)
libraries = instance.parent.parent/'libraries'
extra = list((libraries/'org/ow2/asm/asm-analysis/9.8').glob('*.jar'))
assert len(extra) == 1
out = root/'build/inline-tests'
out.mkdir(exist_ok=True)

for side in ['client', 'server']:
    mods = instance/'minecraft/mods' if side == 'client' else Path('C:/Sunlit_Valley/mods')
    scripts = mods.parent/'kubejs'
    cp = [out, root/'build/client/better-sunlit.jar', *extra]
    # The vanilla SRG classes allow testing text semantics without Forge bootstrap.
    cp += [p for p in original if Path(p).name.endswith('-srg.jar')]
    cp += list(mods.glob('*.jar'))
    cp = [p for p in cp if Path(p).resolve() != (mods/'better-sunlit.jar').resolve()]
    cp += [p for p in original if Path(p).parent.resolve() != (instance/'minecraft/mods').resolve()]
    if side == 'client':
        options = ['--release', '17', '-proc:none', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, cp)),
                   '-d', out, *sorted((root/'tests/inline').glob('*.java'))]
        argfile = out/'javac.args'
        argfile.write_text('\n'.join('"'+str(a).replace('\\','/').replace('"','\\"')+'"' for a in options), encoding='utf8')
        subprocess.run([jdk/'javac.exe', '-J-Dfile.encoding=UTF-8', '@'+str(argfile)], check=True)
    urls = ' '.join(Path(p).resolve().as_uri()+('/' if Path(p).is_dir() else '') for p in cp)
    line = 'Class-Path: '+urls
    wrapped = [line[:70]]
    line = line[70:]
    while line:
        wrapped.append(' '+line[:69])
        line = line[69:]
    bridge = out/('classpath-'+side+'.jar')
    with zipfile.ZipFile(bridge, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n'+'\r\n'.join(wrapped)+'\r\n\r\n')
    result = subprocess.run([jdk/'java.exe', '-Xmx2G', '-Djava.awt.headless=true', '-cp', bridge,
                            'InlineLocalizationTests', mods, side, scripts, root/'build'/side/'better-sunlit.jar'],
                            capture_output=True, encoding='utf8', errors='replace')
    (out/(side+'.log')).write_text(result.stdout+'\n'+result.stderr, encoding='utf8')
    print('\n'.join(result.stdout.splitlines()[-3:]))
    if result.returncode:
        print('\n'.join(result.stderr.splitlines()[-20:]))
        result.check_returncode()
