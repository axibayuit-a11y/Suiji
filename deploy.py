#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""随记 Android 项目提交与 GitHub Release 发布脚本。

用法：
  python deploy.py "提交说明"
  python deploy.py "0.5.1 更新说明" --release
  python deploy.py "只重新发布安装包" --release --skip-build

安全约定：
  - GitHub 操作统一使用 Token：优先读取 SUIJI_GITHUB_TOKEN / GH_TOKEN，
    否则读取本机 GitHub CLI 安全凭据库中的 Token。
  - 脚本不保存、不打印 GitHub Token。
  - APK、本地配置和构建目录不会提交到仓库；脚本自身随源码维护。
  - 默认直连；如需代理，通过 SUIJI_PROXY_URL 显式设置。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DEFAULT_PROXY_URL = ""
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "debug"
ARM64_OUTPUT = APK_DIR / "app-arm64-v8a-debug.apk"
UNIVERSAL_OUTPUT = APK_DIR / "app-universal-debug.apk"
ARM64_RELEASE = ROOT / "随记-arm64-debug.apk"
UNIVERSAL_RELEASE = ROOT / "随记-debug.apk"
LS_EEND_MODEL = ROOT / "test" / "fixtures" / "lseend-streaming-1-8spk.onnx"
PROTECTED_PATHS = {
    "local.properties",
    "keystore.properties",
}


def cleanup_legacy_release_copies() -> None:
    """Remove versioned upload copies left by older script revisions."""
    removed = 0
    for pattern in ("Suiji-v*-arm64.apk", "Suiji-v*-universal.apk"):
        for apk in ROOT.glob(pattern):
            if apk.is_file():
                apk.unlink()
                removed += 1
    if removed:
        print(f"已清理本地历史 APK: {removed} 个")


def configure_environment() -> dict[str, str]:
    env = os.environ.copy()
    proxy_url = env.get("SUIJI_PROXY_URL", DEFAULT_PROXY_URL).strip()
    if proxy_url:
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
            env[key] = proxy_url
        print(f"网络代理: {proxy_url}")
    else:
        print("网络代理: 已关闭")

    java_home = find_compatible_java_home(env)
    if java_home:
        env["JAVA_HOME"] = str(java_home)
        env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
        print(f"构建 JDK: {java_home}")
    else:
        raise RuntimeError(
            "未找到可用的 JDK 17。请设置 SUIJI_JAVA_HOME，或安装 Android Studio 自带的 JBR。"
        )
    return env


def java_major(java_exe: Path) -> int | None:
    try:
        result = subprocess.run(
            [str(java_exe), "-version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        output = result.stderr + result.stdout
        match = re.search(r'version "(?:(?:1\.)?)(\d+)', output)
        return int(match.group(1)) if match else None
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def find_compatible_java_home(env: dict[str, str]) -> Path | None:
    candidates: list[Path] = []
    for key in ("SUIJI_JAVA_HOME", "JAVA_HOME"):
        value = env.get(key, "").strip()
        if value:
            candidates.append(Path(value))

    candidates.extend(
        [
            Path(r"C:\Program Files\Android\Android Studio\jbr"),
            Path.home()
            / "Documents"
            / "Codex"
            / "2026-08-08"
            / "new-chat-2"
            / "work"
            / "temurin17-portable-2"
            / "jdk-17.0.20+8",
        ]
    )

    for candidate in candidates:
        java_exe = candidate / "bin" / "java.exe"
        if java_exe.is_file() and java_major(java_exe) in (17, 21):
            return candidate
    return None


def run(
    command: list[str],
    *,
    env: dict[str, str],
    capture: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    print("执行:", " ".join(command))
    result = subprocess.run(
        command,
        cwd=ROOT,
        env=env,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=capture,
    )
    if check and result.returncode != 0:
        if capture and result.stderr:
            print(result.stderr.strip())
        raise RuntimeError(f"命令执行失败，退出码 {result.returncode}: {command[0]}")
    return result


def require_tools(env: dict[str, str]) -> None:
    for tool in ("git", "gh"):
        if not shutil.which(tool, path=env.get("PATH")):
            raise RuntimeError(f"未找到命令: {tool}")

    configure_github_token(env)
    status = run(
        ["gh", "auth", "status", "--hostname", "github.com"],
        env=env,
        capture=True,
        check=False,
    )
    if status.returncode != 0:
        raise RuntimeError(
            "GitHub Token 无效。请设置 SUIJI_GITHUB_TOKEN，或重新运行 gh auth login"
        )

    # 让 HTTPS git push 与 gh release 统一使用同一个 GH_TOKEN。
    setup = run(
        ["gh", "auth", "setup-git", "--hostname", "github.com"],
        env=env,
        capture=True,
        check=False,
    )
    if setup.returncode != 0:
        raise RuntimeError("GitHub Token 无法配置给 git push")


def configure_github_token(env: dict[str, str]) -> None:
    """Resolve one token without ever persisting or printing it."""
    token = (
        env.get("SUIJI_GITHUB_TOKEN", "").strip()
        or env.get("GH_TOKEN", "").strip()
        or env.get("GITHUB_TOKEN", "").strip()
    )
    source = "环境变量"
    if not token:
        result = subprocess.run(
            ["gh", "auth", "token", "--hostname", "github.com"],
            cwd=ROOT,
            env=env,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
        )
        if result.returncode == 0:
            token = result.stdout.strip()
            source = "GitHub CLI 安全凭据库"
    if not token:
        raise RuntimeError(
            "未找到 GitHub Token。请设置 SUIJI_GITHUB_TOKEN，或运行 gh auth login"
        )

    env["GH_TOKEN"] = token
    env["GITHUB_TOKEN"] = token
    print(f"GitHub 认证: 已从{source}加载 Token（内容不显示）")


def version_name() -> str:
    content = BUILD_FILE.read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    if not match:
        raise RuntimeError("无法从 app/build.gradle.kts 读取 versionName")
    return match.group(1)


def build(env: dict[str, str]) -> None:
    run(
        [sys.executable, "test/test_lseend_streaming_model.py"],
        env=env,
    )
    gradle = str(ROOT / "gradlew.bat")
    run(
        [gradle, "testDebugUnitTest", "lintDebug", "assembleDebug"],
        env=env,
    )
    run(["git", "diff", "--check"], env=env)


def verify_staged_files(env: dict[str, str]) -> None:
    result = run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
        env=env,
        capture=True,
    )
    files = [item for item in result.stdout.split("\0") if item]
    violations = []
    for item in files:
        normalized = item.replace("\\", "/")
        name = normalized.rsplit("/", 1)[-1]
        if (
            normalized in PROTECTED_PATHS
            or normalized.endswith(".apk")
            or "__pycache__" in normalized.split("/")
            or name.endswith((".pyc", ".pyo"))
            or name.startswith(".env")
            or name.endswith((".jks", ".keystore"))
        ):
            violations.append(normalized)
    if violations:
        raise RuntimeError("检测到禁止提交的文件: " + ", ".join(violations))


def commit_and_push(message: str, env: dict[str, str]) -> None:
    run(["git", "add", "-A"], env=env)
    verify_staged_files(env)
    staged = run(
        ["git", "diff", "--cached", "--quiet"],
        env=env,
        check=False,
    )
    if staged.returncode == 1:
        run(["git", "commit", "-m", message], env=env)
    elif staged.returncode != 0:
        raise RuntimeError("无法检查 Git 暂存区")
    else:
        print("源码没有变化，跳过提交。")
    run(["git", "push", "origin", "main"], env=env)


def publish_release(message: str, env: dict[str, str]) -> None:
    version = version_name()
    tag = f"v{version}"
    for output in (ARM64_OUTPUT, UNIVERSAL_OUTPUT):
        if not output.is_file():
            raise RuntimeError(f"找不到 APK: {output}")
    if not LS_EEND_MODEL.is_file():
        raise RuntimeError(f"找不到 LS-EEND 模型: {LS_EEND_MODEL}")

    head = run(["git", "rev-parse", "HEAD"], env=env, capture=True).stdout.strip()
    tag_target = run(
        ["git", "rev-list", "-n", "1", tag],
        env=env,
        capture=True,
        check=False,
    )
    if tag_target.returncode == 0 and tag_target.stdout.strip() != head:
        raise RuntimeError(f"{tag} 已指向旧提交，请先提升 versionName 再发布")

    # 项目目录只保留两个最新版安装包；GitHub 所需的版本化英文名放在临时目录。
    shutil.copy2(ARM64_OUTPUT, ARM64_RELEASE)
    shutil.copy2(UNIVERSAL_OUTPUT, UNIVERSAL_RELEASE)

    with tempfile.TemporaryDirectory(prefix="suiji-release-") as temp_dir:
        temp_root = Path(temp_dir)
        arm64_asset = temp_root / f"Suiji-v{version}-arm64.apk"
        universal_asset = temp_root / f"Suiji-v{version}-universal.apk"
        shutil.copy2(ARM64_OUTPUT, arm64_asset)
        shutil.copy2(UNIVERSAL_OUTPUT, universal_asset)
        assets = [str(arm64_asset), str(universal_asset), str(LS_EEND_MODEL)]

        exists = run(
            ["gh", "release", "view", tag],
            env=env,
            capture=True,
            check=False,
        )
        if exists.returncode != 0:
            run(
                [
                    "gh",
                    "release",
                    "create",
                    tag,
                    "--target",
                    "main",
                    "--title",
                    f"随记 {version}",
                    "--notes",
                    message,
                    "--draft",
                ],
                env=env,
            )
        # 大文件分开上传，避免单次多文件请求中断后全部重传。
        for asset in assets:
            run(["gh", "release", "upload", tag, asset, "--clobber"], env=env)
        run(
            [
                "gh",
                "release",
                "edit",
                tag,
                "--title",
                f"随记 {version}",
                "--notes",
                message,
                "--draft=false",
                "--latest",
            ],
            env=env,
        )
    print(f"Release 发布完成: {tag}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="提交随记源码并发布 APK")
    parser.add_argument("message", help="Git 提交说明，同时作为 Release 更新说明")
    parser.add_argument("--release", action="store_true", help="创建或更新当前版本 Release")
    parser.add_argument("--skip-build", action="store_true", help="跳过测试和 APK 构建")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        cleanup_legacy_release_copies()
        env = configure_environment()
        require_tools(env)
        if not args.skip_build:
            build(env)
        commit_and_push(args.message.strip(), env)
        if args.release:
            publish_release(args.message.strip(), env)
        print("随记提交与发布流程完成。")
        return 0
    except (RuntimeError, OSError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
