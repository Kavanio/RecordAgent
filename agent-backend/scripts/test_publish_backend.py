"""发布顺序与失败边界测试，不连接生产服务。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-backend.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeS3:
    def __init__(self, fail_at=None):
        self.objects = {}
        self.fail_at = fail_at

    def put_object(self, **kwargs):
        if len(self.objects) == self.fail_at:
            raise OSError("模拟上传失败")
        body = kwargs["Body"]
        self.objects[kwargs["Key"]] = body.read() if hasattr(body, "read") else body


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / "app.jar"
        self.launcher = Path(self.temp.name) / "launcher.zip"
        self.jar.write_bytes(b"jar content")
        self.launcher.write_bytes(b"launcher content")

    def publish(self, client, current=True):
        return publisher.publish(client, "test-bucket", "backend", "a" * 40, "123-1",
                                 self.jar, self.launcher, lambda: current)

    def test_channel_is_written_last_with_exact_artifact_hash(self):
        client = FakeS3()
        manifest, promoted = self.publish(client)
        self.assertTrue(promoted)
        self.assertEqual(list(client.objects)[-1], "backend/channels/develop.json")
        self.assertEqual(json.loads(client.objects["backend/channels/develop.json"]), manifest)
        self.assertEqual(manifest["jar"]["size"], len(b"jar content"))
        self.assertEqual(manifest["jar"]["sha256"], publisher.hashlib.sha256(b"jar content").hexdigest())

    def test_each_partial_upload_failure_preserves_existing_channel(self):
        for fail_at in range(5):
            client = FakeS3(fail_at=fail_at)
            with self.assertRaises(OSError):
                self.publish(client)
            self.assertNotIn("backend/channels/develop.json", client.objects)

    def test_old_commit_uploads_without_promoting(self):
        client = FakeS3()
        self.assertFalse(self.publish(client, current=False)[1])
        self.assertNotIn("backend/channels/develop.json", client.objects)
        self.assertNotIn("backend/windows-launcher.zip", client.objects)

    def test_fixed_launcher_is_written_before_channel(self):
        client = FakeS3()
        self.publish(client)
        self.assertEqual(client.objects["backend/windows-launcher.zip"], b"launcher content")
        self.assertEqual(list(client.objects)[-2:], ["backend/windows-launcher.zip", "backend/channels/develop.json"])

    def test_package_excludes_credentials_and_local_runtime(self):
        backend = Path(__file__).resolve().parents[1]
        publisher.package_launcher(backend / "deployment/windows", self.launcher, backend / "WINDOWS.md")
        with zipfile.ZipFile(self.launcher) as archive:
            self.assertIn("backend-settings.example.json", archive.namelist())
            self.assertNotIn("backend-settings.json", archive.namelist())
            self.assertNotIn("backend.env", archive.namelist())
            bat = archive.read("update-and-start.bat")
            self.assertNotIn(b"\n", bat.replace(b"\r\n", b""))
            self.assertFalse(bat.startswith(b"\xef\xbb\xbf"))
            self.assertTrue(archive.read("backend.ps1").startswith(b"\xef\xbb\xbf"))


class PublicPolicyTest(unittest.TestCase):
    class Client:
        def __init__(self, statements):
            self.policy = {"Version": "2012-10-17", "Statement": statements}
            self.writes = 0

        def get_bucket_policy(self, **kwargs):
            return {"Policy": json.dumps(self.policy)}

        def put_bucket_policy(self, **kwargs):
            self.policy = json.loads(kwargs["Policy"])
            self.writes += 1

    def test_only_release_prefix_get_object_and_preserves_existing_policy(self):
        original = {"Sid": "ExistingPrivateRule", "Effect": "Deny", "Action": ["s3:DeleteObject"],
                    "Principal": {"AWS": ["*"]}, "Resource": ["arn:aws:s3:::test-bucket/protected/*"]}
        client = self.Client([original])
        publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(client.policy["Statement"][0], original)
        added = client.policy["Statement"][1]
        self.assertEqual(added["Action"], ["s3:GetObject"])
        self.assertEqual(added["Resource"], ["arn:aws:s3:::test-bucket/backend/*"])
        publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(client.writes, 1)

    def test_conflicting_owned_statement_is_not_overwritten(self):
        original = {"Sid": "RecordAgentBackendPublicRead", "Effect": "Deny"}
        client = self.Client([original])
        with self.assertRaises(ValueError):
            publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(client.writes, 0)
        self.assertEqual(client.policy["Statement"], [original])

    def test_equivalent_server_serialization_is_idempotent(self):
        rule = {"Sid": "RecordAgentBackendPublicRead", "Effect": "Allow", "Principal": "*",
                "Action": "s3:GetObject", "Resource": "arn:aws:s3:::test-bucket/backend/*", "Condition": {}}
        client = self.Client([rule])
        publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(client.writes, 0)

    def test_absent_policy_is_created_but_permission_failure_is_not_treated_as_empty(self):
        class S3Error(Exception):
            def __init__(self, code):
                self.response = {"Error": {"Code": code}}

        class Client(self.Client):
            def get_bucket_policy(self, **kwargs):
                raise S3Error(self.code)

        client = Client([])
        client.code = "NoSuchBucketPolicy"
        publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(len(client.policy["Statement"]), 1)
        client = Client([])
        client.code = "AccessDenied"
        with self.assertRaises(S3Error):
            publisher.ensure_public_downloads(client, "test-bucket", "backend")
        self.assertEqual(client.writes, 0)


if __name__ == "__main__":
    unittest.main()
