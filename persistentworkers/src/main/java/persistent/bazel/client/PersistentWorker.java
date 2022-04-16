package persistent.bazel.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import persistent.bazel.processes.ProtoWorkerRW;
import persistent.common.KeyedWorker;
import persistent.common.processes.ProcessWrapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class PersistentWorker implements KeyedWorker<WorkerKey, WorkRequest, WorkResponse> {

  private static Logger logger = Logger.getLogger(PersistentWorker.class.getName());

  private final WorkerKey key;

  private final ProtoWorkerRW workerRW;

  private final Path execRoot;

  public static final String TOOL_INPUT_SUBDIR = "tool_inputs";

  public PersistentWorker(WorkerKey key) throws IOException {
    this.key = key;
    Path workerSetRoot = key.getExecRoot();
    String uuid = UUID.randomUUID().toString();
    while (Files.exists(workerSetRoot.resolve(uuid))) {
      uuid = UUID.randomUUID().toString();
    }
    this.execRoot = workerSetRoot.resolve(uuid);

    Set<String> tools = loadToolInputFiles();

    ImmutableList.Builder<String> builder = ImmutableList.builder();

    ImmutableList<String> cmd = ImmutableList.copyOf(absPathTools(key.getCmd(), tools));

    ImmutableList<String> initCmd = builder
        .addAll(cmd)
        .addAll(key.getArgs())
        .build();

    Set<Path> workerFiles = ImmutableSet.copyOf(key.getWorkerFilesWithHashes().keySet());
    logger.log(Level.FINE,
        "Starting Worker[" + key.getMnemonic() + "](" + cmd + ") with files: \n" + workerFiles);

    ProcessWrapper processWrapper = new ProcessWrapper(execRoot, initCmd, key.getEnv());
    this.workerRW = new ProtoWorkerRW(processWrapper);
  }

  private Set<String> loadToolInputFiles() throws IOException {
    logger.log(Level.FINE, "loadToolInputFiles()!: " + key.getExecRoot().relativize(execRoot));
    Set<String> relToolPaths = new HashSet<>();
    Path toolInputRoot = key.getExecRoot().resolve(TOOL_INPUT_SUBDIR);
    for (Path toolInputAbsPath : key.getWorkerFilesWithHashes().keySet()) {
      Path relPath = toolInputRoot.relativize(toolInputAbsPath);
      relToolPaths.add(relPath.toString());
      Path execRootPath = execRoot.resolve(relPath);
      Files.createDirectories(execRootPath.getParent());
      if (!Files.exists(execRootPath)) {
        logger.log(Level.FINE, "Copying tool " + toolInputAbsPath + " to " + execRootPath);
        Files.copy(
            toolInputAbsPath,
            execRootPath,
            REPLACE_EXISTING,
            COPY_ATTRIBUTES
        );
      }
    }
    return relToolPaths;
  }

  private List<String> absPathTools(List<String> cmd, Set<String> tools) {
    return cmd
        .stream()
        .map(arg -> {
          try {
            String absPath = execRoot.resolve(Paths.get(arg)).toString();
            if (tools.contains(absPath)) {
              logger.log(Level.FINE, "Absolute Path of Tool: " + absPath);
              return absPath;
            }
          } catch (Exception ignore) {
          }
          return arg;
        })
        .collect(Collectors.toList());
  }

  @Override
  public WorkerKey getKey() {
    return this.key;
  }

  @Override
  public WorkResponse doWork(WorkRequest request) {
    WorkResponse response = null;
    try {
      String reqMsg = "------<" +
          "Got request with args: " +
          request.getArgumentsList() +
          "------>";
      logger.log(Level.FINE, reqMsg);

      getArgsfiles(request);

      workerRW.write(request);
      logger.log(Level.FINE, "waitAndRead()");
      response = workerRW.waitAndRead();
      int returnCode = response.getExitCode();
      if (returnCode != 0) {
        StringBuilder sb = new StringBuilder();
        sb.append("Response non-zero exit_code: ");
        sb.append(returnCode);
        sb.append("Response output: ");
        sb.append(response.getOutput());
        sb.append("\n\tProcess stderr: ");
        sb.append(workerRW.getProcessWrapper().getErrorString());
        logger.log(Level.SEVERE, sb.toString());

        // TODO might be able to remove this; scared that stdout might crash.
        StringBuilder sb2 = new StringBuilder();
        sb2.append("\n\tProcess stdout: ");
        IOUtils.readLines(workerRW.getProcessWrapper().getStdOut(), UTF_8).forEach(s -> {
          sb2.append(s);
          sb2.append("\n\t");
        });
        logger.log(Level.SEVERE, sb2.toString());
      }
    } catch (IOException e) {
      e.printStackTrace();
      logger.log(Level.SEVERE, "IO Failing with : " + e.getMessage());
    } catch (Exception e) {
      e.printStackTrace();
      logger.log(Level.SEVERE, "Failing with : " + e.getMessage());
    }
    return response;
  }

  private void getArgsfiles(WorkRequest request) throws IOException {
    List<String> relArgsfiles = new ArrayList<>();
    for (String arg : request.getArgumentsList()) {
      if (arg.startsWith("@") && !arg.startsWith("@@")) {
        relArgsfiles.add(arg.substring(1));
      }
    }
    if (!relArgsfiles.isEmpty()) {
      for (String relFile : relArgsfiles) {
        boolean copied = false;
        for (Input input : request.getInputsList()) {
          String inputPath = input.getPath();
          if (inputPath.endsWith(relFile)) {
            Path absArgsfile = execRoot.resolve(relFile);
            Files.createDirectories(absArgsfile.getParent());
            Files.copy(Paths.get(inputPath), absArgsfile, REPLACE_EXISTING, COPY_ATTRIBUTES);
            copied = true;
            break;
          }
        }
        if (!copied) {
          throw new IOException("Did not copy argfile: " + relFile);
        }
      }
    }
  }

  @Override
  public void destroy() {
    this.workerRW.getProcessWrapper().destroy();
  }

  public Optional<Integer> getExitValue() {
    ProcessWrapper pw = workerRW.getProcessWrapper();
    return pw != null && !pw.isAlive()
        ? Optional.of(pw.exitValue())
        : Optional.empty();
  }

  public String flushStdErr() {
    try {
      return this.workerRW.getProcessWrapper().flushErrorString();
    } catch (IOException ignored) {
      return "";
    }
  }

  public Path getExecRoot() {
    return this.execRoot;
  }

  public static class Supervisor extends BaseKeyedPooledObjectFactory<WorkerKey, PersistentWorker> {

    private static Supervisor singleton = null;

    public static synchronized Supervisor get() {
      if (singleton == null) {
        singleton = new Supervisor();
      }
      return singleton;
    }

    @Override
    public PersistentWorker create(WorkerKey workerKey) throws Exception {
      return new PersistentWorker(workerKey);
    }

    @Override
    public PooledObject<PersistentWorker> wrap(PersistentWorker persistentWorker) {
      return new DefaultPooledObject<>(persistentWorker);
    }


    /**
     * When a worker process is discarded, destroy its process, too.
     */
    @Override
    public void destroyObject(WorkerKey key, PooledObject<PersistentWorker> p) {
      StringBuilder msgBuilder = new StringBuilder();
      msgBuilder.append("Destroying worker from WorkerKey: ");
      msgBuilder.append(key);
      for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
        msgBuilder.append("\n\t");
        msgBuilder.append(e);
      }
      logger.log(Level.SEVERE, msgBuilder.toString());
      p.getObject().destroy();
    }

    @Override
    public boolean validateObject(WorkerKey key, PooledObject<PersistentWorker> p) {
      PersistentWorker worker = p.getObject();
      Optional<Integer> exitValue = worker.getExitValue();
      if (exitValue.isPresent()) {
        String errorStr;
        try {
          String err = worker.workerRW.getProcessWrapper().getErrorString();
          errorStr = "Stderr:\n" + err;
        } catch (Exception e) {
          errorStr = "Couldn't read Stderr: " + e;
        }
        String msg = String.format(
            "Worker unexpectedly died with exit code %d. Key:\n%s\n%s",
            exitValue.get(),
            key,
            errorStr
        );
        logger.log(Level.SEVERE, msg);
        return false;
      }
      boolean filesChanged =
          !key.getWorkerFilesCombinedHash().equals(worker.key.getWorkerFilesCombinedHash());

      if (filesChanged) {
        StringBuilder msg = new StringBuilder();
        msg.append("Worker can no longer be used, because its files have changed on disk:\n");
        msg.append(key);
        TreeSet<Path> files = new TreeSet<>();
        files.addAll(key.getWorkerFilesWithHashes().keySet());
        files.addAll(worker.key.getWorkerFilesWithHashes().keySet());
        for (Path file : files) {
          HashCode oldHash = worker.key.getWorkerFilesWithHashes().get(file);
          HashCode newHash = key.getWorkerFilesWithHashes().get(file);
          if (!oldHash.equals(newHash)) {
            msg.append("\n")
                .append(file.normalize())
                .append(": ")
                .append(oldHash != null ? oldHash : "<none>")
                .append(" -> ")
                .append(newHash != null ? newHash : "<none>");
          }
        }
        logger.log(Level.SEVERE, msg.toString());
      }

      return !filesChanged;
    }
  }
}
