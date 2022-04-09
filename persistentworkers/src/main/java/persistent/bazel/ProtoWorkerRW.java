package persistent.bazel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;

import persistent.ProcessWrapper;

public class ProtoWorkerRW {

  private final ProcessWrapper processWrapper;

  private InputStream readBufferStream;

  public ProtoWorkerRW(ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
    this.readBufferStream = processWrapper.getStdOut();
  }

  public void write(WorkRequest req) throws IOException {
    OutputStream workerIn = this.processWrapper.getStdIn();
    req.writeDelimitedTo(workerIn);
    workerIn.flush();
  }

  public WorkResponse read() throws IOException {
    return WorkResponse.parseDelimitedFrom(this.processWrapper.getStdOut());
  }

  public WorkResponse waitAndRead() throws IOException, InterruptedException {
  //    while (readBufferStream.available() == 0) {
  //      Thread.sleep(10);
  //      if (!processWrapper.isAlive()) {
  //        throw new IOException("Worker process for died while waiting for response");
  //      }
  //    }
    return read();
  }
}
