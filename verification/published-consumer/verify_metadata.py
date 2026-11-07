"""Validate real publications, dependency coordinates and the production/debug boundary."""
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

repo, group, version, target = Path(sys.argv[1]), *sys.argv[2:]
production = {"actron-decompose", "actron-core", "actron-compose", "actron-logging", "actron-message", "actron-statechart", "actron-observability", "actron-statechart-compose", "actron-persistence"}
tooling = {"actron-test", "actron-statechart-test", "actron-timetravel", "actron-timetravel-test", "actron-timetravel-compose"}
production.add("actron-diagnostics")
if target in {"jvm", "android"}:
    production.add("actron-diagnostics-sdk")
if target == "android":
    production.add("actron-diagnostics-crashlytics")
modules = production | tooling
suffixes = {"jvm": ["jvm"], "android": ["android"], "js": ["js"], "wasm": ["wasm-js"], "ios": ["iosarm64", "iossimulatorarm64"]}[target]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
edges = {module: set() for module in modules}

# A BOM is dependency management, never an aggregate runtime dependency. Validate its real
# Maven POM, including modules outside this consumer's platform coverage.
bom_folder = repo / group.replace(".", "/") / "actron-bom" / version
bom = ET.parse(bom_folder / f"actron-bom-{version}.pom").getroot()
assert bom.findtext("m:packaging", namespaces=ns) == "pom"
assert not bom.findall("m:dependencies/m:dependency", ns), "BOM must not pull in tooling"
managed = {}
for item in bom.findall("m:dependencyManagement/m:dependencies/m:dependency", ns):
    assert item.findtext("m:groupId", namespaces=ns) == group
    assert item.findtext("m:version", namespaces=ns) == version
    managed[item.findtext("m:artifactId", namespaces=ns)] = version
assert modules | {"actron-diagnostics", "actron-diagnostics-sdk", "actron-diagnostics-crashlytics"} <= managed.keys(), managed
assert {module + "-" + suffix for module in modules for suffix in suffixes} <= managed.keys(), managed

def dependency(owner, dependency_group, name, dependency_version):
    assert not name.startswith("koma-"), (owner, "legacy Koma dependency", name)
    if not name.startswith("actron-"):
        return
    assert dependency_group == group, (owner, "wrong dependency group", dependency_group, name)
    assert dependency_version == version, (owner, "wrong dependency version", name, dependency_version)
    # Prefer the longest prefix: actron-statechart-test is not actron-statechart.
    base = max((module for module in modules if name == module or name.startswith(module + "-")), key=len, default=None)
    assert base is not None, (owner, "unknown Actron artifact", name)
    edges[owner].add(base)

for module in sorted(modules):
    for artifact in [module] + [module + "-" + suffix for suffix in suffixes]:
        folder = repo / group.replace(".", "/") / artifact / version
        stem = artifact + "-" + version
        pom = ET.parse(folder / (stem + ".pom")).getroot()
        assert pom.findtext("m:groupId", namespaces=ns) == group, artifact
        assert pom.findtext("m:artifactId", namespaces=ns) == artifact, artifact
        assert pom.findtext("m:version", namespaces=ns) == version, artifact
        assert pom.findtext("m:name", namespaces=ns) == "Actron", artifact
        for item in pom.findall("m:dependencies/m:dependency", ns):
            dependency(module, item.findtext("m:groupId", namespaces=ns), item.findtext("m:artifactId", namespaces=ns), item.findtext("m:version", namespaces=ns))
        metadata = json.loads((folder / (stem + ".module")).read_text())
        component = metadata["component"]
        assert component["group"] == group and component["module"] == module and component["version"] == version, artifact
        if artifact != module:
            assert (folder / component["url"]).is_file(), (artifact, "missing root metadata")
        for variant in metadata["variants"]:
            attributes = variant.get("attributes", {})
            if attributes.get("org.jetbrains.kotlin.platform.type") == "jvm":
                assert attributes.get("org.gradle.jvm.version") == 11, (artifact, "JVM baseline must not depend on build JDK", attributes)
            for item in variant.get("dependencies", []):
                dependency(module, item["group"], item["module"], item.get("version", {}).get("requires"))
            for item in variant.get("files", []):
                payload = folder / item["url"]
                assert payload.is_file(), (artifact, "missing payload", item["url"])
                assert payload.stat().st_size == item["size"], (artifact, "payload size", item["url"])
                assert hashlib.sha256(payload.read_bytes()).hexdigest() == item["sha256"], (artifact, "payload hash", item["url"])
                if target == "jvm" and payload.suffix == ".jar":
                    with zipfile.ZipFile(payload) as jar:
                        for name in jar.namelist():
                            assert not name.startswith("koma/"), (artifact, "legacy Koma package", name)
                            if name.endswith(".class"):
                                assert int.from_bytes(jar.read(name)[6:8], "big") <= 55, (artifact, "bytecode newer than Java 11", name)

for module in production:
    visited, pending = set(), [module]
    while pending:
        node = pending.pop()
        if node in visited:
            continue
        visited.add(node)
        pending.extend(edges[node])
    assert visited.isdisjoint(tooling), (module, "tooling in published production graph", visited & tooling)
print("Validated Maven POMs, Gradle metadata and payload hashes for", len(modules), "modules on", target)
