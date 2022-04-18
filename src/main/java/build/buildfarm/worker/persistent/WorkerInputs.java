package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.protobuf.ByteString;

import persistent.common.util.Args;

public class WorkerInputs {

  private static final Logger logger = Logger.getLogger(WorkerInputs.class.getName());

  public final Path opRoot;
  // Some tool inputs are not under opRoot
  public final ImmutableSet<Path> absToolInputs;
  // The Paths in these collections should all be absolute and under opRoot
  public final ImmutableSet<Path> opToolInputs;
  public final ImmutableMap<Path, Input> allInputs;

  public final ImmutableSet<Path> allToolInputs;

  public final ImmutableSet<Path> missingArgsfiles;

  public WorkerInputs(
      Path opRoot,
      ImmutableSet<Path> absToolInputs,
      ImmutableSet<Path> opToolInputs,
      ImmutableMap<Path, Input> allInputs,
      ImmutableSet<Path> missingArgsfiles
  ) {
    this.opRoot = opRoot;
    this.absToolInputs = absToolInputs;
    this.opToolInputs = opToolInputs;
    this.allInputs = allInputs;
    this.missingArgsfiles = missingArgsfiles;

    this.allToolInputs = ImmutableSet.<Path>builder()
        .addAll(absToolInputs)
        .addAll(opToolInputs)
        .build();

    // Currently not a concern but could be in the future
    for (Path tool : opToolInputs) {
      if (!allInputs.containsKey(tool)) {
        String msg = "Tool not found in inputs: " + tool;
        logger.severe(msg);
        throw new IllegalArgumentException(msg);
      }
    }
  }

  public boolean containsTool(Path tool) {
    return allToolInputs.contains(opRoot.resolve(tool));
  }

  public Path relativizeInput(Path newRoot, Path input) {
    return newRoot.resolve(opRoot.relativize(input));
  }

  /**
   * After this method is called, 'fileToAccess' will be accessible via 'accessFrom'
   *
   * @param fileToAccess absolute path
   * @param accessFrom   absolute path
   */
  public void accessFileFrom(Path fileToAccess, Path accessFrom) throws IOException {
    if (!allInputs.containsKey(fileToAccess) && !missingArgsfiles.contains(fileToAccess)) {
      throw new IllegalArgumentException(
          "accessFileFrom() called on non-input non-argsfile file: " + fileToAccess);
    }
    FileAccessUtils.copyFile(fileToAccess, accessFrom);
  }

  public ByteString digestFor(Path inputPath) {
    Input input = allInputs.get(inputPath);
    if (input == null) {
      throw new IllegalArgumentException("digestFor() called on non-input file: " + inputPath);
    }
    return input.getDigest();
  }

  public static WorkerInputs from(WorkFilesContext workFilesContext, List<String> reqArgs) {
    
    ImmutableMap<Path, Input> pathInputs = workFilesContext.getPathInputs();

    ImmutableSet<Path> toolsAbsPaths = workFilesContext.getToolInputs().keySet();

    ImmutableSet<Path> toolInputs = ImmutableSet.copyOf(
        toolsAbsPaths
        .stream()
        .filter(p -> p.startsWith(workFilesContext.opRoot))
        .iterator()
    );
    ImmutableSet<Path> absToolInputs = ImmutableSet.copyOf(
        toolsAbsPaths
            .stream()
            .filter(p -> !toolInputs.contains(p))
            .iterator()
    );
    
    ImmutableSet.Builder<Path> missingArgsfilesBuilder = ImmutableSet.builder();
    List<Path> absArgsfiles = argsFiles(workFilesContext.opRoot, reqArgs);
    for (Path p : absArgsfiles) {
      if (!pathInputs.containsKey(p)) {
        missingArgsfilesBuilder.add(p);
      }
    }
    ImmutableSet<Path> missingArgsfiles = missingArgsfilesBuilder.build();

    String inputsDebugMsg = "ParsedWorkFiles:" +
        "\nallInputs: " + pathInputs.keySet() +
        "\ntoolInputs: " + toolInputs +
        "\nabsToolInputs: " + absToolInputs +
        "\nmissingArgsFiles " + missingArgsfiles;

    logger.fine(inputsDebugMsg);

    return new WorkerInputs(workFilesContext.opRoot, absToolInputs, toolInputs, pathInputs, missingArgsfiles);
  }

  private static List<Path> argsFiles(Path opRoot, List<String> reqArgs) {
    List<Path> files = new ArrayList<>();
    for (String a : reqArgs) {
      if (Args.isArgsFile(a)) {
        try {
          files.add(opRoot.resolve(Paths.get(a.substring(1))));
        } catch (Exception ignored) {}
      }
    }
    return files;
  }
}
