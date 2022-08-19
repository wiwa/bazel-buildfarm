package build.buildfarm.backplane.ops;

import java.io.IOException;
import java.util.Map;

import build.bazel.remote.execution.v2.ActionResult;
import build.buildfarm.common.DigestUtil;

public interface BackplaneActions {

  final class ActionCacheScanResult {
    public final String token;
    public final Iterable<Map.Entry<DigestUtil.ActionKey, ActionResult>> entries;

    public ActionCacheScanResult(
        String token, Iterable<Map.Entry<DigestUtil.ActionKey, ActionResult>> entries) {
      this.token = token;
      this.entries = entries;
    }
  }


  /**
   * The AC stores full ActionResult objects in a hash map where the key is the digest of the action
   * result and the value is the actual ActionResult object.
   *
   * <p>Retrieves and returns an action result from the hash map.
   */
  ActionResult getActionResult(DigestUtil.ActionKey actionKey) throws IOException;

  /**
   * The AC stores full ActionResult objects in a hash map where the key is the digest of the action
   * result and the value is the actual ActionResult object.
   *
   * <p>Remove an action result from the hash map.
   */
  void removeActionResult(DigestUtil.ActionKey actionKey) throws IOException;

  /** Bulk remove action results */
  void removeActionResults(Iterable<DigestUtil.ActionKey> actionKeys) throws IOException;

  /**
   * Identify an action that should not be executed, and respond to all requests it matches with
   * failover-compatible responses.
   */
  void blacklistAction(String actionId) throws IOException;

  /**
   * The AC stores full ActionResult objects in a hash map where the key is the digest of the action
   * result and the value is the actual ActionResult object.
   *
   * <p>Stores an action result in the hash map.
   */
  void putActionResult(DigestUtil.ActionKey actionKey, ActionResult actionResult) throws IOException;

  /** Page through action cache */
  ActionCacheScanResult scanActionCache(String scanToken, int count) throws IOException;
}
