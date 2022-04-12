java_test(
  name = "tests",
  size = "small",
  srcs = glob(["**/*.java"]),
  resources = [
    "//persistentworkers/examples/src/main/java:adder-bin_deploy.jar"
  ],
  deps = [
    "//persistentworkers/src/main/java/persistent/common:persistent-common",
    "//persistentworkers/src/main/java/persistent/bazel:bazel-persistent-workers",
    "//persistentworkers/src/test/java/persistent/testutil:testutil",
    "//third_party/bazel/src/main/protobuf:worker_protocol_java_proto",
    "@maven//:com_google_truth_truth",
    "@maven//:commons_io_commons_io",
    "@maven//:org_mockito_mockito_core",
  ],
)
