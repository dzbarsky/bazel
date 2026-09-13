"""One native network observation under Bazel's ordinary test sandbox."""

def _network_test_impl(ctx):
    executable = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        executable,
        """#!/bin/bash
set -euo pipefail
exec "$DIAGNOSTIC_PYTHON" "$DIAGNOSTIC_DRIVER" --probe "$DIAGNOSTIC_RUNTIME" "$DIAGNOSTIC_CLASSES" "$TEST_UNDECLARED_OUTPUTS_DIR"
""",
        is_executable = True,
    )
    return [DefaultInfo(executable = executable)]

network_test = rule(implementation = _network_test_impl, test = True)
