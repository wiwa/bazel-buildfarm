load("@rules_proto//proto:defs.bzl", "proto_library")

FILES = [
    "worker_protocol",
]

[proto_library(
    name = s + "_proto",
    srcs = [s + ".proto"],
) for s in FILES]

[java_proto_library(
    name = s + "_java_proto",
    deps = [":" + s + "_proto"],
) for s in FILES]

[java_library_srcs(
    name = s + "_java_proto_srcs",
    deps = [":" + s + "_java_proto"],
) for s in FILES]