Testing Persistent Workers (building Buildfarm server with it):

1. Clone Bazel (branch `release-6.4.0`, commit `48892ae`) and apply the following patch [patch](https://github.com/wiwa/bazel/commit/87430e3de1d56886ba7911a0f85087a497b8a1b8):  
```diff
diff --git a/src/main/java/com/google/devtools/build/lib/remote/RemoteExecutionService.java b/src/main/java/com/google/devtools/build/lib/remote/RemoteExecutionService.java
index 506b6da393..ee95bb6bba 100644
--- a/src/main/java/com/google/devtools/build/lib/remote/RemoteExecutionService.java
+++ b/src/main/java/com/google/devtools/build/lib/remote/RemoteExecutionService.java
@@ -527,7 +527,13 @@ public class RemoteExecutionService {
       if (toolSignature != null) {
         platform =
             PlatformUtils.getPlatformProto(
-                spawn, remoteOptions, ImmutableMap.of("persistentWorkerKey", toolSignature.key));
+                spawn,
+                remoteOptions,
+                ImmutableMap.of(
+                    "persistentWorkerKey", toolSignature.key,
+                    "persistentWorkerCommand", toolSignature.getCmdStr()
+                )
+            );
       } else {
         platform = PlatformUtils.getPlatformProto(spawn, remoteOptions);
       }
@@ -586,7 +592,7 @@ public class RemoteExecutionService {
     fingerprint.addIterableStrings(workerKey.getArgs());
     fingerprint.addStringMap(workerKey.getEnv());
     return new ToolSignature(
-        fingerprint.hexDigestAndReset(), workerKey.getWorkerFilesWithDigests().keySet());
+        fingerprint.hexDigestAndReset(), workerKey.getWorkerFilesWithDigests().keySet(), workerKey.getArgs());
   }

   /** A value class representing the result of remotely executed {@link RemoteAction}. */
@@ -1593,10 +1599,22 @@ public class RemoteExecutionService {
   private static final class ToolSignature {
     private final String key;
     private final Set<PathFragment> toolInputs;
+    private final List<String> command;

-    private ToolSignature(String key, Set<PathFragment> toolInputs) {
+    private ToolSignature(String key, Set<PathFragment> toolInputs, List<String> command) {
       this.key = key;
       this.toolInputs = toolInputs;
+      this.command = command;
+    }
+
+    public String getCmdStr() {
+        StringBuilder cmdStr = new StringBuilder();
+        for (String c : command) {
+            cmdStr.append(c);
+            cmdStr.append(" ");
+        }
+        cmdStr.deleteCharAt(cmdStr.length()-1);
+        return cmdStr.toString();
     }
   }
 }
```
2. Build the custom `bazel` binary and copy it to Buildfarm repo (also renaming it to avoid conflicts):  
```sh
bazel build //src:bazel
cp bazel-bin/src/bazel <path-to-buildfarm-repo>/bazel-640pw
cd <path-to-buildfarm-repo>
```
3. Modify `examples/config.yml` to increase `execute_stage_width` (and `inputFetchStageWidth`(?)) to `<number of cores>`  
4. Run the following commands in separate terminals or in the background (may need 2 Buildfarm repos or perhaps running the `-deploy.jar` versions of server/worker):  
```sh
# (terminal#0/buildfarm@main)
./run_server $(pwd)/examples/config.yml

# (terminal#1/buildfarm@main)
# Can also use env variable BUILDFARM_MAX_WORKERS_PER_KEY=<num>; default is 6
./run_worker $(pwd)/examples/config.yml

# (terminal#2/buildfarm-repo-for-building)
./bazel-640pw build --remote_executor=grpc://localhost:8980 --experimental_remote_mark_tool_inputs src/main/java/build/buildfarm:buildfarm-server_deploy.jar
```
