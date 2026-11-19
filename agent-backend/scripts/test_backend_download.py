"""用本机临时 HTTP 服务验证下载传输；生产入口另强制 HTTPS。"""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
import io
import zipfile

PWSH = os.environ.get("BUTVAN_TEST_PWSH") or shutil.which("pwsh")
MODULE = Path(__file__).resolve().parents[1] / "deployment/windows/source/backend-download.ps1"
PAYLOAD = bytes(range(256)) * 3


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path in ("/403", "/404"):
            self.send_response(int(self.path[1:]))
            self.end_headers()
        elif self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/ok")
            self.end_headers()
        else:
            self.send_response(200)
            if self.path != "/unknown-length":
                self.send_header("Content-Length", str(len(PAYLOAD)))
            if self.path != "/no-metadata":
                self.send_header("x-amz-meta-sha256", hashlib.sha256(PAYLOAD).hexdigest())
                self.send_header("x-amz-meta-commit", "a" * 40)
            self.end_headers()
            self.wfile.write(PAYLOAD)


@unittest.skipUnless(PWSH, "需要通过 BUTVAN_TEST_PWSH 或 PATH 提供 PowerShell")
class DownloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.target = Path(self.temporary.name) / "download.bin"

    def download(self, path="/ok", maximum=len(PAYLOAD), require_metadata=False):
        def quoted(value):
            return "'" + str(value).replace("'", "''") + "'"
        url = f"http://127.0.0.1:{self.server.server_port}{path}"
        required = " -RequireJarMetadata" if require_metadata else ""
        command = (f"$ErrorActionPreference='Stop'; . {quoted(MODULE)}; "
                   f"try {{ Save-PublicDownload {quoted(url)} {quoted(self.target)} {maximum}{required} }} "
                   "catch { Write-Output $_.Exception.Message; exit 1 }")
        return subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-Command", command],
                              capture_output=True, text=True, timeout=10)

    def test_byte_exact_download_without_external_client(self):
        result = self.download()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).digest(), hashlib.sha256(PAYLOAD).digest())

    def test_both_declared_and_actual_oversized_files_are_rejected_and_cleaned(self):
        for path in ("/ok", "/unknown-length"):
            with self.subTest(path=path):
                self.assertNotEqual(self.download(path, maximum=10).returncode, 0)
                self.assertFalse(self.target.exists())

    def test_jar_metadata_comes_from_the_same_get_response(self):
        result = self.download(require_metadata=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.target.unlink()
        result = self.download("/no-metadata", require_metadata=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.target.exists())

    def test_executable_backend_jar_manifest_is_required(self):
        valid = io.BytesIO()
        with zipfile.ZipFile(valid, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"
                         "Main-Class: org.springframework.boot.loader.launch.JarLauncher\n"
                         "Start-Class: butvan.agent.network.ButVanAgentApplication\n")
        for payload, expected in [(valid.getvalue(), 0), (b"not a jar", 1)]:
            self.target.write_bytes(payload)
            quote = lambda text: "'" + str(text).replace("'", "''") + "'"
            code = (f". {quote(MODULE)}; try {{ Assert-ExecutableJar {quote(self.target)} }} "
                    "catch { exit 1 }")
            result = subprocess.run([PWSH, "-NoLogo", "-NoProfile", "-Command", code],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, expected)

    def test_failed_create_never_deletes_existing_file(self):
        self.target.write_bytes(b"original")
        self.assertNotEqual(self.download().returncode, 0)
        self.assertEqual(self.target.read_bytes(), b"original")

    def test_http_status_errors_are_actionable_and_redirects_are_not_followed(self):
        for path, text in [("/403", "HTTP 403"), ("/404", "HTTP 404"), ("/redirect", "HTTP 200")]:
            with self.subTest(path=path):
                result = self.download(path)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(text, result.stdout)
                self.assertFalse(self.target.exists())


if __name__ == "__main__":
    unittest.main()
