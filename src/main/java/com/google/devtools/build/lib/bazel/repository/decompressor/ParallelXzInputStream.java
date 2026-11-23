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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.LZMA2InputStream;
import org.tukaani.xz.ResettableArrayCache;
import org.tukaani.xz.SeekableInputStream;
import org.tukaani.xz.SeekableXZInputStream;
import org.tukaani.xz.check.Check;
import org.tukaani.xz.common.DecoderUtil;

/** Reads independent local XZ blocks in parallel while preserving the decompressed byte order. */
final class ParallelXzInputStream extends InputStream {
  private static final int WORKERS = 2;
  private static final int PERMITS = Math.min(4, Runtime.getRuntime().availableProcessors());
  // Individual block readers reopen the index. Bound that work before submitting any tasks.
  private static final int MAX_BLOCKS = 256;
  private static final int MAX_BLOCK_BYTES = 32 * 1024 * 1024;
  private static final int DECODER_KIB = 32768;
  private static final int LARGE_ARRAY = 1 << 20;
  private static final Semaphore BUDGET = new Semaphore(PERMITS);

  private static final class ChannelInput extends SeekableInputStream {
    private final FileChannel channel;
    private final byte[] one = new byte[1];
    private long position;

    ChannelInput(FileChannel channel) {
      this.channel = channel;
    }

    @Override
    public int read() throws IOException {
      return read(one, 0, 1) == 1 ? Byte.toUnsignedInt(one[0]) : -1;
    }

    @Override
    public int read(byte[] output, int offset, int count) throws IOException {
      if (Thread.currentThread().isInterrupted()) {
        throw new ClosedByInterruptException();
      }
      int read = channel.read(ByteBuffer.wrap(output, offset, count), position);
      if (read > 0) {
        position += read;
      }
      return read;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public long length() throws IOException {
      return channel.size();
    }

    @Override
    public void seek(long next) throws IOException {
      if (next < 0) {
        throw new IOException("Negative XZ input position");
      }
      position = next;
    }
  }

  private static final class DictionaryCache extends ArrayCache {
    private byte[] dictionary;

    @Override
    public byte[] getByteArray(int size, boolean fillWithZeros) {
      if (size < LARGE_ARRAY) {
        return super.getByteArray(size, fillWithZeros);
      }
      byte[] reuse = dictionary;
      dictionary = null;
      if (reuse == null || reuse.length != size) {
        return new byte[size];
      }
      if (fillWithZeros) {
        Arrays.fill(reuse, (byte) 0);
      }
      return reuse;
    }

    @Override
    public void putArray(byte[] array) {
      if (array.length >= LARGE_ARRAY) {
        dictionary = array;
      }
    }
  }

  private static final class Slot {
    final byte[] data;
    private final ResettableArrayCache cache = new ResettableArrayCache(new DictionaryCache());

    Slot(int size) {
      data = new byte[size];
    }

    int decode(FileChannel channel, int block) throws IOException {
      try (var input = new SeekableXZInputStream(new ChannelInput(channel), DECODER_KIB, cache)) {
        input.seekToBlock(block);
        int size = Math.toIntExact(input.getBlockSize(block));
        int offset = 0;
        while (offset < size) {
          if (Thread.currentThread().isInterrupted()) {
            throw new ClosedByInterruptException();
          }
          int count = input.read(data, offset, Math.min(65536, size - offset));
          if (count <= 0) {
            throw new IOException("Truncated XZ block");
          }
          offset += count;
        }
        return size;
      } finally {
        cache.reset();
      }
    }
  }

  private record Pending(Slot slot, Future<Integer> size) {}

  // Restrict parallel reads to bounded, size-declaring LZMA2 blocks. The ordinary stream
  // remains available for any other valid XZ filter chain or dictionary size.
  private static boolean hasBoundedBlocks(FileChannel channel, SeekableXZInputStream index)
      throws IOException {
    var reader = new ChannelInput(channel);
    byte[] header = new byte[1024];
    for (int block = 0; block < index.getBlockCount(); ++block) {
      if (index.getBlockSize(block) <= 0) {
        return false;
      }
      reader.seek(index.getBlockCompPos(block));
      int headerLength = (reader.read() + 1) * 4;
      if (headerLength < 8) {
        return false;
      }
      header[0] = (byte) (headerLength / 4 - 1);
      int end = headerLength - 4;
      if (reader.readNBytes(header, 1, headerLength - 1) != headerLength - 1
          || !DecoderUtil.isCRC32Valid(header, 0, end, end)
          || Byte.toUnsignedInt(header[1]) != 0xC0) {
        return false;
      }
      var flags = new ByteArrayInputStream(header, 2, end - 2);
      long compressedSize = DecoderUtil.decodeVLI(flags);
      int overhead = headerLength + Check.getInstance(index.getBlockCheckType(block)).getSize();
      long paddedSize = index.getBlockCompSize(block);
      if (compressedSize <= 0
          || paddedSize - compressedSize < overhead
          || paddedSize - compressedSize > overhead + 3
          || DecoderUtil.decodeVLI(flags) != index.getBlockSize(block)
          || DecoderUtil.decodeVLI(flags) != 0x21
          || DecoderUtil.decodeVLI(flags) != 1) {
        return false;
      }
      int property = flags.read();
      if (property < 0 || property > 26) {
        return false;
      }
      for (int padding; (padding = flags.read()) >= 0; ) {
        if (padding != 0) {
          return false;
        }
      }
      int dictionarySize = (2 | (property & 1)) << ((property >>> 1) + 11);
      if (index.getIndexMemoryUsage() + LZMA2InputStream.getMemoryUsage(dictionarySize)
          > DECODER_KIB) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  static InputStream tryOpen(FileInputStream source, long minimumCompressedBytes) throws IOException {
    if (!BUDGET.tryAcquire(WORKERS)) {
      return null;
    }
    boolean transferred = false;
    try {
      FileChannel channel = source.getChannel();
      if (channel.size() < minimumCompressedBytes) {
        return null;
      }
      int blocks;
      long size;
      try (var index = new SeekableXZInputStream(new ChannelInput(channel), DECODER_KIB)) {
        blocks = index.getBlockCount();
        size = index.getLargestBlockSize();
        if (index.getStreamCount() != 1 || blocks < WORKERS || blocks > MAX_BLOCKS
            || size > MAX_BLOCK_BYTES || !hasBoundedBlocks(channel, index)) {
          return null;
        }
      }
      var input = new ParallelXzInputStream(channel, blocks, Math.toIntExact(size));
      transferred = true;
      return input;
    } catch (ClosedByInterruptException e) {
      throw e;
    } catch (IOException e) {
      // The positional index read leaves the original stream untouched for serial extraction.
      return null;
    } finally {
      if (!transferred) {
        BUDGET.release(WORKERS);
      }
    }
  }

  private final FileChannel channel;
  private final int blockCount;
  private final ExecutorService executor;
  private final ArrayDeque<Pending> pending;
  private Slot current;
  private int nextBlock;
  private int position;
  private int limit;
  private boolean closed;
  private IOException failure;

  private ParallelXzInputStream(FileChannel channel, int blocks, int size) {
    this.channel = channel;
    this.blockCount = blocks;
    this.executor = Executors.newFixedThreadPool(WORKERS);
    this.pending = new ArrayDeque<>(WORKERS);
    try {
      for (int i = 0; i < WORKERS; ++i) {
        schedule(new Slot(size));
      }
    } catch (RuntimeException | Error e) {
      executor.shutdownNow();
      executor.close();
      throw e;
    }
  }

  private void schedule(Slot slot) {
    if (nextBlock < blockCount) {
      final int block = nextBlock++;
      pending.addLast(new Pending(slot, executor.submit(() -> slot.decode(channel, block))));
    }
  }

  private boolean ensureData() throws IOException {
    if (closed) {
      throw new IOException("XZ stream is closed");
    }
    if (failure != null) {
      throw failure;
    }
    if (Thread.currentThread().isInterrupted()) {
      failure = new ClosedByInterruptException();
      throw failure;
    }
    if (position < limit) {
      return true;
    }
    if (current != null) {
      schedule(current);
      current = null;
    }
    if (pending.isEmpty()) {
      return false;
    }
    Pending head = pending.removeFirst();
    try {
      limit = head.size().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      var interrupted = new ClosedByInterruptException();
      interrupted.initCause(e);
      failure = interrupted;
      throw failure;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException unchecked) {
        throw unchecked;
      }
      failure = cause instanceof IOException error ? error : new IOException(cause);
      throw failure;
    }
    current = head.slot();
    position = 0;
    return true;
  }

  @Override
  public int read() throws IOException {
    return ensureData() ? Byte.toUnsignedInt(current.data[position++]) : -1;
  }

  @Override
  public int read(byte[] output, int offset, int amount) throws IOException {
    Objects.checkFromIndexSize(offset, amount, output.length);
    if (amount == 0) {
      return 0;
    }
    if (!ensureData()) {
      return -1;
    }
    int count = Math.min(amount, limit - position);
    System.arraycopy(current.data, position, output, offset, count);
    position += count;
    return count;
  }

  @Override
  public long skip(long amount) throws IOException {
    long skipped = 0;
    while (skipped < amount && ensureData()) {
      int count = (int) Math.min(amount - skipped, limit - position);
      position += count;
      skipped += count;
    }
    return skipped;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      executor.shutdownNow();
      channel.close();
    } finally {
      try {
        executor.close();
      } finally {
        pending.clear();
        current = null;
        BUDGET.release(WORKERS);
      }
    }
  }
}
