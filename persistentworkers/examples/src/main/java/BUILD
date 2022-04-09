java_library(
  name = "adder-lib",
  srcs = glob(["**/*.java"]),
  visibility = ["//visibility:public"],
  deps = [
    "//persistentworkers/src/main/java:persistent-workers",
    "//persistentworkers/src/main/java/persistent/bazel:bazel-persistent-workers",
  ]
)

java_binary(
  name = "adder-bin",
  main_class = "adder.Adder",
  runtime_deps = [":adder-lib"],
  visibility = ["//visibility:public"],
)
