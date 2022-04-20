package build.buildfarm.worker;

import com.google.protobuf.Duration;
import com.google.rpc.Code;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

import static build.buildfarm.worker.Executor.INCOMPLETE_EXIT_CODE;

import static java.lang.String.format;

public class ExecutionUtils {

  private static final Logger logger = Logger.getLogger(ExecutionUtils.class.getName());

  public static class ProcessExit {

    public final Process process;

    public final int exitCode;

    public final Code statusCode;

    public final String stderr;

    public ProcessExit(Process process, int exitCode, Code statusCode, String stderr) {
      this.process = process;
      this.exitCode = exitCode;
      this.statusCode = statusCode;
      this.stderr = stderr;
    }

    public ProcessExit(Process process, int exitCode, Code statusCode) {
      this(process, exitCode, statusCode, "");
    }
  }

  public static ProcessExit startSubprocess(ProcessBuilder processBuilder, String operationName, Object execLock) {
    Process process = null;
    int exitCode = 0;
    try {
      synchronized (execLock) {
        process = processBuilder.start();
      }
      process.getOutputStream().close();
    } catch (IOException e) {
      logger.log(Level.SEVERE, format("error starting process for %s", operationName), e);
      // again, should we do something else here??
      exitCode = INCOMPLETE_EXIT_CODE;
      // The openjdk IOException for an exec failure here includes the working
      // directory of the execution. Drop it and reconstruct without it if we
      // can get the cause.
      Throwable t = e.getCause();
      String message;
      if (t != null) {
        message =
            "Cannot run program \"" + processBuilder.command().get(0) + "\": " + t.getMessage();
      } else {
        message = e.getMessage();
      }
      return new ProcessExit(process, exitCode, Code.INVALID_ARGUMENT, message);
    }
    return new ProcessExit(process, exitCode, Code.OK);
  }

  public static ProcessExit waitForProcess(Process process, String operationName, long startNanoTime, Duration timeout) throws InterruptedException {  
    boolean processCompleted = false;
    int exitCode = INCOMPLETE_EXIT_CODE;
    try {
      if (timeout == null) {
        exitCode = process.waitFor();
        processCompleted = true;
      } else {
        long timeoutNanos = timeout.getSeconds() * 1000000000L + timeout.getNanos();
        long remainingNanoTime = timeoutNanos - (System.nanoTime() - startNanoTime);
        if (process.waitFor(remainingNanoTime, TimeUnit.NANOSECONDS)) {
          exitCode = process.exitValue();
          processCompleted = true;
        } else {
          logger.log(
              Level.INFO,
              format("process timed out for %s after %ds", operationName, timeout.getSeconds()));
          return new ProcessExit(process, exitCode, Code.DEADLINE_EXCEEDED);
        }
      }
      return new ProcessExit(process, exitCode, Code.OK);
    } finally {
      if (!processCompleted) {
        process.destroy();
        int waitMillis = 1000;
        while (!process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
          logger.log(
              Level.INFO,
              format("process did not respond to termination for %s, killing it", operationName));
          process.destroyForcibly();
          waitMillis = 100;
        }
      }
    }
  }
}