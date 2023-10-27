package build.buildfarm.worker.util;

import build.bazel.remote.execution.v2.*;
import build.buildfarm.v1test.Tree;
import build.buildfarm.common.DigestUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import com.google.protobuf.ByteString;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static build.buildfarm.worker.util.InputsIndexer.BAZEL_TOOL_INPUT_MARKER;

public class WorkerTestUtils {

    public static final DigestUtil DIGEST_UTIL = new DigestUtil(DigestUtil.HashFunction.SHA256);

    public static FileNode makeFileNode(String filename, String content, NodeProperties nodeProperties) {
        return FileNode.newBuilder()
                .setName(filename)
                .setDigest(DIGEST_UTIL.compute(ByteString.copyFromUtf8(content)))
                .setIsExecutable(false)
                .setNodeProperties(nodeProperties)
                .build();
    }

    public static DirectoryNode makeDirNode(String dirname, Digest dirDigest) {
        // Pretty sure we don't need the actual hash for our testing purposes
        return DirectoryNode.newBuilder().setName(dirname).setDigest(dirDigest).build();
    }

    public static Digest addDirToTree(Tree.Builder treeBuilder, String dirname, Directory dir) {
        ByteString dirnameBytes = ByteString.copyFromUtf8(dirname);
        Digest digest = DIGEST_UTIL.compute(dirnameBytes);
        String hash = digest.getHash();
        treeBuilder.putDirectories(hash, dir);
        return digest;
    }

    public static NodeProperties makeNodeProperties(ImmutableMap<String, String> props) {
        return NodeProperties.newBuilder()
                .addAllProperties(
                        props.entrySet().stream()
                                .map(
                                        kv ->
                                                NodeProperty.newBuilder()
                                                        .setName(kv.getKey())
                                                        .setValue(kv.getValue())
                                                        .build())
                                .collect(Collectors.toList()))
                .build();
    }

    public static Input makeInput(Path fileDir, FileNode file) {
        Path fileNodePath = fileDir.resolve(file.getName());
        return Input.newBuilder()
                .setPath(fileNodePath.toString())
                .setDigest(file.getDigest().getHashBytes())
                .build();
    }

    public static Command makeCommand() {
        ImmutableList<String> outputFiles = ImmutableList.of("file1");
        ImmutableList<String> outputDirs = ImmutableList.of();
        ImmutableList<String> outputPaths = ImmutableList
                .<String>builder()
                .addAll(outputFiles)
                .addAll(outputDirs)
                .build();


        return Command.newBuilder()
                .addAllOutputFiles(outputFiles)
                .addAllOutputDirectories(outputDirs)
                .addAllOutputPaths(outputPaths)
                .build();
    }

    public static class TreeFile {
        public final String path;
        public final boolean isTool;

        // null means directory
        public final String content;

        public TreeFile(String path) {
            this(path, "", false);
        }

        public TreeFile(String path, String content) {
            this(path, content, false);
        }

        public TreeFile(String path, String content, boolean isTool) {
            this.path = path;
            this.isTool = isTool;
            this.content = content;
        }

        public boolean isDir() {
            return this.content == null;
        }
    }
    public static Tree makeTree(
            String rootDirPath,
            List<TreeFile> files
    ) {
        Tree.Builder treeBuilder = Tree.newBuilder();
        if (files.isEmpty()) {
            return treeBuilder.build();
        }
        Directory.Builder rootDirBuilder = Directory.newBuilder();

        Map<String, Directory.Builder> dirBuilders = new HashMap<>();

        for (TreeFile file : files) {
            if (file.isDir()){
                dirBuilders.computeIfAbsent(file.path, (filePath) -> Directory.newBuilder());
            } else {
                NodeProperties props = NodeProperties.getDefaultInstance();
                if (file.isTool) {
                    props = makeNodeProperties(ImmutableMap.of(BAZEL_TOOL_INPUT_MARKER, ""));
                }
                FileNode fileNode = makeFileNode(file.path, file.content, props);
                Path parentDirPath = Paths.get(file.path).getParent();
                if (parentDirPath != null) {
                    String parentDirPathStr = parentDirPath.normalize().toString();
                    Directory.Builder parentDirBuilder = dirBuilders
                            .computeIfAbsent(parentDirPathStr, (filePath) -> Directory.newBuilder());
                    parentDirBuilder.addFiles(fileNode);
                }
                rootDirBuilder.addFiles(fileNode);
            }
        }

        for (Map.Entry<String, Directory.Builder> entry : dirBuilders.entrySet()) {
            String subDirName = entry.getKey();
            Directory subDir = entry.getValue().build();
            Digest subDirDigest = addDirToTree(treeBuilder, subDirName, subDir);
            rootDirBuilder.addDirectories(makeDirNode(subDirName, subDirDigest));
        }

        Digest rootDirDigest = addDirToTree(treeBuilder, rootDirPath, rootDirBuilder.build());
        treeBuilder.setRootDigest(rootDirDigest);

        return treeBuilder.build();
    }
}
