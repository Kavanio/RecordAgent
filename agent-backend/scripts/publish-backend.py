#!/usr/bin/env python3
"""只向 MinIO 发布一个固定地址的 JAR；校验信息保存在对象元数据。"""

import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def file_info(path, key):
    """流式计算校验值，避免大 JAR 重复驻留内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"key": key, "sha256": digest.hexdigest(), "size": path.stat().st_size}


def ensure_public_downloads(client, bucket, prefix):
    """合并唯一受控的匿名 GetObject 语句，保留原策略，不授予列举或写入。"""
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*", prefix):
        raise ValueError("发布前缀格式无效")
    try:
        policy = json.loads(client.get_bucket_policy(Bucket=bucket)["Policy"])
    except Exception as error:
        code = getattr(error, "response", {}).get("Error", {}).get("Code")
        if code != "NoSuchBucketPolicy":
            raise
        policy = {"Version": "2012-10-17", "Statement": []}
    statements = policy.get("Statement")
    if not isinstance(statements, list):
        raise ValueError("现有 Bucket Policy 结构无效，未修改")
    statement = {
        "Sid": "RecordAgentBackendPublicRead",
        "Effect": "Allow", "Principal": {"AWS": ["*"]},
        "Action": ["s3:GetObject"],
        "Resource": [f"arn:aws:s3:::{bucket}/{prefix}/*"],
    }
    existing = [item for item in statements if item.get("Sid") == statement["Sid"]]
    if existing:
        def normalise(rule):
            # S3 服务可能将单元素数组序列化为字符串；不把等价策略误判为冲突。
            result = dict(rule)
            for key in ("Action", "Resource"):
                if isinstance(result.get(key), str):
                    result[key] = [result[key]]
            principal = result.get("Principal")
            if principal == "*":
                result["Principal"] = {"AWS": ["*"]}
            elif isinstance(principal, dict) and isinstance(principal.get("AWS"), str):
                result["Principal"] = {**principal, "AWS": [principal["AWS"]]}
            if result.get("Condition") == {}:
                result.pop("Condition")
            return result
        if len(existing) != 1 or normalise(existing[0]) != statement:
            raise ValueError("现有同名策略不同，未自动覆盖")
        return
    statements.append(statement)
    client.put_bucket_policy(Bucket=bucket, Policy=json.dumps(policy))


def publish(client, bucket, prefix, commit, jar, is_current):
    """只有当前 develop 提交可覆盖固定 JAR；一次 PUT 同时发布正文与校验头。"""
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("发布提交必须为完整 SHA")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*", prefix):
        raise ValueError("发布前缀格式无效")
    entry = file_info(jar, f"{prefix}/butvan-backend.jar")
    if not is_current():
        return entry, False
    print(f"正在上传 JAR，大小 {entry['size']} 字节……", flush=True)
    with jar.open("rb") as body:
        client.put_object(Bucket=bucket, Key=entry["key"], Body=body,
                          ContentLength=entry["size"], ContentType="application/java-archive",
                          CacheControl="no-store, max-age=0",
                          Metadata={"sha256": entry["sha256"], "commit": commit})
    return entry, True


def current_develop(commit):
    """上传前读取权威分支，串行发布避免旧构建重跑覆盖新提交。"""
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("仓库标识格式无效")
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    request = Request(f"{api}/repos/{repository}/git/ref/heads/develop", headers={
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
    })
    with urlopen(request, timeout=30) as response:
        return json.load(response)["object"]["sha"] == commit


def main():
    """真实凭据仅从进程环境读取；失败不输出可能含认证信息的 SDK 异常。"""
    endpoint = os.environ["MINIO_ENDPOINT"]
    parsed = urlsplit(endpoint)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError("MinIO 地址必须是无凭据、无路径的 HTTPS S3 API 入口")
    import boto3
    from botocore.config import Config
    client = boto3.client("s3", endpoint_url=endpoint,
                          aws_access_key_id=os.environ["MINIO_ACCESS_KEY"],
                          aws_secret_access_key=os.environ["MINIO_SECRET_KEY"],
                          region_name="us-east-1", config=Config(
                              signature_version="s3v4", s3={"addressing_style": "path"},
                              request_checksum_calculation="when_required",
                              retries={"max_attempts": 3, "mode": "standard"},
                              connect_timeout=15, read_timeout=180))
    # 仓库部署契约已明确授权仅公开发布前缀；开关必须显式启用。
    if os.environ.get("MINIO_PUBLIC_DOWNLOADS") != "true":
        raise ValueError("请显式启用 MINIO_PUBLIC_DOWNLOADS")
    bucket = os.environ["MINIO_BUCKET"]
    prefix = os.environ.get("MINIO_PREFIX", "backend")
    ensure_public_downloads(client, bucket, prefix)
    backend = Path(__file__).resolve().parents[1]
    commit = os.environ["GITHUB_SHA"]
    entry, promoted = publish(
        client, bucket, prefix, commit,
        backend / "server-network/target/server-network-0.0.1-SNAPSHOT.jar",
        lambda: current_develop(commit))
    summary = (f"## 后端 JAR 发布结果\n- 提交：`{commit}`\n"
               f"- 固定地址：{endpoint.rstrip('/')}/{bucket}/{entry['key']}\n"
               f"- SHA-256：`{entry['sha256']}`（保存在 JAR 对象元数据）\n"
               f"- 发布状态：{'已更新' if promoted else '已有新提交，未覆盖'}\n"
               "- 仅上传 JAR；Windows BAT 保存在项目中，由用户手动复制。\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    print(summary)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        code = getattr(error, "response", {}).get("Error", {}).get("Code", "")
        safe_code = code if isinstance(code, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", code) else ""
        print(f"发布失败（{type(error).__name__}{'/' + safe_code if safe_code else ''}）；"
              "请检查连接、Bucket Policy 权限和构建配置。未自动部署 Windows。", file=sys.stderr)
        sys.exit(1)
