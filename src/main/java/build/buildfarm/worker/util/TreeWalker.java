package build.buildfarm.worker.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.logging.Logger;
import java.util.Optional;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.NodeProperties;
import build.bazel.remote.execution.v2.NodeProperty;
import build.buildfarm.common.ProxyDirectoriesIndex;
import build.buildfarm.v1test.Tree;

public class TreeWalker {

  private static final Logger logger = Logger.getLogger("TreeWalker");

  final Tree tree;
  final Map<Digest, Directory> proxyDirs;
  
  ImmutableMap<Path, FileNode> files = null;
  ImmutableMap<Path, Input> absPathInputs = null;
  ImmutableMap<Path, Input> toolInputs = null;

  public TreeWalker(Tree tree) {
    this.tree = tree;
    this.proxyDirs = new ProxyDirectoriesIndex(tree.getDirectoriesMap());
  }

  public ImmutableMap<Path, Input> getAllInputs(Path opRoot) {
    if (absPathInputs == null) {
      ImmutableMap<Path, FileNode> relFiles = getAllFiles();

      ImmutableMap.Builder<Path, Input> inputs = ImmutableMap.builder();
      for (Map.Entry<Path, FileNode> pf : relFiles.entrySet()) {
        Path absPath = opRoot.resolve(pf.getKey());
        inputs.put(absPath, inputFromFile(absPath, pf.getValue()));
      }
      absPathInputs = inputs.build();
    }
    return absPathInputs;
  }

  public ImmutableMap<Path, Input> getToolInputs(Path opRoot) {
    if (toolInputs == null) {
      ImmutableMap<Path, FileNode> relFiles = getAllFiles();
      ImmutableMap.Builder<Path, Input> inputs = ImmutableMap.builder();

      for (Map.Entry<Path, FileNode> pf : relFiles.entrySet()) {
        FileNode fn = pf.getValue();
        if (isToolInput(fn)) {
          Path absPath = opRoot.resolve(pf.getKey());
          inputs.put(absPath, inputFromFile(absPath, fn));
        }
      }
      toolInputs = inputs.build();
    }
    return toolInputs;
  }

  private ImmutableMap<Path, FileNode> getAllFiles() {
    if (files == null) {
      ImmutableMap.Builder<Path, FileNode> accumulator = ImmutableMap.builder();
      Directory rootDir = proxyDirs.get(tree.getRootDigest());
      files = getFilesFromDir(Paths.get("."), rootDir, accumulator).build();
    }
    return files;
  }

  private Input inputFromFile(Path absPath, FileNode fileNode) {
    return Input.newBuilder()
      .setPath(absPath.toString())
      .setDigest(fileNode.getDigest().getHashBytes())
      .build();
  }

  private ImmutableMap.Builder<Path, FileNode> getFilesFromDir(
      Path dirPath, Directory dir, ImmutableMap.Builder<Path, FileNode> acc
  ) {
    dir.getFilesList().forEach(fileNode -> {
      Path path = dirPath.resolve(fileNode.getName()).normalize();
      acc.put(path, fileNode);
    });

    // Recurse into subdirectories
    dir.getDirectoriesList().forEach(dirNode ->
        getFilesFromDir(
            dirPath.resolve(dirNode.getName()),
            this.proxyDirs.get(dirNode.getDigest()),
            acc
        )
    );
    return acc;
  }

  private static boolean isToolInput(FileNode fileNode) {
    for (NodeProperty prop : fileNode.getNodeProperties().getPropertiesList()) {
      if (prop.getName().equals("bazel_tool_input")) {
        return true;
      }
    }
    return false;
  }
}
