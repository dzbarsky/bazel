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

"""Tests Windows prebuilt validation using synthetic PE files and ZIP archives."""

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_windows_bazel", Path(__file__).with_name("verify-windows-bazel.py")
)
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


def pe_header(machine):
  data = bytearray(256)
  data[:2] = b"MZ"
  struct.pack_into("<I", data, 0x3C, 128)
  data[128:132] = b"PE\0\0"
  struct.pack_into("<H", data, 132, machine)
  return data


class VerifyWindowsBazelTest(unittest.TestCase):

  def setUp(self):
    self.directory = tempfile.TemporaryDirectory()
    self.addCleanup(self.directory.cleanup)
    self.executable = Path(self.directory.name) / "bazel.exe"

  def write_bazel(
      self,
      launcher_machine=0x8664,
      java_machine=0x8664,
      release='JAVA_VERSION="25.0.3"\nMODULES="java.base"\n',
      omit=None,
  ):
    self.executable.write_bytes(pe_header(launcher_machine))
    with zipfile.ZipFile(self.executable, "a", zipfile.ZIP_DEFLATED) as archive:
      if omit != "java.exe":
        archive.writestr("embedded_tools/jdk/bin/java.exe", pe_header(java_machine))
      if omit != "release":
        archive.writestr("embedded_tools/jdk/release", release)

  def test_accepts_both_architectures_without_optional_os_arch(self):
    for architecture, machine in (("x86_64", 0x8664), ("arm64", 0xAA64)):
      with self.subTest(architecture=architecture):
        self.write_bazel(launcher_machine=machine, java_machine=machine)
        self.assertEqual(
            VERIFY.verify_bazel(self.executable, architecture), "25.0.3"
        )

  def test_accepts_matching_os_arch(self):
    for architecture, machine, jdk_arch in (
        ("x86_64", 0x8664, "amd64"),
        ("x86_64", 0x8664, "x86_64"),
        ("arm64", 0xAA64, "aarch64"),
    ):
      with self.subTest(architecture=architecture, jdk_arch=jdk_arch):
        self.write_bazel(
            launcher_machine=machine,
            java_machine=machine,
            release=f'JAVA_VERSION="25.0.3"\r\nOS_ARCH="{jdk_arch}"\r\n',
        )
        self.assertEqual(
            VERIFY.verify_bazel(self.executable, architecture), "25.0.3"
        )

  def test_rejects_wrong_launcher_architecture(self):
    self.write_bazel(launcher_machine=0x8664, java_machine=0xAA64)
    with self.assertRaisesRegex(ValueError, "bazel.exe: PE machine"):
      VERIFY.verify_bazel(self.executable, "arm64")

  def test_rejects_wrong_java_architecture(self):
    self.write_bazel(launcher_machine=0xAA64, java_machine=0x8664)
    with self.assertRaisesRegex(ValueError, "java.exe: PE machine"):
      VERIFY.verify_bazel(self.executable, "arm64")

  def test_rejects_mismatched_os_arch(self):
    self.write_bazel(release='JAVA_VERSION="25.0.3"\nOS_ARCH="aarch64"\n')
    with self.assertRaisesRegex(ValueError, "OS_ARCH=.*does not match"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_missing_java_version(self):
    self.write_bazel(release='MODULES="java.base"\n')
    with self.assertRaisesRegex(ValueError, "JAVA_VERSION"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_missing_jdk_members(self):
    for missing_member in ("java.exe", "release"):
      with self.subTest(missing_member=missing_member):
        self.write_bazel(omit=missing_member)
        with self.assertRaisesRegex(ValueError, "exactly one embedded JDK file"):
          VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_duplicate_jdk_member(self):
    self.write_bazel()
    with warnings.catch_warnings():
      warnings.simplefilter("ignore", UserWarning)
      with zipfile.ZipFile(self.executable, "a") as archive:
        archive.writestr("embedded_tools/jdk/bin/java.exe", pe_header(0xAA64))
    with self.assertRaisesRegex(ValueError, "exactly one embedded JDK file"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_bad_dos_header(self):
    self.write_bazel()
    with self.executable.open("r+b") as binary:
      binary.write(b"NO")
    with self.assertRaisesRegex(ValueError, "DOS header"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_bad_pe_signature(self):
    self.write_bazel()
    with self.executable.open("r+b") as binary:
      binary.seek(128)
      binary.write(b"NOPE")
    with self.assertRaisesRegex(ValueError, "PE header"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_truncated_pe_header(self):
    self.executable.write_bytes(pe_header(0x8664)[:132])
    with self.assertRaisesRegex(ValueError, "PE header"):
      VERIFY.verify_bazel(self.executable, "x86_64")

  def test_rejects_missing_zip(self):
    self.executable.write_bytes(pe_header(0x8664))
    with self.assertRaises(zipfile.BadZipFile):
      VERIFY.verify_bazel(self.executable, "x86_64")


if __name__ == "__main__":
  unittest.main()
