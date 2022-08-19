package build.buildfarm.backplane.ops;

import java.io.IOException;
import java.util.List;

import build.bazel.remote.execution.v2.Platform;
import build.buildfarm.common.function.InterruptingRunnable;
import build.buildfarm.v1test.BackplaneStatus;
import build.buildfarm.v1test.GetClientStartTimeRequest;
import build.buildfarm.v1test.GetClientStartTimeResult;

public interface BackplaneState {

  /**
   * Register a runnable for when the backplane cannot guarantee watch deliveries. This runnable may
   * throw InterruptedException
   *
   * <p>onSubscribe is called via the subscription thread, and is not called when operations are not
   * listened to
   */
  void setOnUnsubscribe(InterruptingRunnable onUnsubscribe);

  /**
   * Start the backplane's operation
   */
  void start(String publicClientName) throws IOException;

  /**
   * Stop the backplane's operation
   */
  void stop() throws InterruptedException;

  /**
   * Indicates whether the backplane has been stopped
   */
  boolean isStopped();

  BackplaneStatus backplaneStatus() throws IOException;

  GetClientStartTimeResult getClientStartTime(GetClientStartTimeRequest request) throws IOException;
}
