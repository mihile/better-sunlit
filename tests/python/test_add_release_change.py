"""Check that authored notes accumulate without rewriting public versions."""
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, mock
import json
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from add_release_change import add_change, release


class AddReleaseChangeTests(TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        patch = mock.patch.object(release, 'ROOT', Path(temporary.name))
        patch.start()
        self.addCleanup(patch.stop)
        self.today = date(2026, 11, 21)

    def test_multiple_tasks_accumulate_in_one_unpublished_version(self):
        builds = {'client': 2026112105, 'server': 2026112104}
        path, added = add_change('첫 작업', '첫 설명', builds, self.today)
        self.assertTrue(added)
        self.assertEqual(path.name, '20261121.6.json')
        later = date(2026, 11, 22)
        self.assertEqual(add_change('둘째 작업', '둘째 설명', builds, later), (path, True))
        self.assertEqual(add_change('둘째 작업', '둘째 설명', builds, later), (path, False))
        self.assertEqual([item['title'] for item in json.loads(path.read_text(encoding='utf8'))['changes']],
                         ['첫 작업', '둘째 작업'])

    def test_published_or_reserved_number_is_preserved(self):
        path, _ = add_change('공개 작업', '공개 설명', {'client': 2026112105}, self.today)
        original = path.read_bytes()
        new, _ = add_change('추가 작업', '추가 설명', {'client': 2026112106, 'server': 2026112104}, self.today)
        self.assertEqual(new.name, '20261121.7.json')
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(json.loads(new.read_text(encoding='utf8'))['changes'],
                         [{'title': '추가 작업', 'detail': '추가 설명'}])

    def test_new_day_starts_at_one_when_no_pending_version_exists(self):
        path, _ = add_change('오늘 작업', '오늘 설명', {'client': 2026112009}, self.today)
        self.assertEqual(path.name, '20261121.1.json')
        self.assertEqual(json.loads(path.read_text(encoding='utf8'))['build'], 2026112101)
