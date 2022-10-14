// Copyright 2017 The Bazel Authors. All rights reserved.
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

package build.buildfarm.cas;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.TimeUnit.MINUTES;

import build.bazel.remote.execution.v2.BatchReadBlobsResponse.Response;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.Write;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCallStreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

public class MemoryCAS implements ContentAddressableStorage {
  private static final Logger logger = Logger.getLogger(MemoryCAS.class.getName());

  static final Status OK = Status.newBuilder().setCode(Code.OK.getNumber()).build();

  static final Status NOT_FOUND = Status.newBuilder().setCode(Code.NOT_FOUND.getNumber()).build();

  private final long maxSizeInBytes;
  private final Consumer<Digest> onPut;

  @GuardedBy("this")
  private final Map<String, Entry> storage;

  @GuardedBy("this")
  private final Entry header = new SentinelEntry();

  @GuardedBy("this")
  private long sizeInBytes;

  private final ContentAddressableStorage delegate;
  private final Writes writes = new Writes(this);

  public MemoryCAS(long maxSizeInBytes) {
    this(maxSizeInBytes, (digest) -> {}, /* delegate=*/ null);
  }

  public MemoryCAS(
      long maxSizeInBytes, Consumer<Digest> onPut, ContentAddressableStorage delegate) {
    this.maxSizeInBytes = maxSizeInBytes;
    this.onPut = onPut;
    this.delegate = delegate;
    sizeInBytes = 0;
    header.before = header.after = header;
    storage = Maps.newHashMap();
  }

  @Override
  public synchronized boolean contains(Digest digest, Digest.Builder result) {
    Entry entry = getEntry(digest);
    if (entry != null) {
      result.setHash(entry.key).setSizeBytes(entry.value.size());
      return true;
    }
    return delegate != null && delegate.contains(digest, result);
  }

  @Override
  public Iterable<Digest> findMissingBlobs(Iterable<Digest> digests) throws InterruptedException {
    ImmutableList.Builder<Digest> builder = ImmutableList.builder();
    synchronized (this) {
      // incur access use of the digest
      for (Digest digest : digests) {
        if (digest.getSizeBytes() != 0 && !contains(digest, Digest.newBuilder())) {
          builder.add(digest);
        }
      }
    }
    ImmutableList<Digest> missing = builder.build();
    if (delegate != null && !missing.isEmpty()) {
      return delegate.findMissingBlobs(missing);
    }
    return missing;
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Override
  public synchronized InputStream newInput(Digest digest, long offset) throws IOException {
    // implicit int bounds compare against size bytes
    if (offset < 0 || offset > digest.getSizeBytes()) {
      throw new IndexOutOfBoundsException(
          String.format("%d is out of bounds for blob %s", offset, DigestUtil.toString(digest)));
    }
    Blob blob = get(digest);
    if (blob == null) {
      if (delegate != null) {
        // FIXME change this to a read-through input stream
        return delegate.newInput(digest, offset);
      }
      throw new NoSuchFileException(DigestUtil.toString(digest));
    }
    InputStream in = blob.getData().newInput();
    in.skip(offset);
    return in;
  }

  @Override
  public void get(
      Digest digest,
      long offset,
      long limit,
      ServerCallStreamObserver<ByteString> blobObserver,
      RequestMetadata requestMetadata) {
    Blob blob = get(digest);
    if (blob == null) {
      if (delegate != null) {
        // FIXME change this to a read-through get
        delegate.get(digest, offset, limit, blobObserver, requestMetadata);
      } else {
        blobObserver.onError(io.grpc.Status.NOT_FOUND.asException());
      }
    } else {
      blobObserver.onNext(blob.getData());
      blobObserver.onCompleted();
    }
  }

  @Override
  public ListenableFuture<Iterable<Response>> getAllFuture(Iterable<Digest> digests) {
    return immediateFuture(getAll(digests));
  }

  synchronized Iterable<Response> getAll(Iterable<Digest> digests) {
    return getAll(
        digests,
        (digest) -> {
          Blob blob = get(digest);
          if (blob == null) {
            return null;
          }
          return blob.getData();
        });
  }

  public static Iterable<Response> getAll(
      Iterable<Digest> digests, Function<Digest, ByteString> blobGetter) {
    ImmutableList.Builder<Response> responses = ImmutableList.builder();
    for (Digest digest : digests) {
      responses.add(getResponse(digest, blobGetter));
    }
    return responses.build();
  }

  private static Status statusFromThrowable(Throwable t) {
    Status status = StatusProto.fromThrowable(t);
    if (status == null) {
      status =
          Status.newBuilder().setCode(io.grpc.Status.fromThrowable(t).getCode().value()).build();
    }
    return status;
  }

  public static Response getResponse(Digest digest, Function<Digest, ByteString> blobGetter) {
    Response.Builder response = Response.newBuilder().setDigest(digest);
    try {
      ByteString blob = blobGetter.apply(digest);
      if (blob == null) {
        response.setStatus(NOT_FOUND);
      } else {
        response.setData(blob).setStatus(OK);
      }
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "error getting " + DigestUtil.toString(digest), t);
      response.setStatus(statusFromThrowable(t));
    }
    return response.build();
  }

  @Override
  public Blob get(Digest digest) {
    if (digest.getSizeBytes() == 0) {
      throw new IllegalArgumentException("Cannot fetch empty blob");
    }

    Entry e = getEntry(digest);
    if (e == null) {
      if (delegate != null) {
        return delegate.get(digest);
      }
      return null;
    }
    return e.value;
  }

  private synchronized Entry getEntry(Digest digest) {
    Entry e = storage.get(digest.getHash());
    if (e == null) {
      return null;
    }
    e.recordAccess(header);
    return e;
  }

  @GuardedBy("this")
  private long size() {
    Entry e = header.before;
    long count = 0;
    while (e != header) {
      count++;
      e = e.before;
    }
    return count;
  }

  @Override
  public Write getWrite(Digest digest, UUID uuid, RequestMetadata requestMetadata) {
    return writes.get(digest, uuid);
  }

  @Override
  public void put(Blob blob) {
    put(blob, null);
    onPut.accept(blob.getDigest());
  }

  @Override
  public void put(Blob blob, Runnable onExpiration) {
    if (blob.getDigest().getSizeBytes() == 0) {
      throw new IllegalArgumentException("Cannot put empty blob");
    }

    if (add(blob, onExpiration)) {
      writes.getFuture(blob.getDigest()).set(blob.getData());
      onPut.accept(blob.getDigest());
    }
  }

  private synchronized boolean add(Blob blob, Runnable onExpiration) {
    Entry e = storage.get(blob.getDigest().getHash());
    if (e != null) {
      if (onExpiration != null) {
        e.addOnExpiration(onExpiration);
      }
      e.recordAccess(header);
      return false;
    }

    sizeInBytes += blob.size();

    while (sizeInBytes > maxSizeInBytes && header.after != header) {
      expireEntry(header.after);
    }

    if (sizeInBytes > maxSizeInBytes) {
      logger.log(
          Level.WARNING,
          String.format(
              "Out of nodes to remove, sizeInBytes = %d, maxSizeInBytes = %d, storage = %d, list = %d",
              sizeInBytes, maxSizeInBytes, storage.size(), size()));
    }

    createEntry(blob, onExpiration);

    storage.put(blob.getDigest().getHash(), header.before);

    return true;
  }

  @Override
  public long maxEntrySize() {
    return UNLIMITED_ENTRY_SIZE_MAX;
  }

  @GuardedBy("this")
  private void createEntry(Blob blob, Runnable onExpiration) {
    Entry e = new Entry(blob);
    if (onExpiration != null) {
      e.addOnExpiration(onExpiration);
    }
    e.addBefore(header);
  }

  @GuardedBy("this")
  private void expireEntry(Entry e) {
    Digest digest = DigestUtil.buildDigest(e.key, e.value.size());
    logger.log(Level.INFO, "MemoryLRUCAS: expiring " + DigestUtil.toString(digest));
    if (delegate != null) {
      try {
        Write write =
            delegate.getWrite(digest, UUID.randomUUID(), RequestMetadata.getDefaultInstance());
        try (OutputStream out = write.getOutput(1, MINUTES, () -> {})) {
          e.value.getData().writeTo(out);
        }
      } catch (IOException ioEx) {
        logger.log(
            Level.SEVERE, String.format("error delegating %s", DigestUtil.toString(digest)), ioEx);
      }
    }
    storage.remove(e.key);
    e.expire();
    sizeInBytes -= digest.getSizeBytes();
  }

  private static class Entry {
    Entry before;
    Entry after;
    final String key;
    final Blob value;
    private List<Runnable> onExpirations;

    /** implemented only for sentinel */
    private Entry() {
      key = null;
      value = null;
      onExpirations = null;
    }

    public Entry(Blob blob) {
      key = blob.getDigest().getHash();
      value = blob;
      onExpirations = null;
    }

    public void addOnExpiration(Runnable onExpiration) {
      if (onExpirations == null) {
        onExpirations = new ArrayList<>(1);
      }
      onExpirations.add(onExpiration);
    }

    public void remove() {
      before.after = after;
      after.before = before;
    }

    public void expire() {
      remove();
      if (onExpirations != null) {
        for (Runnable r : onExpirations) {
          r.run();
        }
      }
    }

    public void addBefore(Entry existingEntry) {
      after = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
    }

    public void recordAccess(Entry header) {
      remove();
      addBefore(header);
    }
  }

  class SentinelEntry extends Entry {
    SentinelEntry() {
      super();
      before = after = this;
    }

    @Override
    public void addOnExpiration(Runnable onExpiration) {
      throw new UnsupportedOperationException("cannot add expiration to sentinal");
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("cannot remove sentinel");
    }

    @Override
    public void expire() {
      throw new UnsupportedOperationException("cannot expire sentinel");
    }

    @Override
    public void addBefore(Entry existingEntry) {
      throw new UnsupportedOperationException("cannot add sentinel");
    }

    @Override
    public void recordAccess(Entry header) {
      throw new UnsupportedOperationException("cannot record sentinel access");
    }
  }
}
