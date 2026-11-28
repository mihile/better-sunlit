"""Exercise release orchestration against isolated local Git repositories."""
from datetime import date, datetime, timezone
from pathlib import Path
from unittest import mock
import importlib.util
import base64
import contextlib
import io
import json
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'tools' / 'release.py'
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location('better_sunlit_release', SCRIPT)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
publish_spec = importlib.util.spec_from_file_location('better_sunlit_publish', SCRIPT.parent / 'publish.py')
publish = importlib.util.module_from_spec(publish_spec)
publish_spec.loader.exec_module(publish)
test_spec = importlib.util.spec_from_file_location('better_sunlit_test_runner', SCRIPT.parent / 'test.py')
test_runner = importlib.util.module_from_spec(test_spec)
test_spec.loader.exec_module(test_runner)


def feed_envelope(side, label, sha256=''):
    tag = f'{side}-build-{label}'
    payload = f'channel={side}\r\nlabel={label}\r\ntag={tag}\r\nsha256={sha256}\r\n'
    return 'payload=' + base64.b64encode(payload.encode()).decode() + '\nsignature=test\n'


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
        self.write('src/main/resources/shared.mcmeta', 'base\n')
        self.write('releases/20261114.1.json', json.dumps({
            'build': 2026111401, 'label': '2026.11.14.1', 'title': 'Title',
            'changes': [{'title': 'Feature', 'detail': 'Description'}],
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

    def publish_snapshot(self, side, label):
        self.git('tag', f'{side}-build-{label}')
        self.write('feed/update.properties', feed_envelope(side, label))
        self.commit(f'Publish {side} feed')
        self.git('push', 'origin', side, f'refs/tags/{side}-build-{label}')

    def test_no_changes_leave_publication_alone(self):
        self.publish_snapshot('client', '2026.11.14.1')
        self.git('checkout', 'server')
        self.publish_snapshot('server', '2026.11.14.1')
        self.git('checkout', 'client')
        refs = self.git('ls-remote', 'origin')
        with mock.patch.object(release, 'known_remote_builds', return_value={'client': 2026111401, 'server': 2026111401}), \
             mock.patch.object(release, 'releases', return_value=[]), \
             mock.patch.object(release, 'prepare_or_resume') as prepare, \
             mock.patch.object(release, 'publish_bundle') as publish:
            release.release('both')
        prepare.assert_not_called()
        publish.assert_not_called()
        self.assertEqual(self.git('ls-remote', 'origin'), refs)

    def test_client_only_sources_do_not_trigger_server(self):
        self.publish_snapshot('client', '2026.11.14.1')
        self.git('checkout', 'server')
        self.publish_snapshot('server', '2026.11.14.1')
        self.git('checkout', 'client')
        self.write('src/main/java/dev/sunlit/client/Presentation.java', 'class Presentation {}\n')
        self.commit('Client presentation change')

        self.assertTrue(release.needs_release('client'))
        self.assertFalse(release.needs_release('server'))

        self.write('boot-agent/src/Agent.java', 'class Agent {}\n')
        self.commit('Shared boot agent change')
        self.assertTrue(release.needs_release('server'))

    def test_merge_preserves_both_changes_and_each_channel(self):
        self.write('src/main/resources/client-added.mcmeta', 'client feature\n')
        self.commit('client change')
        self.git('checkout', 'server')
        self.write('src/main/resources/server-added.mcmeta', 'server feature\n')
        self.commit('server change')
        self.git('checkout', 'client')

        release.sync_from('server')
        self.assertEqual((self.root/'feed/update.properties').read_text(), 'client-old\n')
        self.assertEqual((self.root/'release-channel.properties').read_text(), 'channel=client\n')
        self.assertTrue((self.root/'src/main/resources/server-added.mcmeta').exists())
        self.git('checkout', 'server')
        release.sync_from('client')
        self.assertEqual((self.root/'feed/update.properties').read_text(), 'server-old\n')
        self.assertEqual((self.root/'release-channel.properties').read_text(), 'channel=server\n')
        self.assertTrue((self.root/'src/main/resources/client-added.mcmeta').exists())
        release.assert_shared_equal('client', 'server')

    def test_conflicting_shared_changes_abort_without_overwriting(self):
        self.write('src/main/resources/shared.mcmeta', 'client version\n')
        self.commit('client edit')
        self.git('checkout', 'server')
        self.write('src/main/resources/shared.mcmeta', 'server version\n')
        self.commit('server edit')
        self.git('checkout', 'client')
        with self.assertRaisesRegex(RuntimeError, 'src/main/resources/shared.mcmeta'):
            release.sync_from('server')
        self.assertEqual((self.root/'src/main/resources/shared.mcmeta').read_text(), 'client version\n')
        self.assertEqual(self.git('status', '--porcelain'), '')
        self.assertEqual(self.git('show', 'server:src/main/resources/shared.mcmeta'), 'server version')

    def test_every_deploy_entry_point_prepares_the_same_complete_release(self):
        self.git('branch', 'client-only')
        self.git('push', 'origin', 'client-only')
        snapshot = self.git('rev-parse', 'client-only')
        for requested in ('client', 'server', 'both'):
            with self.subTest(requested=requested), \
                 mock.patch.object(release, 'korea_today', return_value=date(2026, 11, 14)), \
                 mock.patch.object(release, 'known_remote_builds', return_value={'client': 0, 'server': 0}), \
                 mock.patch.object(release, 'releases', return_value=[]), \
                 mock.patch.object(release, 'prepare_or_resume', return_value=mock.sentinel.plan) as prepare, \
                 mock.patch.object(release, 'publish_bundle') as publish:
                release.release(requested, prepare_only=True)
                self.assertEqual(set(prepare.call_args.args[1]), {'client', 'server'})
                self.assertEqual(prepare.call_args.args[2], snapshot)
                self.assertEqual(prepare.call_args.args[4]['build'], 2026111401)
                publish.assert_not_called()

    def test_test_failure_stops_before_signing_or_publication(self):
        actual_run = release.run

        def run_stub(command, *, check=True):
            if str(command[0]) == sys.executable and 'tools/test.py' in command:
                raise subprocess.CalledProcessError(1, command)
            return actual_run(command, check=check)

        with mock.patch.object(release, 'build_both') as build, \
             mock.patch.object(release, 'run', side_effect=run_stub), \
             mock.patch.object(release, 'prepare_bundle') as sign:
            with self.assertRaises(subprocess.CalledProcessError):
                release.prepare_or_resume(self.root/'stage', {}, 'standalone', self.root/'notes', {}, None)
        build.assert_called_once()
        sign.assert_not_called()

    def test_local_client_build_preserves_the_installed_download_name(self):
        instance = self.root.parent / 'instance'
        mods = instance / 'minecraft/mods'
        mods.mkdir(parents=True)
        downloaded = mods / 'better-sunlit-client.jar'
        legacy = mods / 'better-sunlit.jar'
        downloaded.write_bytes(b'old client')
        self.write('build/client/better-sunlit.jar', 'new client')

        with mock.patch.object(release, 'INSTANCE', instance), \
             mock.patch.object(release, 'is_client_build_current', return_value=True):
            release.local_build('client')
            self.assertEqual(downloaded.read_bytes(), b'new client')
            self.assertFalse(legacy.exists())
            legacy.write_bytes(b'duplicate')
            with self.assertRaisesRegex(RuntimeError, 'JAR 한 개'):
                release.local_build('client')
            self.assertEqual(legacy.read_bytes(), b'duplicate')

    def test_concurrent_release_lock_is_rejected(self):
        with release.release_lock():
            with self.assertRaisesRegex(RuntimeError, '실행 중'):
                with release.release_lock():
                    self.fail('second lock should not be acquired')

    def test_release_requires_new_metadata_instead_of_copying_old_notes(self):
        with self.assertRaisesRegex(RuntimeError, r'add_release_change.py'):
            release.choose_release(['client', 'server'], {'client': 2026110804, 'server': 2026111401},
                                   date(2026, 11, 25))
        self.assertEqual(len(list((self.root/'releases').glob('*.json'))), 1)

    def test_rejects_duplicate_notes_and_keeps_authored_notes(self):
        old = self.root/'releases/20261114.1.json'
        data = json.loads(old.read_text())
        data.update(build=2026111402, label='2026.11.14.2')
        new = old.with_name('20261114.2.json')
        new.write_text(json.dumps(data), encoding='utf8')
        with self.assertRaisesRegex(RuntimeError, '변경 내역이 .* 같습니다'):
            release.choose_release(['client'], {'client': 2026111401}, date(2026, 11, 14))
        data['changes'][0]['title'] = 'Fixed startup hang'
        new.write_text(json.dumps(data), encoding='utf8')
        _, selected = release.choose_release(['client'], {'client': 2026111401}, date(2026, 11, 14))
        self.assertEqual(selected, data)

    def test_lagging_channel_gets_next_deployment_day_sequence(self):
        old = self.root/'releases/20261114.1.json'
        original = old.read_bytes()
        path, data = release.choose_release(['client'], {'client': 2026111301, 'server': 2026112504},
                                            date(2026, 11, 25))
        self.assertEqual(path.name, '20261125.5.json')
        self.assertEqual((data['build'], data['label']), (2026112505, '2026.11.25.5'))
        self.assertEqual(old.read_bytes(), original)

    def test_future_remote_build_does_not_allocate_a_lower_version(self):
        old = self.root/'releases/20261114.1.json'
        original = old.read_bytes()
        with self.assertRaisesRegex(RuntimeError, '시스템 시각'):
            release.choose_release(['client'], {'client': 2026111301, 'server': 2026112601},
                                   date(2026, 11, 25))
        self.assertEqual(old.read_bytes(), original)
        self.assertEqual(list(old.parent.glob('*.json')), [old])

    def test_korean_date_changes_at_1500_utc(self):
        with mock.patch.object(release, 'datetime') as clock:
            clock.now.side_effect = lambda tz: datetime(2026, 11, 24, 15, tzinfo=timezone.utc).astimezone(tz)
            self.assertEqual(release.korea_today(), date(2026, 11, 25))

    def test_remote_versions_include_drafts_and_tags_without_releases(self):
        pages = [[{'tag_name': 'client-build-2026.11.14.1', 'draft': True}],
                 [{'tag_name': 'server-build-2026.11.08.4', 'draft': False}]]
        refs = 'deadbeef\trefs/tags/server-build-2026.11.15.2\n'
        with mock.patch.object(release, 'out', side_effect=[json.dumps(pages), refs]):
            self.assertEqual(release.known_remote_builds(), {'client': 2026111401, 'server': 2026111502})

    def test_public_feed_must_match_signed_bytes(self):
        destination = self.root / 'public-feed.properties'
        expected = b'payload=correct\nsignature=correct\n'
        with mock.patch.object(publish, 'origin_feed', return_value=expected), \
             mock.patch.object(publish.urllib.request, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(expected)):
            publish.verify_public_feed('client', expected, destination, commit='a'*40)
        self.assertEqual(destination.read_bytes(), expected)
        with mock.patch.object(publish.urllib.request, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(b'old feed')), \
             mock.patch.object(publish, 'origin_feed', return_value=expected), \
             mock.patch.object(publish.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, '공개 피드'):
                publish.verify_public_feed('client', expected, destination, commit='a'*40)

    def test_stale_branch_cache_does_not_fail_a_verified_origin_and_commit(self):
        expected = b'correct signed feed'
        destination = self.root/'public-feed.properties'
        def response(request, **kwargs):
            url = request.full_url if hasattr(request, 'full_url') else request
            return io.BytesIO(b'old cached feed' if '/client/' in url else expected)
        stdout = io.StringIO()
        with mock.patch.object(publish, 'origin_feed', return_value=expected), \
             mock.patch.object(publish.urllib.request, 'urlopen', side_effect=response), \
             contextlib.redirect_stdout(stdout):
            publish.verify_public_feed('client', expected, destination, commit='a'*40)
        self.assertEqual(destination.read_bytes(), expected)
        self.assertIn('캐시', stdout.getvalue())

    def test_wrong_origin_is_still_a_failure(self):
        with mock.patch.object(publish, 'origin_feed', return_value=b'wrong origin'), \
             mock.patch.object(publish.urllib.request, 'urlopen') as request:
            with self.assertRaisesRegex(RuntimeError, '원본 브랜치'):
                publish.verify_public_feed('client', b'expected', self.root/'feed.properties', commit='a'*40)
        request.assert_not_called()


class RecoveryModeTests(unittest.TestCase):
    def test_verification_only_checks_the_complete_public_release(self):
        with mock.patch.object(sys, 'argv', ['release.py', '--side', 'both', '--verify-only']), \
             mock.patch.object(release, 'release_lock', return_value=contextlib.nullcontext()), \
             mock.patch.object(release, 'release') as publish_new, \
             mock.patch.object(release, 'verify_latest') as verify:
            release.main()
        publish_new.assert_not_called()
        verify.assert_called_once_with()


class ParallelRunnerTests(unittest.TestCase):
    def test_failure_is_reported_and_individual_logs_are_kept(self):
        with tempfile.TemporaryDirectory(prefix='better-sunlit-test-runner-') as directory:
            tasks = [('success', [[sys.executable, '-c', "print('PASS: isolated success')"]]),
                     ('failure', [[sys.executable, '-c', "import sys; print('expected error'); sys.exit(7)"]])]
            stdout, stderr = io.StringIO(), io.StringIO()
            with mock.patch.object(test_runner, 'root', Path(directory)), \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                with self.assertRaisesRegex(RuntimeError, 'failure'):
                    test_runner.run_tests(tasks)
            self.assertIn('PASS: isolated success', stdout.getvalue())
            self.assertIn('expected error', stderr.getvalue())
            self.assertIn('expected error', (Path(directory)/'build/test-logs/failure.log').read_text())

    def test_build_failure_is_reported_after_both_builds_finish(self):
        with tempfile.TemporaryDirectory(prefix='better-sunlit-build-runner-') as directory:
            def fake_run(command, cwd, stdout, stderr):
                side = command[command.index('--side') + 1]
                stdout.write((side + ' build log').encode('utf8'))
                return subprocess.CompletedProcess(command, 17 if side == 'server' else 0)

            stdout, stderr = io.StringIO(), io.StringIO()
            with mock.patch.object(release, 'ROOT', Path(directory)), \
                 mock.patch.object(release.subprocess, 'run', side_effect=fake_run), \
                 contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                with self.assertRaisesRegex(RuntimeError, 'server'):
                    release.build_both({'build': 2026111401, 'label': '2026.11.14.1'})
            self.assertIn('server build log', stderr.getvalue())
            self.assertTrue((Path(directory)/'build/build-logs/client.log').is_file())
            self.assertTrue((Path(directory)/'build/build-logs/server.log').is_file())


if __name__ == '__main__':
    unittest.main()
