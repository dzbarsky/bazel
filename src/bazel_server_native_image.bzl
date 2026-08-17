"""Rules for building Bazel's native-image server."""

load("@apple_support//lib:apple_support.bzl", "apple_support")
load("@bazel_tools//tools/build_defs/cc:action_names.bzl", "CPP_LINK_EXECUTABLE_ACTION_NAME", "C_COMPILE_ACTION_NAME")
load("@bazel_tools//tools/cpp:toolchain_utils.bzl", "find_cpp_toolchain")
load("@rules_cc//cc/common:cc_common.bzl", "cc_common")

_MAIN_CLASS = "com.google.devtools.build.lib.bazel.Bazel"

# Bazel's server image needs generated native-image configs and the
# libmanagement_ext.so or libmanagement_ext.dylib output, which the public
# rules_graalvm native_image rule cannot currently declare.
def _resolve_cc_toolchain(ctx):
    cc_toolchain = find_cpp_toolchain(ctx)

    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )
    c_compiler_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = C_COMPILE_ACTION_NAME,
    )
    compile_variables = cc_common.create_compile_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
    )
    compile_env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = C_COMPILE_ACTION_NAME,
        variables = compile_variables,
    )
    compile_requirements = cc_common.get_execution_requirements(
        feature_configuration = feature_configuration,
        action_name = C_COMPILE_ACTION_NAME,
    )
    link_variables = cc_common.create_link_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
    )
    link_env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_EXECUTABLE_ACTION_NAME,
        variables = link_variables,
    )
    link_requirements = cc_common.get_execution_requirements(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_EXECUTABLE_ACTION_NAME,
    )

    env = {}
    env.update(compile_env)
    env.update(link_env)

    path_set = {}
    for tool_path in [c_compiler_path]:
        tool_dir, _, _ = tool_path.rpartition("/")
        if tool_dir:
            path_set[tool_dir] = None
    if "/bin" not in path_set:
        path_set["/bin"] = None
    if "/usr/bin" not in path_set:
        path_set["/usr/bin"] = None
    env["PATH"] = ctx.configuration.host_path_separator.join(sorted(path_set.keys()))

    return struct(
        c_compiler_path = c_compiler_path,
        env = env,
        execution_requirements = compile_requirements + link_requirements,
        files = cc_toolchain.all_files,
    )

def _bazel_server_native_image_impl(ctx):
    if ctx.attr.pgo_instrument and ctx.files.pgo_profiles:
        fail("pgo_instrument and pgo_profiles cannot both be set")

    is_macos = ctx.target_platform_has_constraint(
        ctx.attr._macos_constraint[platform_common.ConstraintValueInfo],
    )
    is_arm64 = ctx.target_platform_has_constraint(
        ctx.attr._arm64_constraint[platform_common.ConstraintValueInfo],
    )
    is_x86_64 = ctx.target_platform_has_constraint(
        ctx.attr._x86_64_constraint[platform_common.ConstraintValueInfo],
    )
    requires_local_execution = is_macos or is_arm64

    hosted_jvm_max_heap = ctx.var.get("BAZEL_NATIVE_IMAGE_MAX_HEAP")
    if hosted_jvm_max_heap != None:
        heap_size_digits = hosted_jvm_max_heap
        if hosted_jvm_max_heap and hosted_jvm_max_heap[-1:].lower() in ["k", "m", "g"]:
            heap_size_digits = hosted_jvm_max_heap[:-1]
        if not heap_size_digits or not heap_size_digits.isdigit() or int(heap_size_digits) < 1:
            fail("BAZEL_NATIVE_IMAGE_MAX_HEAP must be a positive integer optionally followed by k, m, or g")

    compact_object_headers_value = ctx.var.get("BAZEL_NATIVE_IMAGE_COMPACT_OBJECT_HEADERS", "0")
    if compact_object_headers_value not in ["0", "1"]:
        fail("BAZEL_NATIVE_IMAGE_COMPACT_OBJECT_HEADERS must be 0 or 1")
    compact_object_headers = compact_object_headers_value == "1"

    hosted_jvm_new_ratio = ctx.var.get("BAZEL_NATIVE_IMAGE_NEW_RATIO")
    if hosted_jvm_new_ratio != None:
        if not hosted_jvm_new_ratio.isdigit() or int(hosted_jvm_new_ratio) < 1:
            fail("BAZEL_NATIVE_IMAGE_NEW_RATIO must be a positive integer")

    hosted_jvm_gc = ctx.var.get("BAZEL_NATIVE_IMAGE_HOSTED_GC")
    if hosted_jvm_gc != None and hosted_jvm_gc != "g1":
        fail("BAZEL_NATIVE_IMAGE_HOSTED_GC must be g1")

    parallelism = ctx.attr.parallelism
    parallelism_override = ctx.var.get("BAZEL_NATIVE_IMAGE_PARALLELISM")
    if parallelism_override != None:
        if not parallelism_override.isdigit() or int(parallelism_override) < 1:
            fail("BAZEL_NATIVE_IMAGE_PARALLELISM must be a positive integer")
        parallelism = int(parallelism_override)

    profiling_package_prefixes = None
    disable_pgo_sampling = False
    disable_pgo_priority_inlining = False
    if ctx.attr.pgo_instrument:
        profiling_package_prefixes = ctx.var.get("BAZEL_NATIVE_IMAGE_PROFILING_PACKAGE_PREFIXES")
        if profiling_package_prefixes != None:
            identifier_start = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$"
            identifier_characters = identifier_start + "0123456789"
            for prefix in profiling_package_prefixes.split(","):
                for component in prefix.split("."):
                    if not component or component[0] not in identifier_start:
                        fail("BAZEL_NATIVE_IMAGE_PROFILING_PACKAGE_PREFIXES must be comma-separated Java package prefixes")
                    for character in component.elems():
                        if character not in identifier_characters:
                            fail("BAZEL_NATIVE_IMAGE_PROFILING_PACKAGE_PREFIXES must be comma-separated Java package prefixes")

        disable_pgo_sampling_value = ctx.var.get("BAZEL_NATIVE_IMAGE_DISABLE_PGO_SAMPLING", "0")
        if disable_pgo_sampling_value not in ["0", "1"]:
            fail("BAZEL_NATIVE_IMAGE_DISABLE_PGO_SAMPLING must be 0 or 1")
        disable_pgo_sampling = disable_pgo_sampling_value == "1"

        disable_pgo_priority_inlining_value = ctx.var.get("BAZEL_NATIVE_IMAGE_DISABLE_PGO_PRIORITY_INLINING", "0")
        if disable_pgo_priority_inlining_value not in ["0", "1"]:
            fail("BAZEL_NATIVE_IMAGE_DISABLE_PGO_PRIORITY_INLINING must be 0 or 1")
        disable_pgo_priority_inlining = disable_pgo_priority_inlining_value == "1"

    classpath = depset([ctx.file.deploy_jar])

    generated_reflection_configs = [
        ctx.actions.declare_file(ctx.attr.name + "/" + basename)
        for basename in [
            "caffeine_reflect_config.json",
            "proto_reflect_config.json",
            "options_reflect_config.json",
            "converter_reflect_config.json",
            "rule_reflect_config.json",
            "starlark_reflect_config.json",
            "bazel_methods_reflect_config.json",
            "bazel_fields_reflect_config.json",
            "gson_reflect_config.json",
            "bzlmod_reflect_config.json",
            "netty_jctools_reflect_config.json",
            "netty_buffer_reflect_config.json",
        ]
    ]
    dynamic_proxy_config = ctx.actions.declare_file(ctx.attr.name + "/dynamic_proxy_config.json")

    config_args = ctx.actions.args()
    config_args.add(ctx.file.deploy_jar)
    config_args.add(dynamic_proxy_config)
    config_args.add_all(generated_reflection_configs)
    ctx.actions.run(
        executable = ctx.executable._config_generator,
        arguments = [config_args],
        inputs = [ctx.file.deploy_jar],
        tools = [ctx.executable._config_generator],
        outputs = [dynamic_proxy_config] + generated_reflection_configs,
        execution_requirements = {"no-remote": ""} if requires_local_execution else {},
        mnemonic = "BazelNativeImageConfig",
        progress_message = "Generating native-image configs %{label}",
    )

    binary = ctx.actions.declare_file(ctx.attr.output_path or ctx.attr.executable_name)
    management_library_name = "libmanagement_ext.dylib" if is_macos else "libmanagement_ext.so"
    jdk_library = ctx.actions.declare_file(ctx.attr.jdk_library_path or management_library_name)
    build_output_json = ctx.actions.declare_file(ctx.attr.name + "/build-output.json")
    bundle = ctx.actions.declare_file(ctx.attr.name + "/native-image.nib")

    graalvm_files = ctx.attr.graalvm_files[DefaultInfo].files
    cc_toolchain = _resolve_cc_toolchain(ctx)

    direct_inputs = [
        ctx.file.jni_configuration,
        ctx.file.reflection_configuration,
        dynamic_proxy_config,
    ] + generated_reflection_configs

    args = ctx.actions.args().use_param_file("@%s", use_always = False)
    if hosted_jvm_max_heap != None:
        args.add("-J-Xmx" + hosted_jvm_max_heap)
    if compact_object_headers:
        args.add("-J-XX:+UseCompactObjectHeaders")
    if hosted_jvm_new_ratio != None:
        args.add("-J-XX:NewRatio=" + hosted_jvm_new_ratio)
    if hosted_jvm_gc == "g1":
        args.add("-J-XX:-UseParallelGC")
        args.add("-J-XX:+UseG1GC")
    args.add("-Dfile.encoding=ISO-8859-1")
    args.add("-Dnative.encoding=UTF-8")
    args.add("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    args.add("--enable-native-access=ALL-UNNAMED")
    args.add("--enable-monitoring=threaddump")
    args.add("-H:+UnlockExperimentalVMOptions")
    args.add("-H:+AddAllCharsets")
    args.add("-H:DefaultCharset=ISO-8859-1")
    if ctx.attr.optimization:
        args.add("-O" + ctx.attr.optimization)
    if ctx.attr.gc:
        args.add("--gc=" + ctx.attr.gc)
    if ctx.attr.pgo_instrument:
        args.add("--pgo-instrument")
        if profiling_package_prefixes != None:
            args.add("-H:ProfilingPackagePrefixes=" + profiling_package_prefixes)
        if disable_pgo_sampling:
            args.add("-H:-SamplingCollect")
        if disable_pgo_priority_inlining:
            args.add("-H:-AOTPriorityInline")
    elif ctx.files.pgo_profiles:
        args.add_joined(ctx.files.pgo_profiles, join_with = ",", format_joined = "--pgo=%s")
    args.add(bundle, format = "--bundle-create=%s,dry-run")
    args.add(build_output_json, format = "-H:BuildOutputJSONFile=%s")
    args.add_joined(
        [ctx.file.reflection_configuration] + generated_reflection_configs,
        join_with = ",",
        format_joined = "-H:ReflectionConfigurationFiles=%s",
    )
    args.add(ctx.file.jni_configuration, format = "-H:JNIConfigurationFiles=%s")
    args.add(dynamic_proxy_config, format = "-H:DynamicProxyConfigurationFiles=%s")
    args.add(ctx.attr.include_resources, format = "-H:IncludeResources=%s")
    if is_x86_64:
        args.add("-march=x86-64-v2")
    args.add(cc_toolchain.c_compiler_path, format = "--native-compiler-path=%s")
    if parallelism > 0:
        args.add(parallelism, format = "--parallelism=%s")
    args.add("-o")
    args.add(binary)
    args.add_joined("-cp", classpath, join_with = ctx.configuration.host_path_separator)
    runtime_initialized_classes = [
        "com.google.devtools.build.lib.jni.JniLoader",
        "com.google.devtools.build.lib.profiler.SystemNetworkStats",
        "com.google.devtools.build.lib.unix.NativePosixFiles",
        "com.google.devtools.build.lib.unix.ProcessUtils",
        "com.google.devtools.build.lib.util.StringEncoding",
        "com.google.devtools.build.lib.vfs.bazel.Blake3MessageDigest",
        "net.starlark.java.eval.CpuProfiler",
        "io.grpc.netty",
        "io.netty.bootstrap",
        "io.netty.buffer",
        "io.netty.channel",
        "io.netty.handler",
        "io.netty.internal.tcnative",
        "io.netty.util.NetUtil",
        "io.netty.util.ResourceLeakDetector",
        "io.netty.util.concurrent.AbstractScheduledEventExecutor",
        "io.netty.util.internal.MacAddressUtil",
        "sun.nio.fs.Util",
    ]
    if is_macos:
        runtime_initialized_classes.extend([
            "com.google.devtools.build.lib.platform.SleepPreventionModule$SleepPrevention",
            "com.google.devtools.build.lib.platform.SystemMemoryPressureMonitor",
            "com.google.devtools.build.lib.platform.SystemSuspensionModule",
            "com.google.devtools.build.lib.skyframe.MacOSXFsEventsDiffAwareness",
        ])
    args.add("--initialize-at-run-time=" + ",".join(runtime_initialized_classes))
    args.add(
        "--initialize-at-build-time=" + ",".join([
            "com.google.devtools.build.lib.bazel.repository.decompressor.CompressedTarFunction$MarkedIso88591Charset",
            "io.netty.util.internal.shaded.org.jctools",
        ]),
    )
    args.add(ctx.attr.main_class)

    utf8_locale = "en_US.UTF-8" if is_macos else "C.UTF-8"
    env = dict(cc_toolchain.env)
    env.update({
        "LANG": utf8_locale,
        "LC_ALL": utf8_locale,
        "LC_CTYPE": utf8_locale,
    })

    execution_requirements = {
        requirement: ""
        for requirement in cc_toolchain.execution_requirements
    }
    if requires_local_execution:
        execution_requirements["no-remote"] = ""

    native_image_inputs = depset(
        direct = direct_inputs,
        transitive = [
            classpath,
            depset(ctx.files.pgo_profiles),
            graalvm_files,
            cc_toolchain.files,
        ],
    )
    native_image_apply_inputs = depset(
        direct = [bundle],
        transitive = [
            graalvm_files,
            cc_toolchain.files,
        ],
    )

    bundle_action = {
        "executable": ctx.executable.native_image_tool,
        "inputs": native_image_inputs,
        "outputs": [bundle],
        "env": env,
        "execution_requirements": execution_requirements,
        "mnemonic": "BazelNativeImageBundle",
        "progress_message": "Creating Bazel native-image bundle %{label}",
        "toolchain": "@bazel_tools//tools/cpp:toolchain_type",
    }
    if is_macos:
        xcode_args = ctx.actions.args()
        for variable, value in sorted(env.items()):
            xcode_args.add("-E{}={}".format(variable, value))
        xcode_args.add(apple_support.path_placeholders.xcode(), format = "-EDEVELOPER_DIR=%s")
        xcode_args.add(apple_support.path_placeholders.sdkroot(), format = "-ESDKROOT=%s")
        apple_support.run(
            actions = ctx.actions,
            apple_fragment = ctx.fragments.apple,
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig],
            xcode_path_resolve_level = apple_support.xcode_path_resolve_level.args,
            arguments = [args, xcode_args],
            **bundle_action
        )
    else:
        ctx.actions.run(
            arguments = [args],
            **bundle_action
        )

    apply_args = ctx.actions.args()
    apply_args.add(ctx.executable.native_image_tool)
    apply_args.add(bundle)
    apply_args.add(ctx.attr.executable_name)
    apply_args.add(binary)
    apply_args.add(jdk_library)
    apply_args.add(build_output_json)
    apply_args.add(management_library_name)
    apply_args.add("1" if is_macos else "0")
    if hosted_jvm_max_heap != None or compact_object_headers or hosted_jvm_new_ratio != None or hosted_jvm_gc != None:
        apply_args.add(hosted_jvm_max_heap or "")
    if compact_object_headers or hosted_jvm_new_ratio != None or hosted_jvm_gc != None:
        apply_args.add("1" if compact_object_headers else "")
    if hosted_jvm_new_ratio != None or hosted_jvm_gc != None:
        apply_args.add(hosted_jvm_new_ratio or "")
    if hosted_jvm_gc != None:
        apply_args.add(hosted_jvm_gc)

    apply_command = """
set -eu

execroot="$PWD"
native_image_tool="$execroot/$1"
bundle="$execroot/$2"
executable_name="$3"
binary="$4"
jdk_library="$5"
build_output_json="$6"
management_library_name="$7"
is_macos="$8"
hosted_jvm_max_heap="${9:-}"
compact_object_headers="${10:-}"
hosted_jvm_new_ratio="${11:-}"
hosted_jvm_gc="${12:-}"
image_output="$execroot/$(dirname "$build_output_json")/native-image.output"

chmod -R u+w "$image_output" 2>/dev/null || true
rm -rf "$image_output"
trap 'rm -rf "$image_output"' EXIT

if [ -n "$hosted_jvm_max_heap" ]; then
  set -- "-J-Xmx$hosted_jvm_max_heap"
else
  set --
fi
if [ "$compact_object_headers" = "1" ]; then
  set -- "$@" "-J-XX:+UseCompactObjectHeaders"
fi
if [ -n "$hosted_jvm_new_ratio" ]; then
  set -- "$@" "-J-XX:NewRatio=$hosted_jvm_new_ratio"
fi
if [ "$hosted_jvm_gc" = "g1" ]; then
  set -- "$@" "-J-XX:-UseParallelGC" "-J-XX:+UseG1GC"
fi

if [ "$is_macos" = "1" ]; then
  "$native_image_tool" "$@" "--bundle-apply=$bundle" \
    "-EDEVELOPER_DIR=$DEVELOPER_DIR" "-ESDKROOT=$SDKROOT" \
    -o "$executable_name"
else
  "$native_image_tool" "$@" "--bundle-apply=$bundle" -o "$executable_name"
fi

image_source="$image_output/default/$executable_name"
if [ ! -f "$image_source" ]; then
  echo "native-image bundle did not produce $image_source" >&2
  find "$image_output" -maxdepth 3 -type f >&2 || true
  exit 1
fi
mv "$image_source" "$binary"
chmod a+x "$binary"

management_source=""
for candidate in "$image_output/default/$management_library_name" "$image_output/other/$management_library_name"; do
  if [ -f "$candidate" ]; then
    management_source="$candidate"
    break
  fi
done
if [ -z "$management_source" ]; then
  echo "native-image bundle did not produce $management_library_name" >&2
  find "$image_output" -maxdepth 3 -type f >&2 || true
  exit 1
fi
mv "$management_source" "$jdk_library"

build_output_source=""
for candidate in "$image_output/other/build-output.json" "$image_output/default/build-output.json"; do
  if [ -f "$candidate" ]; then
    build_output_source="$candidate"
    break
  fi
done
if [ -z "$build_output_source" ]; then
  echo "native-image bundle did not produce build-output.json" >&2
  find "$image_output" -maxdepth 3 -type f >&2 || true
  exit 1
fi
mv "$build_output_source" "$build_output_json"
"""
    apply_action = {
        "command": apply_command,
        "arguments": [apply_args],
        "inputs": native_image_apply_inputs,
        "tools": [ctx.executable.native_image_tool],
        "outputs": [binary, jdk_library, build_output_json],
        "env": env,
        "execution_requirements": execution_requirements,
        "mnemonic": "BazelNativeImage",
        "progress_message": "Building Bazel native-image server %{label}",
        "toolchain": "@bazel_tools//tools/cpp:toolchain_type",
    }
    if is_macos:
        apple_support.run_shell(
            actions = ctx.actions,
            apple_fragment = ctx.fragments.apple,
            xcode_config = ctx.attr._xcode_config[apple_common.XcodeVersionConfig],
            **apply_action
        )
    else:
        ctx.actions.run_shell(**apply_action)

    return [
        DefaultInfo(
            executable = binary,
            files = depset([binary, jdk_library]),
        ),
        OutputGroupInfo(
            native_image_diagnostics = depset([build_output_json]),
            native_image_bundle = depset([bundle]),
            native_image_output = depset([binary, jdk_library, build_output_json]),
        ),
    ]

bazel_server_native_image = rule(
    implementation = _bazel_server_native_image_impl,
    attrs = {
        "deploy_jar": attr.label(
            allow_single_file = [".jar"],
            mandatory = True,
        ),
        "executable_name": attr.string(
            default = "A-server-native",
        ),
        "graalvm_files": attr.label(
            mandatory = True,
        ),
        "include_resources": attr.string(
            default = "^(?!.*[.]class/?$)(?!jdk/(javadoc|jpackage|jshell|internal/jshell)/).*",
        ),
        "jni_configuration": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
        ),
        "jdk_library_path": attr.string(),
        "main_class": attr.string(
            default = _MAIN_CLASS,
        ),
        "native_image_tool": attr.label(
            allow_files = True,
            cfg = "exec",
            executable = True,
            mandatory = True,
        ),
        "optimization": attr.string(
            default = "2",
            values = [
                "b",
                "s",
                "0",
                "1",
                "2",
                "3",
            ],
        ),
        "output_path": attr.string(),
        "pgo_instrument": attr.bool(),
        "pgo_profiles": attr.label_list(
            allow_files = True,
        ),
        "gc": attr.string(
            values = [
                "",
                "G1",
                "epsilon",
                "serial",
            ],
        ),
        "parallelism": attr.int(),
        "reflection_configuration": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
        ),
        "_arm64_constraint": attr.label(
            default = Label("@platforms//cpu:arm64"),
        ),
        "_cc_toolchain": attr.label(
            default = Label("@bazel_tools//tools/cpp:current_cc_toolchain"),
        ),
        "_config_generator": attr.label(
            default = Label("//src:bazel_server_native_image_configs"),
            cfg = "exec",
            executable = True,
        ),
        "_macos_constraint": attr.label(
            default = Label("@platforms//os:macos"),
        ),
        "_x86_64_constraint": attr.label(
            default = Label("@platforms//cpu:x86_64"),
        ),
        "_xcode_config": attr.label(
            default = configuration_field(
                fragment = "apple",
                name = "xcode_config_label",
            ),
        ),
    },
    executable = True,
    fragments = [
        "apple",
        "cpp",
        "platform",
    ],
    toolchains = [
        "@bazel_tools//tools/cpp:toolchain_type",
    ],
)
