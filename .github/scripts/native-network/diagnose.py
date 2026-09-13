#!/usr/bin/env python3
"""Bounded public BCR observations; network failures are evidence, not test skips."""

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import time

JDK_URL = (
    "https://cdn.azul.com/zulu/bin/zulu25.32.17-ca-jdk25.0.2-macosx_aarch64.tar.gz"
)
JDK_SHA256 = "537ac74fa1ca2c4dd8f6063ddede0138ae4a896f128bfe10b428a7dcc4aa929f"
URLS = {
    "buildozer": (
        "https://bcr.bazel.build/modules/buildozer/8.5.1/MODULE.bazel",
        "a35d9561b3fc5b18797c330793e99e3b834a473d5fbd3d7d7634aafc9bdb6f8f",
    ),
    "platforms": (
        "https://bcr.bazel.build/modules/platforms/1.0.0/MODULE.bazel",
        "f05feb42b48f1b3c225e4ccf351f367be0371411a803198ec34a389fb22aa580",
    ),
}
MODES = {
    "default": [],
    "ipv6-preferred": ["-Djava.net.preferIPv6Addresses=true"],
    "ipv4-only": ["-Djava.net.preferIPv4Stack=true"],
}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(output, name, argv, timeout=5, *, cwd=None, required=False, env=None):
    """Keep each command's status, including timeout; kill only its own group."""
    argv = [str(arg) for arg in argv]
    started = time.monotonic()
    receipt = {"argv": argv, "timeoutSeconds": timeout}
    with (output / (name + ".log")).open("wb") as log:
        try:
            with subprocess.Popen(
                argv,
                stdout=log,
                stderr=subprocess.STDOUT,
                cwd=cwd,
                env=env,
                start_new_session=True,
            ) as process:
                try:
                    receipt["exitCode"] = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    receipt["exitCode"] = process.wait()
                    receipt["timedOut"] = True
        except OSError as error:
            receipt["startError"] = str(error)
    receipt["elapsedSeconds"] = round(time.monotonic() - started, 3)
    write_json(output / (name + ".json"), receipt)
    if required and receipt.get("exitCode") != 0:
        raise RuntimeError("Setup failed: " + name)
    return receipt


def probe(runtime, classes, output):
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "inputs.json", {"urls": URLS, "modes": MODES})
    commands = {
        "uname": ["/usr/bin/uname", "-sm"],
        "os-version": ["/usr/bin/sw_vers"],
        "route-default4": ["/sbin/route", "-n", "get", "default"],
        "route-default6": ["/sbin/route", "-n", "get", "-inet6", "default"],
        "routes4": ["/usr/sbin/netstat", "-rn", "-f", "inet"],
        "routes6": ["/usr/sbin/netstat", "-rn", "-f", "inet6"],
        "resolver": ["/usr/sbin/scutil", "--dns"],
        "host-cache": [
            "/usr/bin/dscacheutil",
            "-q",
            "host",
            "-a",
            "name",
            "bcr.bazel.build",
        ],
    }
    for name, command in commands.items():
        run(output, name, command)
    for family, query, route_flag in [(4, "A", "-inet"), (6, "AAAA", "-inet6")]:
        name = "dns" + str(family)
        run(
            output,
            name,
            ["/usr/bin/dig", "+short", "+time=2", "+tries=1", "bcr.bazel.build", query],
        )
        addresses = []
        for line in (output / (name + ".log")).read_text(errors="replace").splitlines():
            try:
                address = ipaddress.ip_address(line.strip())
            except ValueError:
                continue
            if address.version == family and str(address) not in addresses:
                addresses.append(str(address))
        for index, address in enumerate(addresses[:2]):
            run(
                output,
                f"route{family}-{index}",
                ["/sbin/route", "-n", "get", route_flag, address],
            )
    for name, options in MODES.items():
        run(
            output,
            "java-" + name,
            [runtime / "bin/java", *options, "-cp", classes, "BcrProbe"],
            45,
        )
    for family in [4, 6]:
        for name, (url, expected) in URLS.items():
            result_name = f"curl{family}-{name}"
            body = output / (result_name + ".body")
            result = run(
                output,
                result_name,
                [
                    "/usr/bin/curl",
                    f"--ipv{family}",
                    "--silent",
                    "--show-error",
                    "--connect-timeout",
                    "8",
                    "--max-time",
                    "20",
                    "--max-filesize",
                    "1048576",
                    "--proto",
                    "=https",
                    "--output",
                    body,
                    "--write-out",
                    "remote_ip=%{remote_ip}\nhttp_code=%{http_code}\ntime_total=%{time_total}\n",
                    url,
                ],
                22,
            )
            result["expectedBodySHA256"] = expected
            if body.exists():
                result["bodySHA256"] = sha256(body)
                result["bodyMatches"] = result["bodySHA256"] == expected
            write_json(output / (result_name + ".json"), result)
    # A completed observer is not a successful fetch. Keep all per-command failures.
    write_json(
        output / "observer-completed.json",
        {"completed": True, "networkSuccessNotAsserted": True},
    )


def main():
    source = Path(__file__).resolve().parents[3]
    inputs = Path(__file__).resolve().parent
    results = source / "native-network-results"
    results.mkdir(exist_ok=True)
    try:
        if platform.system() != "Darwin" or platform.machine() != "arm64":
            raise RuntimeError(
                "This diagnostic requires the native macos-15 ARM64 runner"
            )
        scratch = Path(os.environ["RUNNER_TEMP"]) / "native-network"
        scratch.mkdir()  # Refuse to reuse another run's state.
        run(
            results,
            "source-head",
            ["git", "rev-parse", "HEAD"],
            cwd=source,
            required=True,
        )
        archive = scratch / "jdk.tar.gz"
        run(
            results,
            "jdk-download",
            [
                "/usr/bin/curl",
                "--fail",
                "--location",
                "--connect-timeout",
                "15",
                "--max-time",
                "120",
                "--output",
                archive,
                JDK_URL,
            ],
            125,
            required=True,
        )
        actual = sha256(archive)
        write_json(
            results / "jdk-archive.json",
            {"url": JDK_URL, "expected": JDK_SHA256, "actual": actual},
        )
        if actual != JDK_SHA256:
            raise RuntimeError("Pinned JDK archive hash mismatch")
        jdk = scratch / "jdk"
        jdk.mkdir()
        run(
            results,
            "jdk-extract",
            [
                "/usr/bin/tar",
                "xf",
                archive,
                "--no-same-owner",
                "--strip-components=1",
                "-C",
                jdk,
            ],
            30,
            required=True,
        )
        runtime = scratch / "runtime"
        modules_file = source / "src/jdeps_modules.golden"
        modules = ",".join(modules_file.read_text().splitlines()) + ",jdk.crypto.ec"
        run(
            results,
            "jdk-minimize",
            [
                jdk / "bin/jlink",
                "--module-path",
                jdk / "jmods",
                "--add-modules",
                modules,
                "--vm=server",
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--add-options= --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders",
                "--output",
                runtime,
            ],
            90,
            required=True,
        )
        shutil.copyfile(runtime / "release", results / "runtime-release.txt")
        write_json(
            results / "runtime.json",
            {
                "javaSHA256": sha256(runtime / "bin/java"),
                "modulesFileSHA256": sha256(modules_file),
                "modules": modules,
            },
        )
        run(
            results,
            "runtime-version",
            [runtime / "bin/java", "-version"],
            required=True,
        )
        classes = scratch / "classes"
        classes.mkdir()
        run(
            results,
            "compile-probe",
            [jdk / "bin/javac", "-d", classes, inputs / "BcrProbe.java"],
            30,
            required=True,
        )
        probe(runtime, classes, results / "host")
        bazelisk = scratch / "bazelisk"
        run(
            results,
            "bazelisk-download",
            [
                "/usr/bin/curl",
                "--fail",
                "--location",
                "--connect-timeout",
                "15",
                "--max-time",
                "60",
                "--output",
                bazelisk,
                "https://github.com/bazelbuild/bazelisk/releases/download/v1.29.0/bazelisk-darwin-arm64",
            ],
            65,
            required=True,
        )
        bazelisk.chmod(0o755)
        write_json(
            results / "bazelisk.json", {"version": "1.29.0", "sha256": sha256(bazelisk)}
        )
        workspace = scratch / "workspace"
        workspace.mkdir()
        for name in ["MODULE.bazel", "BUILD.bazel", "network_test.bzl"]:
            shutil.copyfile(inputs / name, workspace / name)
        output_base = scratch / "bazel-output"
        env = dict(
            os.environ,
            USE_BAZEL_VERSION="9.2.0",
            BAZELISK_HOME=str(scratch / "bazelisk-home"),
        )
        bazel = [
            bazelisk,
            "--batch",
            "--ignore_all_rc_files",
            "--output_user_root=" + str(scratch / "bazel-user"),
            "--output_base=" + str(output_base),
            "--host_jvm_args=-Xmx2g",
        ]
        sandbox_result = run(
            results,
            "sandbox-test",
            [
                *bazel,
                "test",
                "//:bcr_network_test",
                "--jobs=2",
                "--local_test_jobs=1",
                "--nocache_test_results",
                "--test_output=all",
                "--sandbox_debug",
                "--noremote_upload_local_results",
                "--build_event_json_file=" + str(results / "sandbox.bep.json"),
                "--execution_log_json_file=" + str(results / "sandbox.execution.json"),
                "--test_env=DIAGNOSTIC_PYTHON=" + sys.executable,
                "--test_env=DIAGNOSTIC_DRIVER=" + str(Path(__file__).resolve()),
                "--test_env=DIAGNOSTIC_RUNTIME=" + str(runtime),
                "--test_env=DIAGNOSTIC_CLASSES=" + str(classes),
            ],
            600,
            cwd=workspace,
            env=env,
        )
        profiles = sorted(output_base.glob("sandbox/darwin-sandbox/*/sandbox.sb"))
        if len(profiles) > 16:
            raise RuntimeError("Unexpected profile count")
        for index, profile in enumerate(profiles):
            shutil.copyfile(profile, results / f"sandbox-{index}.sb")
        logs = workspace / "bazel-testlogs/bcr_network_test"
        if logs.is_dir():
            shutil.copytree(logs, results / "sandbox-testlogs")
        downloaded_bazel = sorted(
            (scratch / "bazelisk-home").glob("downloads/**/bin/bazel")
        )
        write_json(
            results / "bazel-binaries.json",
            [
                {"path": str(p.relative_to(scratch)), "sha256": sha256(p)}
                for p in downloaded_bazel
            ],
        )
        events = []
        bep = results / "sandbox.bep.json"
        if bep.exists():
            events = [
                json.loads(line)
                for line in bep.read_text().splitlines()
                if line.strip()
            ]
        configured = [
            e["configured"]
            for e in events
            if e.get("id", {}).get("targetConfigured", {}).get("label")
            == "//:bcr_network_test"
            and "configured" in e
        ]
        tests = [
            e["testResult"]
            for e in events
            if e.get("id", {}).get("testResult", {}).get("label")
            == "//:bcr_network_test"
        ]
        versions = [
            e["started"].get("buildToolVersion") for e in events if "started" in e
        ]
        proof = {
            "profiles": [
                {
                    "name": p.name,
                    "containsNetworkDeny": "(deny network*)" in p.read_text(),
                }
                for p in sorted(results.glob("sandbox-*.sb"))
            ],
            "configured": configured,
            "testResults": tests,
            "bazelVersions": versions,
        }
        write_json(results / "sandbox-proof.json", proof)
        if (
            sandbox_result.get("exitCode") != 0
            or versions != ["9.2.0"]
            or not configured
            or "requires-network" not in configured[0].get("tag", [])
            or len(tests) != 1
            or not profiles
            or not downloaded_bazel
            or tests[0].get("status") != "PASSED"
            or tests[0].get("cachedLocally", False)
            or tests[0].get("executionInfo", {}).get("cachedRemotely", False)
            or tests[0].get("executionInfo", {}).get("strategy") != "darwin-sandbox"
        ):
            raise RuntimeError(
                "Incomplete normal Darwin sandbox observation; inspect saved evidence"
            )
    finally:
        write_json(
            results / "manifest.json",
            [
                {
                    "path": str(p.relative_to(results)),
                    "bytes": p.stat().st_size,
                    "sha256": sha256(p),
                }
                for p in sorted(results.rglob("*"))
                if p.is_file() and p.name != "manifest.json"
            ],
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", nargs=3, metavar=("RUNTIME", "CLASSES", "OUTPUT"))
    parser.add_argument(
        "--describe",
        action="store_true",
        help="Print pinned inputs without network access",
    )
    arguments = parser.parse_args()
    if arguments.describe:
        print(
            json.dumps(
                {
                    "jdkURL": JDK_URL,
                    "jdkSHA256": JDK_SHA256,
                    "urls": URLS,
                    "modes": MODES,
                },
                indent=2,
            )
        )
    elif arguments.probe:
        probe(*(Path(arg) for arg in arguments.probe))
    else:
        main()
