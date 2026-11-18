"""验证交付 BAT 可单独复制、自解压且不依赖项目中的 PS1。"""
import base64
import importlib.util
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build-windows-launchers.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
PWSH = os.environ.get("BUTVAN_TEST_PWSH") or shutil.which("pwsh")


class LauncherTest(unittest.TestCase):
    def test_committed_bats_are_current_and_each_contains_the_complete_runtime(self):
        for name, content in builder.generate(builder.WINDOWS / "source").items():
            with self.subTest(name=name):
                self.assertEqual((builder.WINDOWS / name).read_bytes(), content)
                self.assertNotIn(b"\n", content.replace(b"\r\n", b""))
                command = next(line for line in content.splitlines() if b"-EncodedCommand " in line)
                self.assertLess(len(command), 8000)
                payload = base64.b64decode(content.rsplit(b":__BUTVAN_PAYLOAD__", 1)[1])
                with zipfile.ZipFile(io.BytesIO(payload)) as archive:
                    self.assertEqual(set(archive.namelist()), set(builder.SOURCE_FILES))
                    self.assertNotIn("AGENTS.md", archive.namelist())
                    self.assertNotIn("WINDOWS.md", archive.namelist())
                    self.assertNotIn("backend.env", archive.namelist())

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证实际 BAT 引导")
    def test_each_bat_extracts_without_any_sibling_support_files(self):
        for name in builder.ACTIONS:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix="butvan BAT '空格 ") as directory:
                root = Path(directory)
                bat = root / name
                shutil.copy2(builder.WINDOWS / name, bat)
                result = self.extract(bat)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual({p.name for p in root.iterdir()}, {name, "data"})
                runtimes = list((root / "data/launcher").iterdir())
                self.assertEqual(len(runtimes), 1)
                self.assertEqual({p.name for p in runtimes[0].iterdir()}, set(builder.SOURCE_FILES) | {"complete"})

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证实际 BAT 引导")
    def test_corrupted_bat_does_not_extract_or_execute_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            bat = Path(directory) / "start-backend.bat"
            content = (builder.WINDOWS / bat.name).read_bytes()
            header, payload = content.rsplit(b":__BUTVAN_PAYLOAD__", 1)
            body = bytearray(base64.b64decode(payload))
            body[len(body) // 2] ^= 1
            bat.write_bytes(header + b":__BUTVAN_PAYLOAD__\r\n" + base64.b64encode(body))
            result = self.extract(bat)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((Path(directory) / "data/launcher").exists())

    @unittest.skipUnless(PWSH, "需要 PowerShell 验证配置导入")
    def test_existing_configuration_is_imported_once_and_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bat = root / "start-backend.bat"
            shutil.copy2(builder.WINDOWS / bat.name, bat)
            old = root / "backend.env"
            old.write_text("test configuration")
            self.assertEqual(self.extract(bat).returncode, 0)
            imported = root / "data/backend.env"
            self.assertEqual(imported.read_text(), "test configuration")
            old.write_text("changed old configuration")
            self.assertEqual(self.extract(bat).returncode, 0)
            self.assertEqual(imported.read_text(), "test configuration")

    @staticmethod
    def extract(bat):
        encoded = re.search(rb"-EncodedCommand ([A-Za-z0-9+/=]+)", bat.read_bytes()).group(1).decode()
        environment = os.environ.copy()
        environment.update(BUTVAN_LAUNCHER_FILE=str(bat), BUTVAN_LAUNCHER_ACTION="Extract")
        return subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-EncodedCommand", encoded],
                              env=environment, text=True, capture_output=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
