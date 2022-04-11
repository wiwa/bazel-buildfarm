package persistent.bazel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import com.google.protobuf.GeneratedMessageV3;

import persistent.ProcessWrapper;

/**
 * Based off Google's ProtoWorkerProtocol
 * Slightly generified to encapsulate read/writes
 * Should be used by both the PersistentWorker (client-side)
 *  and the WorkRequestHandler (persistent-process-side)
 */
public class ProtoWorkerRW {

  public final ProcessWrapper processWrapper;

  private final InputStream readBufferStream;

  public ProtoWorkerRW(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
    this.readBufferStream = processWrapper.getStdOut();
  }

  public void write(WorkRequest req) throws IOException {
    writeTo(req, this.processWrapper.getStdIn());
  }

  public WorkResponse waitAndRead() throws IOException, InterruptedException {
    waitForInput(processWrapper::isAlive, readBufferStream);
    return readResponse(readBufferStream);
  }

  public static <R extends GeneratedMessageV3> void writeTo(R req, OutputStream outputStream) throws IOException {
    try {
      req.writeDelimitedTo(outputStream);
    } finally {
      outputStream.flush();
    }
  }

  public static WorkResponse readResponse(InputStream inputStream) throws IOException {
    return WorkResponse.parseDelimitedFrom(inputStream);
  }

  public static WorkRequest readRequest(InputStream inputStream) throws IOException {
    return WorkRequest.parseDelimitedFrom(inputStream);
  }

  public static void waitForInput(Supplier<Boolean> liveCheck, InputStream inputStream) throws IOException, InterruptedException {
    String workerDeathMsg = "Worker process for died while waiting for response";
    while (inputAvailable(inputStream, workerDeathMsg) == 0) {
      Thread.sleep(10);
      if (!liveCheck.get()) {
        throw new IOException(workerDeathMsg);
      }
    }
  }

  private static int inputAvailable(InputStream inputStream, String errorMsg) throws IOException {
    try {
      return inputStream.available();
    } catch (IOException e) {
      throw new IOException(errorMsg, e);
    }
  }
}
