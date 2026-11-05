"""Fail before building/uploading when a GitHub release names another version."""
import re
import sys
from pathlib import Path


def validate(root, tag):
    properties = (root / "gradle.properties").read_text()
    match = re.search(r"^actron\.fork\.version=(.+)$", properties, re.MULTILINE)
    if not match:
        raise ValueError("Missing release version in gradle.properties")
    version = match[1].strip()
    number = r"(?:0|[1-9][0-9]*)"
    pattern = rf"{number}\.{number}\.{number}(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
    if not re.fullmatch(pattern, version) or "SNAPSHOT" in version.upper():
        raise ValueError(f"Invalid release version: {version}")
    if tag.removeprefix("v") != version:
        raise ValueError(f"Release tag {tag} does not match publication version {version}")
    catalog = (root / "gradle/libs.versions.toml").read_text().split("[libraries]")[0]
    match = re.search(r'^actron\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
    if not match or match[1] != version:
        raise ValueError("Version catalog actron must match actron.fork.version")
    return version


if __name__ == "__main__":
    try:
        print("Validated release", validate(Path(sys.argv[1]), sys.argv[2]))
    except ValueError as error:
        sys.exit(str(error))
