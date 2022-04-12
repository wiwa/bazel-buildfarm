package persistent.bazel.client;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

public class WorkerParser<T> {

//  public WorkerConfig computeKey(T spawn) {
//    List<String> flagFiles = new ArrayList<>();
//    ImmutableList<String> workerArgs = splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles);
//    ImmutableMap<String, String> env =
//        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");
//
//    SortedMap<PathFragment, HashCode> workerFiles =
//        WorkerFilesHash.getWorkerFilesWithHashes(
//            spawn, context.getArtifactExpander(), context.getMetadataProvider());
//
//    HashCode workerFilesCombinedHash = WorkerFilesHash.getCombinedHash(workerFiles);
//    return null;
//  }
//
//  public ImmutableList<String> splitSpawnArgsIntoWorkerArgsAndFlagFiles(T Spawn) {
//    return null;
//  }

}
