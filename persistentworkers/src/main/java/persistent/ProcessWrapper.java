package persistent;

import com.google.common.collect.ImmutableList;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

public class ProcessWrapper implements Closeable {
    
    private final ImmutableList<String> args;
    
    private final Process process;
    
    private final Path workDir;
    
    private final Path stdErrFile;
    
    public ProcessWrapper(Path workDir, Path stdErrFile, ImmutableList<String> args) throws IOException {
        this.args = args;
        this.workDir = workDir;
        this.stdErrFile = stdErrFile;
        
        ProcessBuilder pb = new ProcessBuilder()
                .command(this.args)
                .directory(this.workDir.toFile())
                .redirectError(stdErrFile.toFile());
        
        this.process = pb.start();
    }
    
    public ImmutableList<String> getInitialArgs() {
        return this.args;
    }
    
    public Path getWorkDir() {
        return this.workDir;
    }
    
    public OutputStream getStdIn() {
        return this.process.getOutputStream();
    }

    public InputStream getStdOut() {
        return this.process.getInputStream();
    }
    
    public Path getStdErrPath() {
        return this.stdErrFile;
    }

    @Override
    public void close() throws IOException {
        this.process.destroyForcibly();
    }
}
