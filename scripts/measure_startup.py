#!/usr/bin/env python3
"""Measure gradle-tapi-mcp-server startup until initialize response."""

from __future__ import annotations

import json
import os
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
RUNS = 3
TIMEOUT_SEC = 120

INIT = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "startup-benchmark", "version": "1.0"},
    },
}


def resolve_jar() -> Path:
    libs_dir = PROJECT_DIR / "build/libs"
    jars = sorted(
        path
        for path in libs_dir.glob("gradle-tapi-mcp-server-*.jar")
        if not path.name.endswith("-plain.jar")
    )
    if len(jars) != 1:
        raise FileNotFoundError(
            f"Expected exactly one fat jar in {libs_dir}; run ./gradlew jar first"
        )
    return jars[0]


def measure_once(jar: Path, env: dict[str, str] | None) -> tuple[int | None, str | None]:
    proc = subprocess.Popen(
        ["java", "-jar", str(jar)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env={**os.environ, **(env or {})},
        text=True,
        bufsize=1,
    )
    assert proc.stdin and proc.stdout

    start = time.perf_counter()
    proc.stdin.write(json.dumps(INIT) + "\n")
    proc.stdin.flush()

    line_holder: list[str | None] = [None]

    def read_stdout() -> None:
        try:
            line_holder[0] = proc.stdout.readline()
        except Exception:
            line_holder[0] = None

    reader = threading.Thread(target=read_stdout, daemon=True)
    reader.start()
    reader.join(TIMEOUT_SEC)

    elapsed_ms = int((time.perf_counter() - start) * 1000)
    proc.kill()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()

    if reader.is_alive():
        return None, f"timeout after {TIMEOUT_SEC}s"
    if not line_holder[0]:
        return None, "empty response"
    return elapsed_ms, line_holder[0][:120]


def run_scenario(jar: Path, label: str, env: dict[str, str] | None) -> None:
    print(f"\n=== {label} ===")
    samples: list[int] = []
    for i in range(1, RUNS + 1):
        ms, preview = measure_once(jar, env)
        if ms is None:
            print(f"  run {i}: FAILED ({preview})")
        else:
            samples.append(ms)
            print(f"  run {i}: {ms} ms  preview={preview!r}")
    if samples:
        print(
            f"  summary: avg={statistics.mean(samples):.0f} ms, "
            f"min={min(samples)} ms, max={max(samples)} ms"
        )


def main() -> int:
    try:
        jar = resolve_jar()
    except FileNotFoundError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(f"JAR: {jar}")
    print(f"Runs per scenario: {RUNS}, timeout: {TIMEOUT_SEC}s")
    run_scenario(jar, "MCP only (no GRADLE_PROJECT_DIR)", None)
    run_scenario(
        jar,
        "MCP + auto-connect (GRADLE_PROJECT_DIR)",
        {"GRADLE_PROJECT_DIR": str(PROJECT_DIR)},
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
