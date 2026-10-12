#!/usr/bin/env python3
"""Offline checks for Gradle project() references and dependency cycles.

This is a static source check, not a Kotlin/Gradle compilation.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
includes = {
    name for declaration in re.findall(r"\binclude\(([^)]*)\)", settings, flags=re.S)
    for name in re.findall(r'"(:[^"]+)"', declaration)
}
builds = {}
for file in root.rglob("build.gradle.kts"):
    if file.parent == root or ".gradle" in file.parts or "build" in file.parts:
        continue
    module = ":" + ":".join(file.parent.relative_to(root).parts)
    builds[module] = file.read_text(encoding="utf-8")
errors = []
if set(builds) != includes:
    errors += [
        f"settings missing Gradle modules: {sorted(set(builds) - includes)}",
        f"settings includes nonexistent modules: {sorted(includes - set(builds))}",
    ]
dependencies = {}
for module, source in builds.items():
    dependencies[module] = set(re.findall(r'project\("(:[^"]+)"\)', source))
    for dep in dependencies[module]:
        if dep not in builds:
            errors.append(f"{module} references missing {dep}")

active, visited = set(), set()
def visit(module, chain):
    if module in active:
        errors.append("Cycle: " + " → ".join(chain + [module]))
        return
    if module in visited:
        return
    active.add(module)
    for dep in sorted(dependencies.get(module, ())):
        if dep in builds:
            visit(dep, chain + [module])
    active.remove(module)
    visited.add(module)

for module in sorted(builds):
    visit(module, [])

print(f"Gradle module graph: {len(builds)} modules, {sum(map(len, dependencies.values()))} references")
if errors:
    for error in errors:
        print("FAIL:", error, file=sys.stderr)
    sys.exit(1)
print("PASS: all declared project modules exist and the static dependency graph is acyclic")
print("NOTE: this does not test plugin resolution, JVM compilation or JNI/ARM64 binaries")
