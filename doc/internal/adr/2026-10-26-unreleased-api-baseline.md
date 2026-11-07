# Supported API before the first release

- Date: 2026-10-26
- Status: accepted by the project owner
- Supersedes the experimental boundary in 2026-10-01-stable-core and the pre-release previous-reader requirement in 2026-10-01-format-freeze-policy.

Actron has not published a release. All current public APIs, including external sources,
replay, branches, inspector, Compose adapters, persistence and testing, are supported without
ExperimentalActronApi. The marker and its opt-ins are removed, rather than retained as aliases.
Third-party Kotlin/Compose experimental annotations and InternalActronApi retain their purpose.

There is no compatibility layer for earlier development versions. Deprecated forwarding APIs
and mutable group member replay cursors are removed. Consumers use canonical methods and the
read-only memberViews derived from the group cursor. Store directly owns observation and closeAndJoin; the optional StoreLifecycle compatibility
capability and old collect callbacks are removed. Recording and journal codecs accept only their current
formats; FormatMigration and built-in old-version migrations are removed. Unsupported formats
are rejected explicitly. Definition identity/version validation remains necessary to prevent
replaying a recording with a different machine; this is not old-version support.

Existing format identifiers remain unchanged because this change removes readers and aliases,
without changing the current writer's representation. Current-format round trips, golden files,
malformed-input rejection and the API dumps remain checked. The
[first Maven release policy](2026-11-07-published-compatibility.md) defines the compatibility
boundary for published versions.
