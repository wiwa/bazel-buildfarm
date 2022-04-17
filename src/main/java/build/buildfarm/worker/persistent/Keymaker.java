package build.buildfarm.worker.persistent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.SortedMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkerKey;

public class Keymaker {

  public static WorkerKey make(
      ImmutableList<String> workerInitCmd,
      ImmutableList<String> workerInitArgs,
      ImmutableMap<String, String> workerEnv,
      String executionName,
      ParsedWorkFiles workerFiles
  ) {
    boolean sandboxed = true;
    boolean cancellable = false;

    Path workRoot = calculateWorkRoot(workerInitCmd, workerInitArgs, workerEnv, executionName,
        sandboxed, cancellable);
    Path toolsRoot = workRoot.resolve(PersistentWorker.TOOL_INPUT_SUBDIR);

    SortedMap<Path, HashCode> hashedTools = workerFilesWithHashes(workerFiles);
    HashCode combinedToolsHash = workerFilesCombinedHash(toolsRoot, hashedTools);

    return new WorkerKey(
        workerInitCmd,
        workerInitArgs,
        workerEnv,
        workRoot,
        executionName,
        combinedToolsHash,
        hashedTools,
        sandboxed,
        cancellable
    );
  }

  // Hash of a subset of the WorkerKey
  static private Path calculateWorkRoot(
      ImmutableList<String> workerInitCmd,
      ImmutableList<String> workerInitArgs,
      ImmutableMap<String, String> workerEnv,
      String executionName,
      boolean sandboxed,
      boolean cancellable
  ) {
    int workRootId = Objects.hash(
        workerInitCmd,
        workerInitArgs,
        workerEnv,
        sandboxed,
        cancellable
    );
    String workRootDirName = "work-root_" + executionName + "_" + workRootId;
    return PersistentExecutor.workRootsDir.resolve(workRootDirName);
  }

  static private ImmutableSortedMap<Path, HashCode> workerFilesWithHashes(
      ParsedWorkFiles workerFiles
  ) {

    ImmutableSortedMap.Builder<Path, HashCode> workerFileHashBuilder = ImmutableSortedMap.naturalOrder();

    for (Path opPath : workerFiles.opToolInputs) {
      Path relPath = workerFiles.opRoot.relativize(opPath);

      HashCode toolInputHash = HashCode.fromBytes(workerFiles.digestFor(opPath).toByteArray());
      workerFileHashBuilder.put(relPath, toolInputHash);
    }

    return workerFileHashBuilder.build();
  }

  // Even though we hash the toolsRoot-resolved path, it doesn't exist yet.
  static private HashCode workerFilesCombinedHash(
      Path toolsRoot, SortedMap<Path, HashCode> hashedTools
  ) {
    Hasher hasher = Hashing.sha256().newHasher();
    hashedTools.forEach((relPath, toolHash) -> {
      hasher.putString(toolsRoot.resolve(relPath).toString(), StandardCharsets.UTF_8);
      hasher.putBytes(toolHash.asBytes());
    });
    return hasher.hash();
  }
}
