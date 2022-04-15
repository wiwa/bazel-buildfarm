package persistent.common.processes;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.io.IOUtils;

import static com.google.common.base.Preconditions.checkNotNull;

public class ProcessWrapper implements Closeable {

    private final Process process;
    
    private final Path workRoot;

    private final ImmutableList<String> args;

    private final Path errorFile;

    public ProcessWrapper(Path workDir, ImmutableList<String> args) throws IOException {
        this(workDir, args, new HashMap<>());
    }

    public ProcessWrapper(Path workDir, ImmutableList<String> args, Map<String, String> env) throws IOException {
        this.args = checkNotNull(args);
        this.workRoot = checkNotNull(workDir);
        Preconditions.checkArgument(
            Files.isDirectory(workDir),
            "Process workDir must be a directory, got: " + workDir
        );

        this.errorFile = this.workRoot.resolve(UUID.randomUUID() + ".stderr");

        System.out.println("Starting Process:");
        System.out.println("\tcmd: " + this.args);
        System.out.println("\tdir: " + this.workRoot);
        System.out.println("\tenv: " + ImmutableMap.copyOf(env));
        System.out.println("\tenv: " + errorFile);
        
        ProcessBuilder pb = new ProcessBuilder()
                .command(this.args)
                .directory(this.workRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.to(this.errorFile.toFile()));

        pb.environment().putAll(env);

        this.process = pb.start();

        if (!this.process.isAlive()) {
            int exitVal = this.process.exitValue();
            String msg = "Process instantly terminated with: " + exitVal + "; " +
                IOUtils.toString(getStdErr(), StandardCharsets.UTF_8);
            throw new IOException(msg);
        }
    }
    
    public ImmutableList<String> getInitialArgs() {
        return this.args;
    }
    
    public Path getWorkRoot() {
        return this.workRoot;
    }
    
    public OutputStream getStdIn() {
        return this.process.getOutputStream();
    }

    public InputStream getStdOut() {
        return this.process.getInputStream();
    }

    public InputStream getStdErr() throws IOException {
        return IOUtils.toBufferedInputStream(Files.newInputStream(this.errorFile));
    }

    public String getErrorString() throws IOException {
        if (Files.exists(this.errorFile)) {
            return new String(Files.readAllBytes(this.errorFile));
        } else {
            return "";
        }
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    public int exitValue() {
        return this.process.exitValue();
    }

    public int waitFor() throws InterruptedException {
        return this.process.waitFor();
    }

    public void destroy() {
        this.process.destroyForcibly();
    }

    @Override
    public void close() throws IOException {
        this.destroy();
    }
}
