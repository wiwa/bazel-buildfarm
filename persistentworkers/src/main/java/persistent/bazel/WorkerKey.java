package persistent.bazel;

import java.nio.file.Path;

import com.google.common.collect.ImmutableList;

public class WorkerKey {

  public Path workDir;

  public ImmutableList<String> workerArgs;

}
