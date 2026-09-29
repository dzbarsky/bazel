#!/usr/bin/env python3
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Verifies the architecture of a Windows Bazel executable and its embedded JDK."""

import argparse
from pathlib import Path
import re
import struct
import sys
import zipfile


PE_MACHINES = {"x86_64": 0x8664, "arm64": 0xAA64}
JDK_ARCHITECTURES = {
    "x86_64": {"amd64", "x86_64"},
    "arm64": {"aarch64", "arm64"},
}
JAVA_EXE = "embedded_tools/jdk/bin/java.exe"
JDK_RELEASE = "embedded_tools/jdk/release"


def verify_pe(binary, name, architecture):
  """Checks the DOS signature, PE signature, and COFF machine field."""
  header = binary.read(64)
  if len(header) != 64 or header[:2] != b"MZ":
    raise ValueError(f"{name}: missing or truncated DOS header")
  pe_offset = struct.unpack_from("<I", header, 0x3C)[0]
  if pe_offset < len(header):
    raise ValueError(f"{name}: invalid PE header offset {pe_offset}")
  binary.seek(pe_offset)
  pe_header = binary.read(6)
  if len(pe_header) != 6 or pe_header[:4] != b"PE\0\0":
    raise ValueError(f"{name}: missing or truncated PE header")
  machine = struct.unpack_from("<H", pe_header, 4)[0]
  expected = PE_MACHINES[architecture]
  if machine != expected:
    raise ValueError(
        f"{name}: PE machine 0x{machine:04x}, expected"
        f" 0x{expected:04x} for {architecture}"
    )


def required_member(archive, name):
  """Returns a ZIP member, rejecting missing or duplicate JDK members."""
  members = [entry for entry in archive.infolist() if entry.filename == name]
  if len(members) != 1 or members[0].is_dir():
    raise ValueError(f"Expected exactly one embedded JDK file: {name}")
  return members[0]


def verify_bazel(executable, architecture):
  """Validates both PE architectures and returns the embedded JDK version."""
  if architecture not in PE_MACHINES:
    raise ValueError(f"Unsupported Windows architecture: {architecture}")
  with executable.open("rb") as binary:
    verify_pe(binary, str(executable), architecture)
  with zipfile.ZipFile(executable) as archive:
    with archive.open(required_member(archive, JAVA_EXE)) as java:
      verify_pe(java, JAVA_EXE, architecture)
    release = archive.read(required_member(archive, JDK_RELEASE)).decode("utf-8")

  versions = re.findall(r'^JAVA_VERSION="([^"\r\n]+)"\s*$', release, re.MULTILINE)
  if len(versions) != 1:
    raise ValueError(f"{JDK_RELEASE}: expected exactly one JAVA_VERSION")
  # jlink normally writes only JAVA_VERSION and MODULES. Some JDK distributions
  # also preserve OS_ARCH; java.exe's PE header verifies the architecture in both
  # cases.
  architectures = re.findall(r"^OS_ARCH=(.*)$", release, re.MULTILINE)
  if architectures:
    if len(architectures) != 1:
      raise ValueError(f"{JDK_RELEASE}: duplicate OS_ARCH")
    jdk_architecture = architectures[0].strip().strip('"')
    if jdk_architecture not in JDK_ARCHITECTURES[architecture]:
      raise ValueError(
          f"{JDK_RELEASE}: OS_ARCH={jdk_architecture!r} does not match"
          f" {architecture}"
      )
  return versions[0]


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("executable", type=Path)
  parser.add_argument("architecture", choices=PE_MACHINES)
  args = parser.parse_args()
  try:
    java_version = verify_bazel(args.executable, args.architecture)
  except (OSError, ValueError, zipfile.BadZipFile) as error:
    print(f"Windows Bazel verification failed: {error}", file=sys.stderr)
    return 1
  print(
      f"Verified {args.executable}: Windows {args.architecture} launcher and"
      f" embedded Java {java_version}"
  )
  return 0


if __name__ == "__main__":
  sys.exit(main())
