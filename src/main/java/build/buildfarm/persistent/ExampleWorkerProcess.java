package build.buildfarm.persistent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import com.google.devtools.build.lib.worker.WorkerProtocol;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;

public class ExampleWorkerProcess {

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--persistent_worker")) {
      jbMain(args);
    } else {
      throw new Exception("was not run with --persistent_worker!");
    }

  }

  static void jbMain(String[] args) {
    WorkRequestHandler workerHandler = new WorkRequestHandler(ExampleWorkerProcess::work);
    int exitCode = workerHandler.processRequests(System.in, System.out, System.err);
    System.exit(exitCode);
  }

  static void logToTmp(String s) {
    try {
      Files.write(Paths.get("/tmp/asdf.txt"), (s + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }

  static class WorkRequestHandler {

    private final BiFunction<List<String>, PrintWriter, Integer> callback;

    /**
     * Creates a {@code WorkRequestHandler} that will call {@code callback} for each WorkRequest
     * received. The first argument to {@code callback} is the set of command-line arguments, the
     * second is where all error messages and similar should be written to.
     */
    public WorkRequestHandler(BiFunction<List<String>, PrintWriter, Integer> callback) {
      this.callback = callback;
    }

    /**
     * Runs an infinite loop of reading {@code WorkRequest} from {@code in}, running the callback,
     * then writing the corresponding {@code WorkResponse} to {@code out}. If there is an error
     * reading or writing the requests or responses, it writes an error message on {@code err} and
     * returns. If {@code in} reaches EOF, it also returns.
     *
     * @return 0 if we reached EOF, 1 if there was an error.
     */
    public int processRequests(InputStream in, PrintStream out, PrintStream err) {
      while (true) {
        try {
          WorkRequest request = WorkRequest.parseDelimitedFrom(in);

          logToTmp("got request");
          if (request == null) {
            logToTmp("request == null");
            break;
          }
          respondToRequest(request, out);
        } catch (IOException e) {
          e.printStackTrace(err);
          return 1;
        }
      }
      return 0;
    }

    void respondToRequest(WorkRequest request, PrintStream out) throws IOException {
      logToTmp("respondToRequest");
      logToTmp(String.join(",", request.getArgumentsList()));
      try (StringWriter sw = new StringWriter();
           PrintWriter pw = new PrintWriter(sw)) {
        int exitCode;
        try {
          logToTmp("callback.apply");
          exitCode = callback.apply(request.getArgumentsList(), pw);
        } catch (RuntimeException e) {
          logToTmp("RuntimeException");
          e.printStackTrace(pw);
          exitCode = 1;
        }
        logToTmp("pw.flush()");
        pw.flush();
        logToTmp("WorkResponse");
        WorkerProtocol.WorkResponse.Builder builder =
            WorkerProtocol.WorkResponse.newBuilder();
        String output = builder.getOutput() + sw;
        WorkerProtocol.WorkResponse workResponse = builder
            .setOutput(output)
            .setExitCode(exitCode)
            .setRequestId(request.getRequestId())
            .build();
        logToTmp("workResponse.writeDelimitedTo");
        logToTmp("out: " + output);
        logToTmp("ec: " + exitCode);
        logToTmp("rid: " + request.getRequestId());
        synchronized (this) {
          try {
            workResponse.writeDelimitedTo(out);
          } finally {
            out.flush();
          }
        }
      }

      // Hint to the system that now would be a good time to run a gc.  After a compile
      // completes lots of objects should be available for collection and it should be cheap to
      // collect them.
      System.gc();
    }
  }

  private static int work(List<String> args, PrintWriter out) {
    Process p;
    int r = 1;
    try {
      out.println("working with args: " + String.join(", ", args));
      p = new ProcessBuilder(args).start();

      BufferedReader pout = new BufferedReader(new InputStreamReader(p.getInputStream()));
      pout.lines().forEach(s -> {
        logToTmp("subprocess line: " + s);
        out.println(s);
      });

      r = p.waitFor();
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
    return r;
  }


  private static void persistentWorkerMain() {

    System.setIn(new ByteArrayInputStream(new byte[0]));

    try {
      while (true) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();

        try {
          WorkRequest request = WorkRequest.parseDelimitedFrom(System.in);
          if (request == null) {
            System.out.println("request was null?!");
            break;
          }

          int code = 0;

          try {
            work(request.getArgumentsList(), new PrintWriter(System.out, true));
          } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
            code = 1;
          }

          WorkerProtocol.WorkResponse.newBuilder()
              .setExitCode(code)
              .setOutput(outStream.toString())
              .build()
              .writeDelimitedTo(System.out);

        } catch (IOException e) {
          // for now we swallow IOExceptions when
          // reading/writing proto
        } finally {
          System.gc();
        }
      }
    } finally {
      System.out.println("bad");
    }
  }

//  /** The single pass runner for ephemeral (non-persistent) worker processes */
//  private static void ephemeralWorkerMain(String workerArgs[])
//      throws Exception {
//    String[] args;
//    if (workerArgs.length == 1 && workerArgs[0].startsWith("@")) {
//      args =
//          stringListToArray(
//              Files.readAllLines(Paths.get(workerArgs[0].substring(1)), StandardCharsets.UTF_8));
//    } else {
//      args = workerArgs;
//    }
//    work(args, new PrintWriter(System.out, true));
//  }


}
