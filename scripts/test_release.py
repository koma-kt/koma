"""Release tags must identify the exact version published by the build."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate-release.py")


class ReleaseTest(unittest.TestCase):
    def validate(self, tag, version="5.0.0-alpha.1", catalog="5.0.0-alpha.1"):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "gradle").mkdir()
            (root / "gradle.properties").write_text(f"actron.fork.version={version}\n")
            (root / "gradle/libs.versions.toml").write_text(f'[versions]\nactron = "{catalog}"\n')
            return subprocess.run([sys.executable, str(SCRIPT), str(root), tag], capture_output=True, text=True)

    def test_stable_and_prerelease_tags(self):
        for tag in ("v5.0.0-alpha.1", "5.0.0-alpha.1"):
            self.assertEqual(self.validate(tag).returncode, 0)
        self.assertEqual(self.validate("v5.0.0", "5.0.0", "5.0.0").returncode, 0)

    def test_tag_must_match_publication_version(self):
        result = self.validate("v5.0.0-alpha.2")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)

    def test_catalog_must_match_publication_version(self):
        result = self.validate("v5.0.0-alpha.1", catalog="4.0.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("catalog", result.stderr)

    def test_releases_reject_snapshots_and_invalid_versions(self):
        for version in ("5.0.0-SNAPSHOT", "latest", "5.0", "01.0.0"):
            result = self.validate("v" + version, version, version)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("release version", result.stderr)
