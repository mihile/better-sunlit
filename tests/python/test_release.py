"""Exercise release orchestration against isolated local Git repositories."""
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

SCRIPT = Path(__file__).resolve().parents[2] / 'tools' / 'release.py'
spec = importlib.util.spec_from_file_location('better_sunlit_release', SCRIPT)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
publish_spec = importlib.util.spec_from_file_location('better_sunlit_publish', SCRIPT.parent / 'publish.py')
publish = importlib.util.module_from_spec(publish_spec)
publish_spec.loader.exec_module(publish)


class ReleaseGitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='better-sunlit-release-test-')
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '-b', 'client')
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'release-test@example.invalid')
        self.write('feed/update.properties', 'client-old\n')
        self.write('release-channel.properties', 'channel=client\n')
        self.write('shared.txt', 'base\n')
        self.write('releases/20261114.1.json', json.dumps({
            'build': 2026111401, 'label': '2026.11.14.1', 'title': 'Title',
            'features': [{'title': 'Feature', 'detail': 'Description'}],
        }))
        self.commit('base')
        self.git('checkout', '-b', 'server')
        self.write('feed/update.properties', 'server-old\n')
        self.write('release-channel.properties', 'channel=server\n')
        self.commit('server channel')
        self.git('checkout', 'client')
        self.remote = Path(self.temp.name) / 'remote.git'
        subprocess.run(['git', 'init', '--bare', str(self.remote)], check=True, capture_output=True)
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', '-u', 'origin', 'client', 'server')
        self.root_patch = mock.patch.object(release, 'ROOT', self.root)
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, check=True, capture_output=True,
                              text=True, encoding='utf8').stdout.strip()

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf8')

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-m', message)

    def test_merge_preserves_both_changes_and_each_channel(self):
        self.write('client-added.txt', 'client feature\n')
        self.commit('client change')
        self.git('checkout', 'server')
        self.write('server-added.txt', 'server feature\n')
        self.commit('server change')
        self.git('checkout', 'client')

        release.sync_from('server')
        self.assertEqual((self.root/'feed/update.properties').read_text(), 'client-old\n')
        self.assertEqual((self.root/'release-channel.properties').read_text(), 'channel=client\n')
        self.assertTrue((self.root/'server-added.txt').exists())
        self.git('checkout', 'server')
        release.sync_from('client')
        self.assertEqual((self.root/'feed/update.properties').read_text(), 'server-old\n')
        self.assertEqual((self.root/'release-channel.properties').read_text(), 'channel=server\n')
        self.assertTrue((self.root/'client-added.txt').exists())
        release.assert_shared_equal('client', 'server')

    def test_conflicting_shared_changes_abort_without_overwriting(self):
        self.write('shared.txt', 'client version\n')
        self.commit('client edit')
        self.git('checkout', 'server')
        self.write('shared.txt', 'server version\n')
        self.commit('server edit')
        self.git('checkout', 'client')
        with self.assertRaisesRegex(RuntimeError, 'shared.txt'):
            release.sync_from('server')
        self.assertEqual((self.root/'shared.txt').read_text(), 'client version\n')
        self.assertEqual(self.git('status', '--porcelain'), '')
        self.assertEqual(self.git('show', 'server:shared.txt'), 'server version')

    def test_both_build_and_test_once_before_either_publish(self):
        self.write('client-added.txt', 'client feature\n')
        self.commit('client change')
        self.git('checkout', 'server')
        self.write('server-added.txt', 'server feature\n')
        self.commit('server change')
        self.git('checkout', 'client')
        events = []
        actual_run = release.run

        def run_stub(command, *, check=True):
            if str(command[0]) == sys.executable:
                if 'tools/test.py' in command:
                    events.append('test')
                elif 'tools/publish.py' in command and '--prepare-only' in command:
                    events.append('prepare-' + command[command.index('--side') + 1])
                else:
                    self.fail(f'Unexpected command: {command}')
                return subprocess.CompletedProcess(command, 0)
            return actual_run(command, check=check)

        def publish_stub(side, changes):
            self.assertEqual(events[:4], ['build', 'test', 'prepare-client', 'prepare-server'])
            self.assertEqual(self.git('branch', '--show-current'), side)
            events.append('publish-' + side)
            self.write('feed/update.properties', side + '-new\n')
            self.commit('Publish ' + side)

        with mock.patch.object(release, 'known_remote_builds', return_value={'client': 2026110804, 'server': 2026110804}), \
             mock.patch.object(release, 'build_both', side_effect=lambda data: events.append('build')), \
             mock.patch.object(release, 'verify_artifacts'), \
             mock.patch.object(release, 'run', side_effect=run_stub), \
             mock.patch.object(release, 'publish_side', side_effect=publish_stub):
            release.release('both')

        self.assertEqual(events, ['build', 'test', 'prepare-client', 'prepare-server',
                                  'publish-client', 'publish-server'])
        self.assertEqual(self.git('show', 'client:feed/update.properties'), 'client-new')
        self.assertEqual(self.git('show', 'server:feed/update.properties'), 'server-new')
        self.assertEqual(self.git('show', 'server:release-channel.properties'), 'channel=server')
        release.assert_shared_equal('client', 'server')

    def test_test_failure_stops_before_publication(self):
        actual_run = release.run
        published = []

        def run_stub(command, *, check=True):
            if str(command[0]) == sys.executable and 'tools/test.py' in command:
                raise subprocess.CalledProcessError(1, command)
            return actual_run(command, check=check)

        with mock.patch.object(release, 'known_remote_builds', return_value={'client': 0, 'server': 0}), \
             mock.patch.object(release, 'build_both'), \
             mock.patch.object(release, 'run', side_effect=run_stub), \
             mock.patch.object(release, 'publish_side', side_effect=lambda *args: published.append(args)):
            with self.assertRaises(subprocess.CalledProcessError):
                release.release('both')
        self.assertEqual(published, [])
        self.assertEqual(self.git('show', 'origin/client:feed/update.properties'), 'client-old')
        self.assertEqual(self.git('show', 'origin/server:feed/update.properties'), 'server-old')

    def test_concurrent_release_lock_is_rejected(self):
        with release.release_lock():
            with self.assertRaisesRegex(RuntimeError, '실행 중'):
                with release.release_lock():
                    self.fail('second lock should not be acquired')

    def test_new_version_exceeds_both_channels_and_keeps_feature_notes(self):
        with mock.patch.object(release, 'ROOT', self.root):
            path, data = release.choose_release(['client', 'server'],
                                                {'client': 2026110804, 'server': 2026111401})
        self.assertGreater(data['build'], 2026111401)
        self.assertEqual(data['features'][0]['detail'], 'Description')
        self.assertTrue(path.is_file())
        self.assertTrue((self.root/'releases/20261114.1.json').is_file())

    def test_remote_versions_include_drafts_and_tags_without_releases(self):
        pages = [[{'tag_name': 'client-build-2026.11.14.1', 'draft': True}],
                 [{'tag_name': 'server-build-2026.11.08.4', 'draft': False}]]
        refs = 'deadbeef\trefs/tags/server-build-2026.11.15.2\n'
        with mock.patch.object(release, 'out', side_effect=[json.dumps(pages), refs]):
            self.assertEqual(release.known_remote_builds(), {'client': 2026111401, 'server': 2026111502})

    def test_publish_rejects_old_build_number_before_signing(self):
        build = self.root / 'build/client'
        build.mkdir(parents=True)
        (build/'build-report.json').write_text(json.dumps(
            {'build': 2026110801, 'label': '2026.11.08.1', 'side': 'client'}), encoding='utf8')
        args = SimpleNamespace(side='client', changes=self.root/'releases/20261114.1.json')
        with mock.patch.object(publish, 'ROOT', self.root):
            with self.assertRaisesRegex(RuntimeError, '빌드 번호'):
                publish.prepare(args)

    def test_public_feed_must_match_signed_bytes(self):
        destination = self.root / 'public-feed.properties'
        expected = b'payload=correct\nsignature=correct\n'
        with mock.patch.object(publish.urllib.request, 'urlopen', return_value=io.BytesIO(expected)):
            publish.verify_public_feed('client', expected, destination)
        self.assertEqual(destination.read_bytes(), expected)
        with mock.patch.object(publish.urllib.request, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(b'old feed')), \
             mock.patch.object(publish.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, '공개 피드'):
                publish.verify_public_feed('client', expected, destination)


if __name__ == '__main__':
    unittest.main()
