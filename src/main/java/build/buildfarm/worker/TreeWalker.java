package build.buildfarm.worker;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.v1test.Tree;

public class TreeWalker {

  Tree tree;
  Map<Digest, Directory> proxyDirs;

  public TreeWalker(Tree tree) {
    this.tree = tree;
    this.proxyDirs = DigestUtil.proxyDirectoriesIndex(tree.getDirectoriesMap());
  }

  public ImmutableList<Input> getInputs(Path rootPath) {
    ImmutableList.Builder<Input> accumulator = ImmutableList.builder();
    Directory rootDir = proxyDirs.get(this.tree.getRootDigest());
    return getInputsFromDir(rootPath, rootDir, accumulator).build();
  }

  private ImmutableList.Builder<Input> getInputsFromDir(Path dirPath, Directory dir, ImmutableList.Builder<Input> acc) {
    dir.getFilesList().forEach(fileNode ->
        acc.add(
            Input.newBuilder()
                .setPath(dirPath.resolve(fileNode.getName()).toString())
                .setDigest(fileNode.getDigest().getHashBytes())
                .build()
        )
    );

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
}
