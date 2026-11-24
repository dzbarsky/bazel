load("@rules_java//java:defs.bzl", "java_library", "java_plugin")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src/main/java/com/google/devtools/build/lib:build_lib"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/com/google/devtools/build/lib/util:__pkg__"],
)

java_library(
    name = "regex_util",
    srcs = ["RegexUtil.java"],
    deps = [
        ":reggie_pattern",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
    ],
)

java_library(
    name = "reggie_pattern",
    srcs = ["ReggiePattern.java"],
    deps = [
        "//third_party/java/jsr305_annotations",
        "@maven//:com_datadoghq_reggie",
    ],
)

java_plugin(
    name = "reggie_processor",
    processor_class = "com.datadoghq.reggie.processor.RegexPatternProcessor",
    deps = ["@maven//:com_datadoghq_reggie"],
)

java_library(
    name = "bazel_patterns",
    srcs = ["BazelPatterns.java"],
    plugins = [":reggie_processor"],
    deps = ["@maven//:com_datadoghq_reggie"],
)
