"""发布顺序与失败边界测试，不连接生产服务。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-backend.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeS3:
    def __init__(self, fail_at=None):
        self.objects = {}
        self.fail_at = fail_at
        self.last_arguments = None

    def upload_file(self, filename, bucket, key, ExtraArgs=None, Callback=None, Config=None):
        self.last_arguments = {
            "Filename": filename,
            "Bucket": bucket,
            "Key": key,
            "ExtraArgs": ExtraArgs,
            "Callback": Callback,
            "Config": Config,
        }
        if len(self.objects) == self.fail_at:
            raise OSError("模拟上传失败")
        data = Path(filename).read_bytes()
        if Callback is not None:
            Callback(len(data))
        self.objects[key] = data


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / "app.jar"
        self.jar.write_bytes(b"jar content")

    def publish(self, client, current=True, transfer_config=None):
        return publisher.publish(client, "test-bucket", "backend", "a" * 40,
                                 self.jar, lambda: current, transfer_config=transfer_config)

    def test_uploads_only_one_fixed_jar_with_integrity_headers(self):
        client = FakeS3()
        sentinel_config = object()
        entry, promoted = self.publish(client, transfer_config=sentinel_config)
        self.assertTrue(promoted)
        self.assertEqual(list(client.objects), ["backend/butvan-backend.jar"])
        self.assertEqual(client.objects[entry["key"]], b"jar content")
        extra = client.last_arguments["ExtraArgs"]
        self.assertEqual(extra["Metadata"], {"commit": "a" * 40, "sha256": entry["sha256"]})
        self.assertEqual(extra["ContentType"], "application/java-archive")
        self.assertEqual(extra["CacheControl"], "no-store, max-age=0")
        self.assertIs(client.last_arguments["Config"], sentinel_config)
        self.assertEqual(entry["size"], len(b"jar content"))
        self.assertEqual(entry["sha256"], publisher.hashlib.sha256(b"jar content").hexdigest())

    def test_failed_upload_preserves_previous_jar(self):
        client = FakeS3(fail_at=1)
        client.objects["backend/butvan-backend.jar"] = b"previous jar"
        with self.assertRaises(OSError):
            self.publish(client)
        self.assertEqual(client.objects, {"backend/butvan-backend.jar": b"previous jar"})

    def test_old_commit_writes_nothing(self):
        client = FakeS3()
        self.assertFalse(self.publish(client, current=False)[1])
        self.assertEqual(client.objects, {})


class UploadProgressTest(unittest.TestCase):
    def test_reports_on_percent_interval_and_clamps_retries(self):
        now = [0.0]
        lines = []
        progress = publisher.UploadProgress(
            100 * 1024 * 1024,
            min_percent_step=10.0,
            min_interval_seconds=10.0,
            clock=lambda: now[0],
            printer=lines.append,
        )
        now[0] = 2.0
        progress(5 * 1024 * 1024)  # 5%, 2s -> 未达阈值不输出
        self.assertEqual(lines, [])
        now[0] = 4.0
        progress(6 * 1024 * 1024)  # 11%, 4s -> 达到 10% 步进
        self.assertEqual(len(lines), 1)
        self.assertIn("11.0%", lines[-1])
        now[0] = 15.0
        progress(1 * 1024 * 1024)  # 12%, 距上次 11s -> 达到时间间隔
        self.assertEqual(len(lines), 2)
        now[0] = 20.0
        progress(88 * 1024 * 1024)  # 100% -> 完成输出
        self.assertEqual(len(lines), 3)
        self.assertIn("100.0%", lines[-1])
        now[0] = 21.0
        progress(8 * 1024 * 1024)  # 分片重试超总大小，不重复刷屏且不超过 100%
        self.assertEqual(len(lines), 3)
        self.assertIn("JAR 上传完成：100.0 MiB", progress.summary())


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
