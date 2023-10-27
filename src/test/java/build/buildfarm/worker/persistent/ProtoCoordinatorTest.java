package build.buildfarm.worker.persistent;

import build.bazel.remote.execution.v2.Command;
import build.buildfarm.v1test.Tree;
import build.buildfarm.worker.util.WorkerTestUtils;
import build.buildfarm.worker.util.WorkerTestUtils.TreeFile;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.Input;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import persistent.bazel.client.PersistentWorker;
import persistent.bazel.client.WorkerKey;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public class ProtoCoordinatorTest {

    private WorkerKey makeWorkerKey(WorkFilesContext ctx, WorkerInputs workerFiles, Path workRootsDir) {
        WorkerKey key =
                Keymaker.make(
                        ctx.opRoot,
                        workRootsDir,
                        ImmutableList.of("workerExecCmd"),
                        ImmutableList.of("workerInitArgs"),
                        ImmutableMap.of(),
                        "executionName",
                        workerFiles
                );
        return key;
    }

    private Path rootDir = null;
    public Path jimFsRoot() {
        if (rootDir == null) {
            rootDir = Iterables.getFirst(
                    Jimfs.newFileSystem(
                                    Configuration.unix()
                                            .toBuilder()
                                            .setAttributeViews("basic", "owner", "posix", "unix")
                                            .build())
                            .getRootDirectories(),
                    null);
        }
        return rootDir;
    }

    @Test
    public void testProtoCoordinator() throws Exception {
        ProtoCoordinator pc = ProtoCoordinator.ofCommonsPool(4);

        Path fsRoot = jimFsRoot();
        Path opRoot = fsRoot.resolve("opRoot");
        assert(Files.notExists(opRoot));
        Files.createDirectory(opRoot);

        assert(Files.exists(opRoot));

        String treeRootDir = opRoot.toString();
        List<TreeFile> fileInputs = ImmutableList.of(
                        new TreeFile("file_1", "file contents 1"),
                        new TreeFile("subdir/subdir_file_2", "file contents 2"),
                        new TreeFile("tools_dir/tool_file", "tool file contents", true)
                );

        Tree tree = WorkerTestUtils.makeTree(treeRootDir, fileInputs);

        Command command = WorkerTestUtils.makeCommand();
        WorkFilesContext ctx = WorkFilesContext.fromContext(opRoot, tree, command);
        ImmutableList<String> requestArgs = ImmutableList.of("reqArg1");

        WorkerInputs workerFiles = WorkerInputs.from(ctx, requestArgs);

        for (Map.Entry<Path, Input> entry : workerFiles.allInputs.entrySet()) {
            Path file = entry.getKey();
            Files.createDirectories(file.getParent());
            Files.createFile(file);
            System.out.println("Worker File created: " + file);
        }

        WorkerKey key = makeWorkerKey(ctx, workerFiles, fsRoot.resolve("workRootsDir"));

        // insert assertions about file system state
        Path workRoot = key.getExecRoot();
        Path toolsRoot = workRoot.resolve(PersistentWorker.TOOL_INPUT_SUBDIR);

        pc.copyToolInputsIntoWorkerToolRoot(key, workerFiles);
        // TODO: extra files are being created -- look into it
        //  mfw test fixtures pls
        loldebug(workRoot);

        assert Files.exists(workRoot);
        for (TreeFile file : fileInputs) {
            if (file.isTool) {
                assert Files.exists(toolsRoot.resolve(file.path));
            }
            else {
                assert Files.notExists(workRoot.resolve(file.path));
            }
        }
        // TODO!
        assert false;
    }

    private void loldebug(Path workRoot) throws IOException {
        System.out.println(workRoot);

        Files.walkFileTree(workRoot, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                System.out.println("Found: " + dir + "/");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                System.out.println("Found: " + file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.out.println("visitFileFailed: " + file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
