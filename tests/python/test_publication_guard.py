from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'tools'))
import publication_guard as guard


class GuardTests(unittest.TestCase):
    def test_jar_rejects_extra_local_files_and_hidden_credentials(self):
        with tempfile.TemporaryDirectory(prefix='sunlit-guard-jar-') as directory:
            jar=Path(directory)/'test.jar'
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('dev/sunlit/Example.class',b'ordinary class bytes')
                archive.writestr('assets/example/lang/ko_kr.json',b'{}')
            guard.check_release_jar(jar)
            with zipfile.ZipFile(jar,'a') as archive:
                archive.writestr('assets/example/.env',b'local file')
            with self.assertRaisesRegex(RuntimeError,'.env'):
                guard.check_release_jar(jar)
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('dev/sunlit/Example.class',b'ghp_'+b'C'*36)
            with self.assertRaisesRegex(RuntimeError,'GitHub token'):
                guard.check_release_jar(jar)

    def test_source_and_public_keys_are_allowed(self):
        guard.check_file('src/main/java/Example.java', b'class Example {}')
        guard.check_file('keys/client-public.der', guard.PUBLIC_DER_PREFIX + b'x'*32)
        for name in ('.env', 'server.properties', 'mods/example.jar', 'tools/notes.txt',
                     'src/main/resources/token.pk8'):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                guard.check_file(name, b'local data')

    def test_private_key_and_token_values_are_never_echoed(self):
        secret = b'ghp_' + b'A'*36
        samples = [secret, guard.PRIVATE_DER_PREFIX+b'x'*32,
                   ('-----BEGIN '+'PRIVATE KEY-----').encode(),
                   b'password="' + b'sensitive-value' + b'"']
        for data in samples:
            with self.subTest(data_length=len(data)), self.assertRaises(RuntimeError) as caught:
                guard.check_file('src/main/resources/test.json', data)
            self.assertNotIn(data.decode(errors='replace'), str(caught.exception))
        with self.assertRaises(RuntimeError):
            guard.check_file('keys/server-public.der', guard.PRIVATE_DER_PREFIX+b'x'*32)

    def test_exact_signing_key_disguised_as_source_is_blocked(self):
        with self.assertRaises(RuntimeError):
            guard.check_file('src/main/java/Example.java', b'prefix-hidden-private-value-suffix',
                             [b'hidden-private-value'])

    def test_history_catches_secret_deleted_before_head(self):
        with tempfile.TemporaryDirectory(prefix='sunlit-guard-test-') as directory:
            root=Path(directory)
            def git(*args):
                return subprocess.check_output(['git',*args],cwd=root,stderr=subprocess.DEVNULL).decode().strip()
            git('init','-b','client')
            git('config','user.name','Guard Test')
            git('config','user.email','guard@example.invalid')
            (root/'README.md').write_text('source project',encoding='utf8')
            git('add','-A');git('commit','-m','base')
            base=git('rev-parse','HEAD')
            secret=root/'src/main/resources/example.json'
            secret.parent.mkdir(parents=True)
            secret.write_bytes(b'ghp_'+b'B'*36)
            git('add','-A');git('commit','-m','bad addition')
            secret.unlink()
            git('add','-A');git('commit','-m','remove bad addition')
            guard.check_git_tree(root)
            with self.assertRaisesRegex(RuntimeError,'example.json'):
                guard.check_git_tree(root,remote=base)

    def test_untracked_local_data_is_rejected_and_ignored_data_stays_local(self):
        with tempfile.TemporaryDirectory(prefix='sunlit-guard-worktree-') as directory:
            root=Path(directory)
            subprocess.run(['git','init',str(root)],check=True,capture_output=True)
            (root/'.gitignore').write_text('build/\n',encoding='utf8')
            (root/'build').mkdir()
            (root/'build/private.pk8').write_bytes(b'private local data')
            guard.check_worktree(root)
            (root/'.env').write_bytes(b'private local config')
            with self.assertRaisesRegex(RuntimeError,'.env'):
                guard.check_worktree(root)
            staged=subprocess.check_output(['git','diff','--cached','--name-only'],cwd=root)
            self.assertEqual(staged,b'')


if __name__=='__main__':
    unittest.main()
