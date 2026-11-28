"""Verify which commit anchors the bundle and what counts as first in Releases."""
from io import BytesIO
from pathlib import Path
import sys
from unittest import TestCase, mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import downloads


class DownloadOrderTests(TestCase):
    def test_target_uses_most_recent_publication_commit(self):
        heads = {'client': 'client-head', 'server': 'server-head', 'client-only': 'standalone'}
        outputs = {'client-head': 'client-feed', 'server-head': 'server-feed',
                   'client-feed': '200', 'server-feed': '300', 'standalone': '100'}

        def git(*args):
            commit = args[4] if args[1] in ('log', 'show') else None
            if args[1] == 'log':
                self.assertEqual(args[5:], ('--', 'feed/update.properties'))
            return outputs[commit]

        with mock.patch.object(downloads, 'run') as fetch, mock.patch.object(downloads, 'read', side_effect=git):
            self.assertEqual(downloads.release_commit(heads), 'server-feed')
            outputs['standalone'] = '400'
            self.assertEqual(downloads.release_commit(heads), 'standalone')
        fetch.assert_called_with('git', 'fetch', '--no-tags', '--no-write-fetch-head', 'origin', *heads.values())

    def test_release_list_can_take_time_to_reflect_publication(self):
        older = b'<section id="release-server-build-1"></section>'
        bundle = b'<section id="release-downloads-1"></section>'
        with mock.patch.object(downloads.urllib.request, 'urlopen',
                               side_effect=[BytesIO(older + bundle), BytesIO(bundle + older)]) as fetch, \
             mock.patch.object(downloads.time, 'sleep'):
            downloads.verify_list_order('downloads-1')
        self.assertEqual(fetch.call_count, 2)

    def test_latest_link_does_not_prove_the_bundle_is_first(self):
        page = b'<section id="release-server-build-1"></section><section id="release-downloads-1"></section>'
        with mock.patch.object(downloads.urllib.request, 'urlopen', side_effect=lambda *args, **kw: BytesIO(page)), \
             mock.patch.object(downloads.time, 'sleep'), \
             self.assertRaisesRegex(RuntimeError, 'server-build-1'):
            downloads.verify_list_order('downloads-1')
