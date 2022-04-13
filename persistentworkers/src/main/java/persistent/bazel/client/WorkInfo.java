package persistent.bazel.client;

import java.nio.file.Path;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;

public interface WorkInfo {

  // Environment variables for worker initialization+execution
  ImmutableMap<String, String> getEnv();

  ImmutableSortedMap<Path, HashCode> getWorkerFiles();

  // Files needed to initialize the worker
  ImmutableList<Path> getToolFiles();

  // A flagfile is an @argsfile which contains flags
  ImmutableList<Path> getFlagFiles();

  ImmutableList<String> getWorkerArgs();

}
