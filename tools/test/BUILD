load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
load("@rules_cc//cc:cc_test.bzl", "cc_test")

package(default_visibility = ["//visibility:public"])

# Members of this filegroup shouldn't have duplicate basenames, otherwise
# TestRunnerAction#getRuntimeArtifact() will get confused.
# Deprecated, do not use.
filegroup(
    name = "runtime",
    srcs = ["test-setup.sh"],
)

filegroup(
    name = "test_setup",
    srcs = ["test-setup.sh"],
)

filegroup(
    name = "test_xml_generator",
    srcs = ["generate-xml.sh"],
)

filegroup(
    name = "collect_coverage",
    srcs = ["collect_coverage.sh"],
)

filegroup(
    name = "coverage_support",
    srcs = ["collect_coverage.sh"],
)

filegroup(
    name = "collect_cc_coverage",
    srcs = ["collect_cc_coverage.sh"],
)

# Native test wrapper for Windows, Linux, and macOS.
cc_binary(
    name = "tw",
    srcs = select({
        "@bazel_tools//src/conditions:windows": ["windows/tw_main.cc"],
        "@bazel_tools//src/conditions:linux": ["posix/tw_main.cc"],
        "@bazel_tools//src/conditions:darwin": ["posix/tw_main.cc"],
        "//conditions:default": ["empty_test_wrapper.cc"],
    }),
    visibility = ["//visibility:private"],
    deps = [":tw_lib"],
)

cc_binary(
    name = "xml",
    srcs = ["windows/xml_main.cc"],
    target_compatible_with = ["@platforms//os:windows"],
    visibility = ["//visibility:private"],
    deps = [":tw_lib"],
)

cc_library(
    name = "tw_lib",
    srcs = ["test_wrapper_common.cc"] + select({
        "@bazel_tools//src/conditions:windows": ["windows/tw.cc"],
        "@bazel_tools//src/conditions:linux": [
            "posix/tw.cc",
            "posix/tw_outputs.cc",
        ],
        "@bazel_tools//src/conditions:darwin": [
            "posix/tw.cc",
            "posix/tw_outputs.cc",
        ],
        "//conditions:default": [],
    }),
    hdrs = ["test_wrapper_common.h"] + select({
        "@bazel_tools//src/conditions:windows": ["windows/tw.h"],
        "@bazel_tools//src/conditions:linux": ["posix/tw_outputs.h"],
        "@bazel_tools//src/conditions:darwin": ["posix/tw_outputs.h"],
        "//conditions:default": [],
    }),
    linkopts = select({
        "//src/conditions:windows": [
            "-DEFAULTLIB:advapi32.lib",  # GetUserNameW
        ],
        "//conditions:default": [],
    }),
    visibility = ["//visibility:private"],
    deps = [
        "//src/main/cpp/util:filesystem",
        "//third_party/ijar:zip",
    ] + select({
        "@bazel_tools//src/conditions:windows": [
            "//src/main/cpp/util:strings",
            "//src/main/native/windows:lib-file",
            "//src/main/native/windows:lib-process",
            "@rules_cc//cc/runfiles",
        ],
        "//conditions:default": [],
    }),
)

cc_test(
    name = "tw_test",
    srcs = select({
        "@bazel_tools//src/conditions:windows": ["windows/tw_test.cc"],
        "@bazel_tools//src/conditions:linux": ["posix/tw_test.cc"],
        "@bazel_tools//src/conditions:darwin": ["posix/tw_test.cc"],
        "//conditions:default": ["empty_test.cc"],
    }),
    deps = [
        ":tw_lib",
        "//src/main/cpp/util:filesystem",
        "//third_party/ijar:zip",
        "@com_google_googletest//:gtest_main",
    ] + select({
        "@bazel_tools//src/conditions:windows": [
            "//src/main/native/windows:lib-file",
            "//src/test/cpp/util:windows_test_util",
        ],
        "//conditions:default": [],
    }),
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD.tools",
        "collect_cc_coverage.sh",
        "collect_coverage.sh",
        "default_test_toolchain.bzl",
        "dummy.sh",
        "extensions.bzl",
        "generate-xml.sh",
        "posix/tw.cc",
        "posix/tw_main.cc",
        "posix/tw_outputs.cc",
        "posix/tw_outputs.h",
        "test-setup.sh",
        "test_wrapper_common.cc",
        "test_wrapper_common.h",
        "windows/tw.cc",
        "windows/tw.h",
        "windows/tw_main.cc",
        "windows/xml_main.cc",
    ] + select({
        "@bazel_tools//src/conditions:windows": [
            "tw",
            "xml",
        ],
        "@bazel_tools//src/conditions:linux": ["tw"],
        "@bazel_tools//src/conditions:darwin": ["tw"],
        "//conditions:default": [],
    }),
    visibility = ["//tools:__pkg__"],
)

filegroup(
    name = "bzl_srcs",
    srcs = [
        "default_test_toolchain.bzl",
        "extensions.bzl",
    ],
)
