# Compatibility from the first Maven release

Status: proposed for the first Maven prerelease.

Use the first published Maven version as the baseline for public API and persisted
formats. The unreleased cleanup policy continues to describe earlier source builds.
Artifacts under a published version are immutable.

- Keep source and binary compatibility for supported public APIs in patch
  releases of a stable line. Prefer additive changes and deprecation before
  removal. Treat ABI dumps as a review gate and review behavioral contracts
  separately. Prereleases may break API between versions. Each such release
  needs a migration note, including changed defaults and lifecycle semantics.
- Keep reading formats written by published versions, including prereleases.
  A format change needs a previous-version reader, fixture, round-trip coverage
  and updated format inventory in the same change. Reject unknown newer formats
  explicitly. Follow the [format freeze policy](2026-10-01-format-freeze-policy.md).
- Application serializers and machine definition versions remain application
  contracts. Hosts migrating a definition must migrate or invalidate its
  checkpoints deliberately. Invalidating a screen snapshot must preserve any
  business outbox owned by the application.
- Record the toolchain and supported targets for each release. Use the
  published-consumer build to verify artifact metadata and target selection.
  Verify actual host builds before advertising compatibility with their toolchains.

For the baseline release, record the release tag, API dumps and format fixtures
from the same commit and check the signed artifacts after Maven Central serves them.
