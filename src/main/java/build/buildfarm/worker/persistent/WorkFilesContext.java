package build.buildfarm.worker.persistent;

import java.nio.file.Path;

import com.google.common.collect.ImmutableList;

import build.buildfarm.v1test.Tree;

/**
 * POJO/data class grouping all the input/output file requirements for persistent workers
 */
public class WorkFilesContext {

  public final Path opRoot;

  public final Tree execTree;

  public final ImmutableList<String> outputPaths;

  public final ImmutableList<String> outputFiles;

  public final ImmutableList<String> outputDirectories;

  public WorkFilesContext(
      Path opRoot,
      Tree execTree,
      ImmutableList<String> outputPaths,
      ImmutableList<String> outputFiles,
      ImmutableList<String> outputDirectories
  ) {
    this.opRoot = opRoot;
    this.execTree = execTree;
    this.outputPaths = outputPaths;
    this.outputFiles = outputFiles;
    this.outputDirectories = outputDirectories;
  }

}
