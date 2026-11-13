"""Test publication ordering using a local Git remote and simulated releases."""
from pathlib import Path
from unittest import mock
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'tools' / 'publish.py'
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location('better_sunlit_publish_flow', SCRIPT)
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class PublishFlowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='better-sunlit-publish-test-')
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '-b', 'client')
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'release-test@example.invalid')
        (self.root/'feed').mkdir()
        (self.root/'feed/update.properties').write_bytes(b'old feed\n')
        self.git('add', 'feed/update.properties')
        self.git('commit', '-m', 'initial')
        self.remote = Path(self.temp.name) / 'remote.git'
        subprocess.run(['git', 'init', '--bare', self.remote], check=True, capture_output=True)
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', '-u', 'origin', 'client')

        self.build = self.root/'build/client'
        self.release = self.build/'release'
        self.release.mkdir(parents=True)
        self.jar = self.build/'better-sunlit.jar'
        self.jar.write_bytes(b'verified JAR')
        (self.release/'better-sunlit.jar').write_bytes(self.jar.read_bytes())
        (self.release/'update.properties').write_bytes(b'signed new feed\n')
        (self.release/'notes.md').write_text('notes', encoding='utf8')
        (self.release/'SHA256SUMS.txt').write_text('hashes', encoding='utf8')
        # Build outputs are ignored just as in the real repository.
        (self.root/'.git/info/exclude').write_text('build/\n', encoding='utf8')
        self.args = SimpleNamespace(side='client')
        self.tag = 'client-build-2026.11.14.1'
        self.prepared = (self.build, {'build': 2026111401},
                         {'title': 'Title', 'label': '2026.11.14.1'},
                         self.jar, self.tag, self.release, Path('fake-java.exe'), 'fake-classpath')
        self.events = []
        self.corrupt_download = False

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args, cwd=None):
        return subprocess.run(['git', *args], cwd=cwd or self.root, check=True,
                              capture_output=True, text=True, encoding='utf8').stdout.strip()

    def execute(self):
        original_run = publish.run
        original_output = publish.output

        def fake_run(command, **kwargs):
            command = list(map(str, command))
            if command[:3] == ['gh', 'release', 'create']:
                self.events.append('create')
                self.assertEqual(self.git('show', 'origin/client:feed/update.properties'), 'old feed')
                return subprocess.CompletedProcess(command, 0)
            if command[:3] == ['gh', 'release', 'download']:
                self.events.append('download')
                dest = Path(command[command.index('--dir') + 1])
                (dest/'better-sunlit.jar').write_bytes(b'corrupt' if self.corrupt_download else self.jar.read_bytes())
                (dest/'update.properties').write_bytes((self.release/'update.properties').read_bytes())
                return subprocess.CompletedProcess(command, 0)
            if command[0] == 'fake-java.exe':
                self.events.append('verify')
                return subprocess.CompletedProcess(command, 0)
            return original_run(command, **kwargs)

        def fake_output(command):
            if command[0] == 'gh':
                return json.dumps([[]])
            return original_output(command)

        def public_feed(request, timeout):
            self.events.append('public-feed')
            content = subprocess.check_output(['git', '--git-dir', self.remote, 'show',
                                               'client:feed/update.properties'])
            return io.BytesIO(content)

        with mock.patch.object(publish, 'ROOT', self.root), \
             mock.patch.object(publish, 'run', side_effect=fake_run), \
             mock.patch.object(publish, 'output', side_effect=fake_output), \
             mock.patch.object(publish, 'origin_feed', side_effect=lambda side: subprocess.check_output(
                 ['git', '--git-dir', self.remote, 'show', 'client:feed/update.properties'])), \
             mock.patch.object(publish.urllib.request, 'urlopen', side_effect=public_feed):
            publish.publish(self.args, self.prepared)

    def test_create_then_feed_push_then_redownload_and_verify(self):
        self.execute()
        self.assertEqual(self.events, ['create', 'download', 'verify', 'public-feed', 'public-feed', 'verify'])
        self.assertEqual(self.git('show', 'origin/client:feed/update.properties'), 'signed new feed')
        self.assertEqual(self.git('status', '--porcelain'), '')

    def test_bad_public_jar_is_reported_after_feed_push(self):
        self.corrupt_download = True
        with self.assertRaisesRegex(RuntimeError, '공개 JAR'):
            self.execute()
        self.assertEqual(self.events, ['create', 'download'])
        self.assertEqual(self.git('show', 'origin/client:feed/update.properties'), 'signed new feed')

    def test_remote_change_after_fetch_blocks_release_creation(self):
        other = Path(self.temp.name)/'other'
        subprocess.run(['git', 'clone', '--branch', 'client', self.remote, other], check=True, capture_output=True)
        self.git('config', 'user.name', 'Other', cwd=other)
        self.git('config', 'user.email', 'other@example.invalid', cwd=other)
        (other/'new.txt').write_text('remote change', encoding='utf8')
        self.git('add', 'new.txt', cwd=other)
        self.git('commit', '-m', 'remote change', cwd=other)
        self.git('push', 'origin', 'client', cwd=other)
        with self.assertRaisesRegex(RuntimeError, '원격 브랜치가 변경'):
            self.execute()
        self.assertEqual(self.events, [])


if __name__ == '__main__':
    unittest.main()
