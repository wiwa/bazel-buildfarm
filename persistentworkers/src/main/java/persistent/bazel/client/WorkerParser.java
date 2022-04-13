package persistent.bazel.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

public class WorkerParser {

  public WorkerConfig computeWorkerConfig(WorkInfo workInfo) {
    //

    // ImmutableMap<String, String> env =
    //     localEnvProvider.rewriteLocalEnv(actionArgs.getEnvironment(), binTools, "/tmp");

    SortedMap<Path, HashCode> workerFiles = workInfo.getWorkerFiles();

    HashCode workerFilesCombinedHash = WorkerFilesHash.getCombinedHash(workerFiles);
    return null;
  }

}
