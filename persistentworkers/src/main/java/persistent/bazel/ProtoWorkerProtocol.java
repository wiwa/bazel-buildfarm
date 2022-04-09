package persistent.bazel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.google.devtools.build.lib.worker.WorkerProtocol;

public class ProtoWorkerProtocol implements Closeable {

  /**
   * The worker process's stdin
   */
  private final OutputStream workersStdin;

  /**
   * The worker process's stdout.
   */
  private final InputStream workersStdout;

  public ProtoWorkerProtocol(OutputStream workersStdin, InputStream workersStdout) {
    this.workersStdin = workersStdin;
    this.workersStdout = workersStdout;
  }

  public void putRequest(WorkerProtocol.WorkRequest request) throws IOException {
    request.writeDelimitedTo(workersStdin);
    workersStdin.flush();
  }

  public WorkerProtocol.WorkResponse getResponse() throws IOException {
    return WorkerProtocol.WorkResponse.parseDelimitedFrom(workersStdout);
  }

  @Override
  public void close() {
  }
}
