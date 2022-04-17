package persistent.bazel.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Based off of copy-pasting from Bazel's WorkerKey.
 * Comments also ripped off, credits to the Bazel Authors.
 * Has less dependencies, but only ProtoBuf and non-multiplex support.
 *
 * I wish I could just make `data class` or `case class`.
 *
 * Data container that uniquely identifies a kind of worker process.
 */
public final class WorkerKey {
  /** Adding the cmd here instead of having multiple pools */
  private final ImmutableList<String> cmd;
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
  /** If true, the workers run inside a sandbox. */
  private final boolean sandboxed;
  /** If true, the workers for this key are able to cancel work requests. */
  private final boolean cancellable;
  /**
   * Cached value for the hash of this key, because the value is expensive to calculate
   * (ImmutableMap and ImmutableList do not cache their hashcodes).
   */
  private final int hash;

  // Operation execRoot which created this WorkerKey
  public final Path opRoot;

  public WorkerKey(
      ImmutableList<String> cmd,
      ImmutableList<String> args,
      ImmutableMap<String, String> env,
      Path execRoot,
      String mnemonic,
      HashCode workerFilesCombinedHash,
      SortedMap<Path, HashCode> workerFilesWithHashes,
      boolean sandboxed,
      boolean cancellable,
      Path opRoot
  ) {
    // Part of hash
    this.cmd = Preconditions.checkNotNull(cmd);
    this.args = Preconditions.checkNotNull(args);
    this.env = Preconditions.checkNotNull(env);
    this.execRoot = Preconditions.checkNotNull(execRoot);
    this.mnemonic = Preconditions.checkNotNull(mnemonic);
    this.sandboxed = sandboxed;
    this.cancellable = cancellable;
    // Not part of hash
    this.workerFilesCombinedHash = Preconditions.checkNotNull(workerFilesCombinedHash);
    this.workerFilesWithHashes = Preconditions.checkNotNull(workerFilesWithHashes);

    this.hash = calculateHashCode();

    this.opRoot = opRoot;
  }

  /** Getter function for variable cmd. */
  public ImmutableList<String> getCmd() {
    return cmd;
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

  /** Returns true if workers are sandboxed. */
  public boolean isSandboxed() {
    return sandboxed;
  }

  public boolean isCancellable() {
    return cancellable;
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
    if (!cmd.equals(workerKey.cmd)) {
      return false;
    }
    if (!args.equals(workerKey.args)) {
      return false;
    }
    if (!cancellable == workerKey.cancellable) {
      return false;
    }
    if (!sandboxed == workerKey.sandboxed) {
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
    // Use the string representation of the protocolFormat because the hash of the same enum value
    // can vary across instances.
    return Objects.hash(
        cmd,
        args,
        env,
        execRoot,
        mnemonic,
        cancellable,
        sandboxed
    );
  }

  // Not as cool as using Bazel CommandFailureUtils
  @Override
  public String toString() {
    return "WorkerKey(" + "\n\t"
        + "cmd=" + cmd + ",\n\t"
        + "args=" + args + ",\n\t"
        + "env=" + env + ",\n\t"
        + "mnemonic=" + mnemonic + ",\n\t"
        + "execRoot=" + execRoot.toAbsolutePath()
        + "\n)";
  }
}
