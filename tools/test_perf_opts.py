from pathlib import Path
import json, os, shutil, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
INSTANCE = Path(__file__).resolve().parents[3]
LIBRARIES = INSTANCE.parent.parent / 'libraries'
JDK = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')) / 'bin'

CLIENT_JAR = ROOT / 'build/client/better-sunlit.jar'
SERVER_JAR = ROOT / 'build/server/better-sunlit.jar'

# Test the exact artifacts supplied by the caller. Rebuilding here used to
# overwrite release JARs with build.py's default build number before signing.
assert CLIENT_JAR.is_file(), f'Missing {CLIENT_JAR}; build the client first'
assert SERVER_JAR.is_file(), f'Missing {SERVER_JAR}; build the server first'

# 1. Verify mixins.json consistency & strict client/server separation in built JARs.
mixins_cfg = json.loads((ROOT / 'src/main/resources/better_sunlit_entry.mixins.json').read_text(encoding='utf8'))
common_mixins = mixins_cfg.get('mixins', [])
client_mixins = mixins_cfg.get('client', [])
assert len(common_mixins) == 5, common_mixins
assert len(client_mixins) == 22, client_mixins

with zipfile.ZipFile(CLIENT_JAR) as zf:
    client_names = set(zf.namelist())
with zipfile.ZipFile(SERVER_JAR) as zf:
    server_names = set(zf.namelist())
    for name in server_names:
        assert '/client/' not in name and not name.startswith('dev/sunlit/bettersunlit/client/'), f'Client class leaked into server JAR: {name}'
        if name.endswith('.class'):
            raw = zf.read(name)
            assert b'net/minecraft/client/' not in raw, f'Client Minecraft ref in server JAR class {name}'

for m in common_mixins:
    cls = f'dev/sunlit/fishvisuals/mixin/{m}.class'
    assert cls in client_names, f'Missing common mixin in client JAR: {cls}'
    assert cls in server_names, f'Missing common mixin in server JAR: {cls}'
for m in client_mixins:
    cls = f'dev/sunlit/fishvisuals/mixin/{m}.class'
    assert cls in client_names, f'Missing client mixin in client JAR: {cls}'
    assert cls not in server_names, f'Client mixin leaked into server JAR: {cls}'

# 2. Verify every EntryMixinPlugin.HASHES target & switch arm matches better_sunlit_entry.mixins.json.
plugin_src = (ROOT / 'src/main/java/dev/sunlit/bettersunlit/inline/EntryMixinPlugin.java').read_text(encoding='utf8')
for m in common_mixins + client_mixins:
    assert f'"{m}"' in plugin_src, f'{m} not gated in EntryMixinPlugin.shouldApplyMixin'

# 3. Compile and execute Java verification suite (ASM contracts + functional equivalence + zero-alloc checks).
OUT_CLASSES = ROOT / 'build/test-perf-classes'
if OUT_CLASSES.exists():
    shutil.rmtree(OUT_CLASSES)
OUT_CLASSES.mkdir(parents=True)

def to_cmd_path(path_str: str) -> str:
    p = Path(path_str).resolve()
    try:
        return p.relative_to(INSTANCE).as_posix()
    except ValueError:
        return p.as_posix()

javac_args_lines = [line.strip().strip('"') for line in (ROOT / 'build/client/javac.args').read_text(encoding='utf8').splitlines() if line.strip()]
cp_index = javac_args_lines.index('-classpath')
compile_cp = javac_args_lines[cp_index + 1]
filtered_entries = [to_cmd_path(p) for p in compile_cp.split(os.pathsep) if Path(p).name.lower() != 'better-sunlit.jar']
test_cp = os.pathsep.join([to_cmd_path(str(CLIENT_JAR))] + filtered_entries)

compile_argfile = ROOT / 'build/test-perf-javac.args'
compile_argfile.write_text('\n'.join([
    '"--release"', '"17"', '"-proc:none"', '"-encoding"', '"UTF-8"',
    '"-classpath"', '"' + test_cp + '"',
    '"-d"', '"' + to_cmd_path(str(OUT_CLASSES)) + '"',
    '"' + to_cmd_path(str(ROOT / 'tests/perf/PerfOptTests.java')) + '"',
]), encoding='utf8')
subprocess.run([str(JDK / 'javac.exe'), '@' + to_cmd_path(str(compile_argfile))], cwd=str(INSTANCE), check=True)

run_cp = to_cmd_path(str(OUT_CLASSES)) + os.pathsep + test_cp
run_argfile = ROOT / 'build/test-perf-java.args'
run_argfile.write_text('\n'.join([
    '"-Dfile.encoding=UTF-8"',
    '"-cp"', '"' + run_cp + '"',
    '"dev.sunlit.bettersunlit.tests.perf.PerfOptTests"',
]), encoding='utf8')
res = subprocess.run([str(JDK / 'java.exe'), '@' + to_cmd_path(str(run_argfile))], cwd=str(INSTANCE), capture_output=True, text=True, encoding='utf8', errors='replace')
print(res.stdout or '', end='')
if res.returncode != 0:
    print(res.stderr or '', end='')
    sys.exit(res.returncode)
