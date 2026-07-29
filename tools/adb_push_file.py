#!/usr/bin/env python3
"""Push one local file to a directory on a connected Android device.

Examples:
    python tools/adb_push_file.py chart.logic.json /sdcard/Download/pjsk
    python tools/adb_push_file.py model.ncnn.bin /data/local/tmp/models --root
    python tools/adb_push_file.py model.bin /data/user/0/com.example/app_models \
        --root --serial 192.168.1.20:5555
"""

from __future__ import annotations

import argparse
import os
import shlex
import subprocess
import sys
from pathlib import Path


def run(command: list[str], *, capture_output: bool = False) -> subprocess.CompletedProcess[str]:
    """Run an adb command and turn failures into a concise error message."""
    try:
        return subprocess.run(
            command,
            check=True,
            text=True,
            capture_output=capture_output,
        )
    except FileNotFoundError as exc:
        raise RuntimeError(f"找不到 adb: {command[0]}") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "").strip()
        raise RuntimeError(detail or f"命令执行失败: {' '.join(command)}") from exc


def android_quote(value: str) -> str:
    """Quote a value for the Android shell, including paths that contain spaces."""
    return shlex.quote(value)


def adb_prefix(adb: str, serial: str | None) -> list[str]:
    return [adb, *( ["-s", serial] if serial else [])]


def remote_size(prefix: list[str], remote_path: str, use_root: bool) -> int:
    command = f"wc -c < {android_quote(remote_path)}"
    if use_root:
        result = run([*prefix, "shell", "su", "-c", command], capture_output=True)
    else:
        result = run([*prefix, "shell", command], capture_output=True)
    try:
        return int(result.stdout.strip().splitlines()[-1])
    except (IndexError, ValueError) as exc:
        raise RuntimeError(f"无法读取手机端文件大小: {result.stdout.strip()}") from exc


def push_file(
    source: str | Path,
    remote_dir: str,
    *,
    name: str | None = None,
    serial: str | None = None,
    adb: str = "adb",
    root: bool = False,
    verify: bool = True,
) -> str:
    """Push one file and return its full path on the Android device.

    Raises RuntimeError on connection, permission, copy, or verification errors.
    """
    source = Path(source).expanduser().resolve()
    if not source.is_file():
        raise RuntimeError(f"本机文件不存在: {source}")
    if not remote_dir.startswith("/"):
        raise RuntimeError("手机目标目录必须是绝对路径，例如 /sdcard/Download/pjsk")

    remote_name = name or source.name
    if "/" in remote_name or remote_name in {"", ".", ".."}:
        raise RuntimeError("name 只能是文件名，不能包含路径分隔符")

    remote_dir = remote_dir.rstrip("/") or "/"
    remote_path = f"{remote_dir}/{remote_name}" if remote_dir != "/" else f"/{remote_name}"
    prefix = adb_prefix(adb, serial)

    # Ensure a device is available before copying potentially large model files.
    run([*prefix, "get-state"], capture_output=True)

    if root:
        staging_path = f"/data/local/tmp/adb_push_{os.getpid()}_{remote_name}"
        try:
            run([*prefix, "push", str(source), staging_path])
            root_command = (
                f"mkdir -p {android_quote(remote_dir)} && "
                f"cp -f {android_quote(staging_path)} {android_quote(remote_path)} && "
                f"rm -f {android_quote(staging_path)}"
            )
            run([*prefix, "shell", "su", "-c", root_command])
        finally:
            # Ignore cleanup errors so the original failure remains visible.
            subprocess.run([*prefix, "shell", "rm", "-f", staging_path], text=True, check=False)
    else:
        run([*prefix, "shell", "mkdir", "-p", remote_dir])
        run([*prefix, "push", str(source), remote_path])

    if verify:
        local_size = source.stat().st_size
        pushed_size = remote_size(prefix, remote_path, root)
        if pushed_size != local_size:
            raise RuntimeError(
                f"文件大小校验失败: 本机 {local_size} 字节，手机端 {pushed_size} 字节"
            )
    return remote_path


def main() -> int:
    parser = argparse.ArgumentParser(description="通过 ADB 将单个文件推送到手机目录")
    parser.add_argument("source", type=Path, help="本机文件路径")
    parser.add_argument("remote_dir", help="手机目标目录，例如 /sdcard/Download/pjsk")
    parser.add_argument("--name", help="手机端文件名，默认保留本机文件名")
    parser.add_argument("--serial", help="设备序列号；无线调试示例：192.168.1.20:5555")
    parser.add_argument("--adb", default="adb", help="adb 可执行文件路径，默认从 PATH 查找")
    parser.add_argument("--root", action="store_true", help="使用 su 写入需要 root 权限的目录")
    parser.add_argument("--no-verify", action="store_true", help="不校验手机端与本机文件大小")
    args = parser.parse_args()

    remote_path = push_file(
        args.source,
        args.remote_dir,
        name=args.name,
        serial=args.serial,
        adb=args.adb,
        root=args.root,
        verify=not args.no_verify,
    )
    print(f"推送成功: {args.source} -> {remote_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"错误: {exc}", file=sys.stderr)
        raise SystemExit(1)
