"""Exercise unified publication and atomic feed ordering with a local Git remote."""
from pathlib import Path
from unittest import mock
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import release


class PublishFlowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='better-sunlit-publish-test-')
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        self.git('init', '-b', 'client')
        self.git('config', 'user.name', 'Release Test')
        self.git('config', 'user.email', 'release-test@example.invalid')
        (self.root/'feed').mkdir()
        self.feed = self.root/'feed/update.properties'
        self.feed.write_text('old client\n', encoding='utf8')
        self.git('add', 'feed')
        self.git('commit', '-m', 'client feed')
        self.git('checkout', '-b', 'server')
        self.feed.write_text('old server\n', encoding='utf8')
        self.git('commit', '-am', 'server feed')
        self.git('checkout', 'client')
        self.remote = Path(self.temp.name) / 'remote.git'
        subprocess.run(['git', 'init', '--bare', self.remote], check=True, capture_output=True)
        self.git('remote', 'add', 'origin', str(self.remote))
        self.git('push', '-u', 'origin', 'client', 'server')
        self.folder = self.root/'build/unified/test'
        self.folder.mkdir(parents=True)
        for side in ('client', 'server'):
            (self.folder/f'{side}.properties').write_text(f'new {side}\n', encoding='utf8')
        self.plan = {'sources': {side: self.git('rev-parse', side) for side in ('client', 'server')}, 'hashes': {}}
        self.tag = 'build-2026.11.14.1'
        self.data = {'label': '2026.11.14.1'}
        self.events = []

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True, encoding='utf8').strip()

    def assert_feeds(self, stage):
        for side in ('client', 'server'):
            self.assertEqual(self.git('--git-dir', self.remote, 'show', f'{side}:feed/update.properties'), f'{stage} {side}')

    def execute(self, fail_at=None, existing=None):
        original = release.run

        def run(command, **kwargs):
            if command[:3] == ['gh', 'release', 'create']:
                self.events.append('draft')
                self.assertEqual({Path(path).name for path in command[-3:]}, set(release.ASSETS.values()))
                self.assert_feeds('old')
            elif command[:3] == ['gh', 'release', 'edit']:
                self.events.append('latest')
                self.assertIn('--latest', command)
                self.assert_feeds('old')
            else:
                if command[:2] == ['git', 'push']:
                    self.assertIn('--atomic', command)
                return original(command, **kwargs)

        def verify(*args):
            self.events.append('redownload')
            self.assert_feeds('old')
            if self.events.count('redownload') == fail_at:
                raise RuntimeError('bad download')

        with mock.patch.object(release, 'ROOT', self.root), mock.patch.object(release, 'run', side_effect=run), \
             mock.patch.object(release, 'out', return_value=self.tag), \
             mock.patch.object(release, 'check_git_tree'), mock.patch.object(release, 'verify_downloads', side_effect=verify), \
             mock.patch.object(release, 'verify_latest', side_effect=lambda: self.assert_feeds('new')):
            release.publish_bundle('client', self.folder, self.tag, self.plan, self.data, existing)

    def test_bundle_verifies_before_either_feed_advances(self):
        self.execute()
        self.assertEqual(self.events, ['draft', 'redownload', 'latest', 'redownload'])
        self.assert_feeds('new')
        self.assertEqual(self.git('branch', '--show-current'), 'client')

    def test_bad_public_download_retains_both_previous_feeds(self):
        with self.assertRaisesRegex(RuntimeError, 'bad download'):
            self.execute(fail_at=2)
        self.assert_feeds('old')

    def test_retry_does_not_push_staged_feeds_before_rechecking_bundle(self):
        self.feed.write_text('new client\n', encoding='utf8')
        self.git('commit', '-am', 'prepared client feed')
        with self.assertRaisesRegex(RuntimeError, 'bad download'):
            self.execute(fail_at=1, existing={'draft': False})
        self.assert_feeds('old')
        self.assertEqual(self.events, ['redownload'])
