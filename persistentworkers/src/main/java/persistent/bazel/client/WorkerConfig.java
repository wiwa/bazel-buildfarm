package persistent.bazel.client;

import java.util.List;

import com.google.common.collect.ImmutableList;

/**
 * From Bazel's WorkerConfig class.
 */
public class WorkerConfig {

  private final WorkerKey workerKey;
  private final List<String> flagFiles;

  public WorkerConfig(WorkerKey workerKey, List<String> flagFiles) {
    this.workerKey = workerKey;
    this.flagFiles = ImmutableList.copyOf(flagFiles);
  }

  public WorkerKey getWorkerKey() {
    return workerKey;
  }

  public List<String> getFlagFiles() {
    return flagFiles;
  }
}
