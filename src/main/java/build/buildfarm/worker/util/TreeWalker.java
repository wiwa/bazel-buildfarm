package build.buildfarm.worker.util;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.logging.Logger;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.NodeProperties;
import build.bazel.remote.execution.v2.NodeProperty;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.v1test.Tree;

public class TreeWalker {

  private static final Logger logger = Logger.getLogger("TreeWalker");

  Tree tree;
  Map<Digest, Directory> proxyDirs;

  public TreeWalker(Tree tree) {
    this.tree = tree;
    this.proxyDirs = DigestUtil.proxyDirectoriesIndex(tree.getDirectoriesMap());
  }

  public ImmutableMap<Path, Input> getInputs(Path rootPath) {
    ImmutableMap.Builder<Path, Input> accumulator = ImmutableMap.builder();
    Directory rootDir = proxyDirs.get(tree.getRootDigest());
    return getInputsFromDir(rootPath, rootDir, accumulator).build();
  }

  private ImmutableMap.Builder<Path, Input> getInputsFromDir(
      Path dirPath, Directory dir, ImmutableMap.Builder<Path, Input> acc
  ) {
    
    dir.getFilesList().forEach(fileNode -> {
      if (isToolInput(fileNode)) {
        Path path = dirPath.resolve(fileNode.getName()).normalize();
        acc.put(
          path,
          Input.newBuilder()
              .setPath(path.toString())
              .setDigest(fileNode.getDigest().getHashBytes())
              .build()
          );
      }
    });

    // Recurse into subdirectories
    dir.getDirectoriesList().forEach(dirNode ->
        getInputsFromDir(
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
