package build.buildfarm.persistent;


import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Data container that uniquely identifies a kind of worker process and is used as the key for the
 * {@link PersistentPool}.
 *
 * <p>We expect a small number of WorkerKeys per mnemonic. Unbounded creation of WorkerKeys will
 * break various things as well as render the workers less useful.
 */
final class WorkerKey {
  /** Build options. */
  private final ImmutableList<String> args;
  /** Environment variables. */
  private final ImmutableMap<String, String> env;
  /** Execution root of Bazel process. */
  private final Path execRoot;
  /** Mnemonic of the worker. */
  private final String mnemonic;

  /**
   * These are used during validation whether a worker is still usable. They are not used to
   * uniquely identify a kind of worker, thus it is not to be used by the .equals() / .hashCode()
   * methods.
   */
  private final HashCode workerFilesCombinedHash;
  /** Worker files with the corresponding hash code. */
  private final SortedMap<Path, HashCode> workerFilesWithHashes;
  /** Set it to true if this job is running speculatively and thus likely to be interrupted. */
  private final boolean isSpeculative;
  /** A WorkerProxy will be instantiated if true, instantiate a regular Worker if false. */
  private final boolean proxied;
  /** If true, the workers for this key are able to cancel work requests. */
  private final boolean cancellable;
  /**
   * Cached value for the hash of this key, because the value is expensive to calculate
   * (ImmutableMap and ImmutableList do not cache their hashcodes.
   */
  private final int hash;

  WorkerKey(
      ImmutableList<String> args,
      ImmutableMap<String, String> env,
      Path execRoot,
      String mnemonic,
      HashCode workerFilesCombinedHash,
      SortedMap<Path, HashCode> workerFilesWithHashes,
      boolean isSpeculative,
      boolean proxied,
      boolean cancellable) {
    this.args = Preconditions.checkNotNull(args);
    this.env = Preconditions.checkNotNull(env);
    this.execRoot = Preconditions.checkNotNull(execRoot);
    this.mnemonic = Preconditions.checkNotNull(mnemonic);
    this.workerFilesCombinedHash = Preconditions.checkNotNull(workerFilesCombinedHash);
    this.workerFilesWithHashes = Preconditions.checkNotNull(workerFilesWithHashes);
    this.isSpeculative = isSpeculative;
    this.proxied = proxied;
    this.cancellable = cancellable;
    hash = calculateHashCode();
  }

  /** Getter function for variable args. */
  public ImmutableList<String> getArgs() {
    return args;
  }

  /** Getter function for variable env. */
  public ImmutableMap<String, String> getEnv() {
    return env;
  }

  /** Getter function for variable execRoot. */
  public Path getExecRoot() {
    return execRoot;
  }

  /** Getter function for variable mnemonic. */
  public String getMnemonic() {
    return mnemonic;
  }

  /** Getter function for variable workerFilesCombinedHash. */
  public HashCode getWorkerFilesCombinedHash() {
    return workerFilesCombinedHash;
  }

  /** Getter function for variable workerFilesWithHashes. */
  public SortedMap<Path, HashCode> getWorkerFilesWithHashes() {
    return workerFilesWithHashes;
  }

  /** Returns true if workers are run speculatively. */
  public boolean isSpeculative() {
    return isSpeculative;
  }

  /** Getter function for variable proxied. */
  public boolean getProxied() {
    return proxied;
  }

  public boolean isMultiplex() {
    return getProxied() && !isSpeculative;
  }

  public boolean isCancellable() {
    return cancellable;
  }

  /** Returns a user-friendly name for this worker type. */
  public static String makeWorkerTypeName(boolean proxied, boolean mustBeSandboxed) {
    if (proxied && !mustBeSandboxed) {
      return "multiplex-worker";
    } else {
      return "worker";
    }
  }

  /** Returns a user-friendly name for this worker type. */
  public String getWorkerTypeName() {
    return makeWorkerTypeName(proxied, isSpeculative);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    WorkerKey workerKey = (WorkerKey) o;
    if (this.hash != workerKey.hash) {
      return false;
    }
    if (!args.equals(workerKey.args)) {
      return false;
    }
    if (!proxied == workerKey.proxied) {
      return false;
    }
    if (!env.equals(workerKey.env)) {
      return false;
    }
    if (!execRoot.equals(workerKey.execRoot)) {
      return false;
    }
    return mnemonic.equals(workerKey.mnemonic);

  }

  /** Since all fields involved in the {@code hashCode} are final, we cache the result. */
  @Override
  public int hashCode() {
    return hash;
  }

  private int calculateHashCode() {
    return Objects.hash(args, env, execRoot, mnemonic, proxied);
  }

  @Override
  public String toString() {
    return "WorkerKey(" + String.join(" ", args) +
        ", execRoot=" + execRoot +
        " ;env:" + String.join(", ", env.toString()) + ")";
  }
}
