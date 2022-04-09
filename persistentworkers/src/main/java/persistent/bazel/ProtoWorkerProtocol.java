package persistent.bazel;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

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

  public void putRequest(WorkRequest request) throws IOException {
    request.writeDelimitedTo(workersStdin);
    workersStdin.flush();
  }

  public WorkResponse getResponse() throws IOException {
    return WorkResponse.parseDelimitedFrom(workersStdout);
  }

  @Override
  public void close() {
  }
}
