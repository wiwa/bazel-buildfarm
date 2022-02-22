package build.buildfarm.persistent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.ByteString;

import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.persistent.PersistentPool.WorkerPoolConfig;

public class PersistentSpawnRunner {

  public static void main(String[] args) {
    try {
      withWorkerJar(args[0]);
    } catch (Exception e) {
      System.out.println("Error!");
      System.out.println(e.getMessage());
      System.exit(1);
    }
    System.exit(0);
  }

  public static void withWorkerJar(String jar) throws IOException, InterruptedException  {
    Path workerDir = Files.createTempDirectory("persistentworker");

    boolean workerSandboxing = false;

    WorkerFactory workerFactory = new WorkerFactory(workerDir, workerSandboxing);

    Map<String, Integer> maxInstances = new HashMap<>();
    maxInstances.put("", 4);

    WorkerPoolConfig workerPoolConfig =
        new WorkerPoolConfig(
            workerFactory,
            new ArrayList<>(maxInstances.entrySet())
        );


    PersistentPool workers = new PersistentPool(workerPoolConfig);

    SortedMap<Path, HashCode> workerFiles = ImmutableSortedMap.of();
//    new ImmutableSortedMap.Builder<>()
//        .put(Paths.get(args[0]).toAbsolutePath(), HashCode.fromString(args[0]))
//        .build();

    ImmutableList<String> workerArgs =
        ImmutableList.copyOf(Arrays.asList("java", "-jar", jar, "--persistent_worker"));

    // TODO need actual worker process
    WorkerKey key =
        new WorkerKey(
            workerArgs,
            ImmutableMap.of(),
            workerDir,
            "MyMnemonic",
            HashCode.fromInt(127348190),
            workerFiles,
            false,
            false,
            false);

    PersistentWorker worker = workers.borrowObject(key);

    WorkRequest request = createWorkRequest();

    worker.prepareExecution();

    WorkResponse r1 = execOnWorker(worker, request);

    if (r1 == null) {
      System.out.println("r1 is null!");
      System.out.println(worker.getLogFile());
      workers.invalidateObject(key, worker);
      worker = null;
    } else {
      System.out.println("worker output1:");
      System.out.println(r1.getOutput());

      returnWorker(workers, worker);

      PersistentWorker worker2 = workers.borrowObject(key);


      WorkResponse r2 = execOnWorker(worker2, request);
      if (r2 == null) {
        System.out.println("r2 is null!");
        System.out.println(worker2.getLogFile());
        workers.invalidateObject(key, worker2);
      } else {
        System.out.println("worker output2:");
        System.out.println(r2.getOutput());
        returnWorker(workers, worker2);
      }
    }
  }

  private static WorkResponse execOnWorker(PersistentWorker worker, WorkRequest request)
      throws IOException {

    System.out.println("execOnWorker");
    worker.putRequest(request);
    WorkResponse response = null;
    try{
      response = worker.getResponse(request.getRequestId());
    } catch (IOException e) {
      System.out.println("IO Failing with : " + e.getMessage());
    } catch (Exception e) {
      System.out.println("Any Failing with : " + e.getMessage());
    }

    System.out.println("worker.getResponse finished");

    return response;
  }

  private static void returnWorker(PersistentPool workers, PersistentWorker worker) {
    if (worker != null) {
      System.out.println("returning worker");
      workers.returnObject(worker.workerKey, worker);
    }
  }

  private static WorkRequest createWorkRequest() {

    Path infile = Paths.get("./input.txt").toAbsolutePath();

    byte[] inbytes = null;
    try {
      inbytes = Files.readAllBytes(infile);
    } catch (Exception e) {
      System.err.println(e);
    }
    int size = inbytes.length;

    HashCode hash = DigestUtil.HashFunction.SHA256.getHash().hashBytes(inbytes);
    Input input = Input.newBuilder()
        .setPath(infile.toString())
        .setDigest(ByteString.copyFrom(hash.asBytes()))
        .build();



    return WorkRequest.newBuilder()
        .setRequestId(0)
        .addAllArguments(Arrays.asList("grep", "hello", input.getPath()))
        .addAllInputs(Collections.singletonList(input))
        .build();
  }
}
