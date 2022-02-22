
java_binary(
    name = "pworker",
    main_class = "build.buildfarm.persistent.PersistentSpawnRunner",
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//src/main/java/build/buildfarm/persistent:persistent-worker",
    ],
)

java_binary(
    name = "example-worker",
    main_class = "build.buildfarm.persistent.ExampleWorkerProcess",
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//src/main/java/build/buildfarm/persistent:persistent-worker",
    ],
)

java_library(
    name = "persistent-worker",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//src/main/java/build/buildfarm/common",
        "//src/main/java/build/buildfarm/common/redis",
        "//src/main/java/build/buildfarm/instance",
        "//src/main/java/build/buildfarm/instance/shard",
        "//src/main/java/build/buildfarm/instance/stub",
        "//src/main/java/build/buildfarm/worker/shard",
        "//src/main/protobuf:build_buildfarm_v1test_buildfarm_java_proto",
        "//third_party/bazel/src/main/protobuf:worker_protocol_java_proto",
        "@maven//:org_apache_commons_commons_pool2",
        "@maven//:com_github_pcj_google_options",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:com_google_protobuf_protobuf_java_util",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_context",
        "@maven//:io_grpc_grpc_core",
        "@maven//:io_grpc_grpc_netty",
        "@maven//:io_grpc_grpc_protobuf",
        "@maven//:io_grpc_grpc_stub",
    ],
)
