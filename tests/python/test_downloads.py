"""Test the public notes and asset boundaries of the unified release."""
from pathlib import Path
import sys
from unittest import TestCase, mock
import json

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import downloads


class DownloadTests(TestCase):
    def test_first_release_explains_all_editions(self):
        data = {'editionOverview': True, 'changes': [{'title': '내부 수정', 'detail': '내부 설명'}]}
        notes = downloads.release_notes(data)
        for name in downloads.ASSETS.values():
            self.assertIn(name, notes)
        self.assertIn('해골 광산 엘리베이터', notes)
        self.assertIn('싱글플레이', notes)
        self.assertIn('인게임 자동 업데이트를 제공하지 않습니다', notes)
        self.assertNotIn('내부 수정', notes)

    def test_following_releases_include_authored_changes(self):
        notes = downloads.release_notes({'changes': [{'title': '낚시 연출', 'detail': '물고기의 착지를 다듬었습니다.'}]})
        self.assertIn('## 변경 사항', notes)
        self.assertIn('물고기의 착지를 다듬었습니다.', notes)

    def test_unexpected_asset_stops_before_download(self):
        response = {'targetCommitish': 'source', 'assets': [
            {'name': name} for name in (*downloads.ASSETS.values(), 'update.properties')]}
        with mock.patch.object(downloads, 'output', return_value=json.dumps(response)), \
             mock.patch.object(downloads, 'run') as download, \
             self.assertRaisesRegex(RuntimeError, 'JAR 세 개'):
            downloads.verify_downloads(Path('.'), 'build-1', 'source', {}, {})
        download.assert_not_called()
