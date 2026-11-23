// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository.decompressor;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ParallelXzInputStreamTest {
  // Four 4 KiB blocks filled with A through D, compressed using xz with two threads,
  // CRC64, a 4 KiB block size, and a 1 MiB LZMA2 dictionary.
  private static final byte[] ARCHIVE =
      Base64.getDecoder()
          .decode(
              "/Td6WFoAAATm1rRGA8AhgCAhARAAAAAAF0QoUOAP/wAZXQAg7/u//qOxXuX4P7KqJlX4aHBB"
                  + "cBUOJBjPAAAAAKj7LwuAJds/A8AhgCAhARAAAAAAF0QoUOAP/wAZXQAhb/u//qOxXuX4P7Kq"
                  + "JlX4aHBBcBUOJBjPAAAAALMWscqw4cu7A8AhgCAhARAAAAAAF0QoUOAP/wAZXQAh7/u//qOx"
                  + "XuX4P7KqJlX4aHBBcBUOJBjPAAAAADm4Pu+5OIxJA8AhgCAhARAAAAAAF0QoUOAP/wAZXQAi"
                  + "b/u//qOxXuX4P7KqJlX4aHBBcBUOJBjPAAAAAADSgub6xjIhAAQ5gCA5gCA5gCA5gCAAAKgY"
                  + "0oCsJz4tBAAAAAAEWVo=");

  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void requireWorkers() {
    assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
  }

  private FileInputStream input(byte[] bytes) throws IOException {
    return new FileInputStream(Files.write(folder.newFile().toPath(), bytes).toFile());
  }

  @Test
  public void readsBlocksInOrder() throws Exception {
    byte[] expected = new byte[4 * 4096];
    for (int block = 0; block < 4; ++block) {
      Arrays.fill(expected, block * 4096, (block + 1) * 4096, (byte) ('A' + block));
    }
    try (var file = input(ARCHIVE);
        var decoded = ParallelXzInputStream.tryOpen(file, /* minimumCompressedBytes= */ 0)) {
      assertThat(decoded).isNotNull();
      assertThat(decoded.readAllBytes()).isEqualTo(expected);
    }
  }

  @Test
  public void leavesSerialCursorAtStartForLargeDictionary() throws Exception {
    byte[] larger = ARCHIVE.clone();
    larger[19] = 28; // The first header's LZMA2 property for a 64 MiB dictionary.
    var crc = new CRC32();
    crc.update(larger, 12, 12);
    ByteBuffer.wrap(larger, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
    try (var file = input(larger)) {
      assertThat(ParallelXzInputStream.tryOpen(file, /* minimumCompressedBytes= */ 0)).isNull();
      assertThat(file.getChannel().position()).isEqualTo(0);
    }
  }

  @Test
  public void rejectsCorruptBlockChecksum() throws Exception {
    byte[] corrupt = ARCHIVE.clone();
    corrupt[71] ^= 1; // Last CRC64 byte in the first block.
    try (var file = input(corrupt);
        var decoded = ParallelXzInputStream.tryOpen(file, /* minimumCompressedBytes= */ 0)) {
      assertThat(decoded).isNotNull();
      assertThrows(IOException.class, decoded::readAllBytes);
    }
  }

  @Test
  public void interruptedReaderReleasesWorkersOnClose() throws Exception {
    for (int run = 0; run < 2; ++run) {
      try (var file = input(ARCHIVE);
          var decoded = ParallelXzInputStream.tryOpen(file, /* minimumCompressedBytes= */ 0)) {
        assertThat(decoded).isNotNull();
        Thread.currentThread().interrupt();
        try {
          assertThrows(ClosedByInterruptException.class, decoded::read);
        } finally {
          Thread.interrupted();
        }
      }
    }
  }
}
