java_library(
  name = "adder-lib",
  srcs = glob(["**/*.java"]),
  visibility = ["//visibility:public"],
)

java_binary(
  name = "adder-bin",
  main_class = "adder.Adder",
  runtime_deps = [":adder-lib"]
)
