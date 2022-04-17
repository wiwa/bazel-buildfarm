package build.buildfarm.worker.persistent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.protobuf.ByteString;

public class ParsedWorkFiles {

  private static final Logger logger = Logger.getLogger(ParsedWorkFiles.class.getName());

  public final Path opRoot;
  // The Paths in these collections should all be absolute and under opRoot
  public final ImmutableSet<Path> toolInputs;
  public final ImmutableMap<Path, Input> allInputs;

  public ParsedWorkFiles(
      Path opRoot,
      ImmutableSet<Path> toolInputs,
      ImmutableMap<Path, Input> allInputs
  ) {
    this.opRoot = opRoot;
    this.toolInputs = toolInputs;
    this.allInputs = allInputs;

    // Currently not a concern but could be in the future
    for (Path tool : toolInputs) {
      if (!allInputs.containsKey(tool)) {
        String msg = "Tool not found in inputs: " + tool;
        logger.severe(msg);
        throw new IllegalArgumentException(msg);
      }
    }
  }

  /**
   * After this method is called, 'fileToAccess' will be accessible via 'accessFrom'
   *
   * @param fileToAccess absolute path
   * @param accessFrom   absolute path
   */
  public void accessFileFrom(Path fileToAccess, Path accessFrom) throws IOException {
    if (!allInputs.containsKey(fileToAccess)) {
      throw new IllegalArgumentException(
          "accessFileFrom() called on non-input file: " + fileToAccess);
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

  public static ParsedWorkFiles from(WorkFilesContext workFilesContext) {
    ImmutableMap<Path, Input> pathInputs = workFilesContext.getPathInputs();

    ImmutableList<Path> inputAbsPaths = pathInputs.keySet().asList();
    ImmutableSet<Path> toolsAbsPaths = InputsExtractor.getToolFiles(inputAbsPaths);

    String inputsDebugMsg = "ParsedWorkFiles:" +
        "\nallInputs: " + pathInputs.keySet() +
        "\ntoolInputs: " + toolsAbsPaths;

    logger.fine(inputsDebugMsg);

    return new ParsedWorkFiles(workFilesContext.opRoot, toolsAbsPaths, pathInputs);
  }
}
