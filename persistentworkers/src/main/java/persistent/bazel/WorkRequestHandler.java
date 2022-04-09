package persistent.bazel;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.function.BiFunction;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import org.jetbrains.annotations.NotNull;

/**
 * Persistence-compatible tools should instantiate this class
 */
public class WorkRequestHandler {

  private final BiFunction<List<String>, PrintWriter, Integer> callback;

  public WorkRequestHandler(@NotNull BiFunction<List<String>, PrintWriter, Integer> callback) {
    this.callback = callback;
  }

  public void writeToStream(@NotNull WorkResponse workResponse, @NotNull PrintStream out) throws IOException {
    synchronized (this) {
      try {
        workResponse.writeDelimitedTo(out);
      } finally {
        out.flush();
      }
    }
  }

  public WorkResponse handleRequest(@NotNull WorkRequest request) throws IOException {
    try (StringWriter sw = new StringWriter();
         PrintWriter pw = new PrintWriter(sw)) {

      int exitCode;
      try {
        exitCode = callback.apply(request.getArgumentsList(), pw);
      } catch (RuntimeException e) {
        e.printStackTrace(pw);
        exitCode = 1;
      }

      pw.flush();
      String output = sw.toString();

      return WorkResponse
          .newBuilder()
          .setOutput(output)
          .setExitCode(exitCode)
          .setRequestId(request.getRequestId())
          .build();
    }
  }

  public int processForever(InputStream in, PrintStream out, PrintStream err) {
    while (true) {
      try {
        WorkRequest request = WorkRequest.parseDelimitedFrom(in);

        if (request == null) {
          break;
        } else {
          WorkResponse response = handleRequest(request);
          writeToStream(response, out);
        }
      } catch (IOException e) {
        e.printStackTrace(err);
        return 1;
      }
    }
    return 0;
  }
}
