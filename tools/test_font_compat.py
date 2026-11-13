"""Font regressions against the exact installed libraries from the client build."""
from pathlib import Path
import os
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')) / 'bin'
args = (root / 'build/client/javac.args').read_text(encoding='utf8').splitlines()
classpath = args[args.index('"-classpath"') + 1].strip('"')
original_paths = [Path(p) for p in classpath.split(os.pathsep)]
# The installed addon is the OLD build until all tests pass.
classpath = os.pathsep.join([str(root / 'build/client/better-sunlit.jar')] +
                          [p for p in classpath.split(os.pathsep) if Path(p).name != 'better-sunlit.jar'])
icu = root.parents[3] / 'libraries/com/ibm/icu/icu4j/71.1/icu4j-71.1.jar'
assert icu.is_file(), 'Minecraft 1.20.1 ICU library is missing'
classpath += os.pathsep + str(icu)
out = root / 'build/client/font-tests'
out.mkdir(exist_ok=True)

def run(tool, arguments):
    argfile = out / (tool + '.args')
    argfile.write_text('\n'.join('"' + str(a).replace('\\', '/').replace('"', '\\"') + '"'
                                 for a in arguments), encoding='utf8')
    subprocess.run([jdk / (tool + '.exe'), '@' + str(argfile)],
                   cwd=root / 'build/client', check=True)

run('javac', ['--release', '17', '-proc:none', '-encoding', 'UTF-8', '-cp', classpath,
              '-d', out, *sorted((root / 'tests/font').glob('*.java'))])
# A manifest bridge avoids both Windows' command limit and Java's native-encoding
# @file decoding of Korean paths. Manifest URLs are ASCII and the manifest is UTF-8.
urls = ' '.join(Path(p).resolve().as_uri() for p in [str(out), *classpath.split(os.pathsep)])
urls = urls.replace(out.resolve().as_uri(), out.resolve().as_uri() + '/', 1)
line = 'Class-Path: ' + urls
wrapped = [line[:70]]
line = line[70:]
while line:
    wrapped.append(' ' + line[:69])
    line = line[69:]
bridge = out / 'test-classpath.jar'
with zipfile.ZipFile(bridge, 'w') as jar:
    jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n' + '\r\n'.join(wrapped) + '\r\n\r\n')
subprocess.run([jdk / 'java.exe', '-Djava.awt.headless=true', '-cp', str(bridge), 'FontCompatTests'],
               cwd=root / 'build/client', check=True)
with zipfile.ZipFile(root / 'build/server/better-sunlit.jar') as jar:
    assert not any('/client/' in n or 'FontSetMixin' in n for n in jar.namelist())
print('PASS: font helpers and mixins excluded from dedicated-server JAR')

# Verify every injected descriptor against the installed, pinned binaries without
# initializing Minecraft classes. This catches silently ineffective target changes.
for mixin, target, jar in [
    ('FontSetMixin', 'net.minecraft.client.gui.font.FontSet',
     next(p for p in original_paths if p.name.endswith('-srg.jar'))),
    ('StandardFontSetMixin', 'icyllis.modernui.mc.text.StandardFontSet',
     next(p for p in original_paths if p.name.startswith('ModernUI-'))),
]:
    binary = subprocess.run([jdk/'javap.exe', '-p', '-s', '-classpath', str(jar), target],
                            check=True, capture_output=True, text=True, encoding='utf8').stdout
    methods = {name + descriptor for name, descriptor in
               re.findall(r'([\w$]+)\([^;\n]*\);\s+descriptor:\s+(\S+)', binary)}
    source = (root / 'src/main/java/dev/sunlit/fishvisuals/mixin' / (mixin + '.java')).read_text(encoding='utf8')
    for method in re.findall(r'@Inject\(method = "([^"]+)"', source):
        assert method in methods, f'{mixin}: target method missing: {method}'
print('PASS: all six font injection targets match installed Minecraft/ModernUI descriptors')
