package persistent.common.processes;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;

import static com.google.common.base.Preconditions.checkNotNull;

public class ProcessWrapper implements Closeable {

    private final Process process;
    
    private final Path workRoot;

    private final ImmutableList<String> args;
    
    public ProcessWrapper(Path workDir, ImmutableList<String> args) throws IOException {
        this.args = checkNotNull(args);
        this.workRoot = checkNotNull(workDir);
        Preconditions.checkArgument(
            Files.isDirectory(workDir),
            "Process workDir must be a directory, got: " + workDir
        );
        
        ProcessBuilder pb = new ProcessBuilder()
                .command(this.args)
                .directory(this.workRoot.toFile());

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

    public InputStream getStdErr() {
        return this.process.getErrorStream();
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    public int exitCode() {
        return this.process.exitValue();
    }

    public int waitFor() throws InterruptedException {
        return this.process.waitFor();
    }

    @Override
    public void close() throws IOException {
        this.process.destroyForcibly();
    }
}
