package build.buildfarm.worker.persistent;

import java.nio.file.Path;
import java.util.List;

import com.google.common.collect.ImmutableSet;

public class InputsExtractor {

  static boolean isToolInput(Path absPath) {
    String pathStr = absPath.toString();
    return pathStr.contains("external/remotejdk11_linux/") ||
        pathStr.contains("external/remote_java_tools/") ||
        pathStr.endsWith("/external/bazel_tools/tools/jdk/platformclasspath.jar") ||
        pathStr.endsWith("/scalac.jar") ||
        pathStr.endsWith("_deploy.jar") ||
        pathStr.endsWith("scalac/scalac") ||
        pathStr.contains(
            "external/io_bazel_rules_scala/src/java/io/bazel/rulesscala/scalac/scalac.runfiles"
        );
  }

  static ImmutableSet<Path> getToolFiles(List<Path> files) {
    return ImmutableSet.copyOf(
        files
            .stream()
            .filter(InputsExtractor::isToolInput)
            .iterator()
    );
  }
}
