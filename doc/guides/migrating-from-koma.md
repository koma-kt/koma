# Migrating from Koma to Actron

Actron is the new name of this Koma fork. This is a breaking source and binary rename:
recompile applications and libraries that consume it. No compatibility aliases or Maven
relocations are provided. The upstream Koma project retains its name and coordinates.

| Before | After |
|---|---|
| `io.github.roman-n1:koma-<module>` | `io.github.roman-n1:actron-<module>` |
| `koma.*` imports and Android namespaces | `actron.*` |
| `ExperimentalKomaApi`, `InternalKomaApi`, `KomaStoreDsl` | No experimental marker; `InternalActronApi`, `ActronStoreDsl` |
| `:koma-core` and other Gradle module paths | `:actron-core` and equivalent `:actron-*` paths |
| `koma.publish` convention plugin | `actron.publish` |
| `koma.*` Gradle properties / `KOMA_*` environment variables | `actron.*` / `ACTRON_*` |
| `.koma/`, `*.koma.json` model artifacts | `.actron/`, `*.actron.json` |
| Koma Behavioural Model IDE plugin | Actron Behavioural Model (`io.github.roman-n1.actron`) |

The configured version is `1.0.0-beta.1`; the renamed artifacts have not been
published by this change. For a local composite build, point `includeBuild` at the Actron
checkout and request `io.github.roman-n1:actron-core:1.0.0-beta.1` (plus optional modules).

Update dependencies, imports, opt-ins, Gradle commands, scripts and model artifact paths
together. Reinstall the IDE plugin under its new ID. The core Kotlin/Native, JS and Wasm
library identity now belongs to Actron too; do not substitute Actron for a Koma dependency
inside a previously compiled JAR or klib.

Actron evolves independently of upstream Koma 4.0.0. Source, binary and runtime
interoperability are unsupported, including running frozen Koma consumers alongside Actron.
Migrate consumers to Actron and recompile them. Their types are distinct: a `koma.core.Store`
cannot be passed to an Actron API. Actron's published-consumer checks reject upstream Koma
dependencies in the resolved production and tooling graphs.

The frozen journal/recording file magic bytes (`KOMAJRNL`, `KOMARECD`, `KOMAGRPO`) and
format versions are retained so a branding change does not invalidate file framing.
Regenerate exported model/coverage artifacts using Actron. Review application-owned
persistence codecs and serialized type names before reusing old recordings or saved state;
the package rename does not promise compatibility for those payloads. Migrate and verify
application-owned payload codecs before loading historical data with Actron.

The GitHub repository is now [roman-n1/actron](https://github.com/roman-n1/actron).
Update existing clones with `git remote set-url origin https://github.com/roman-n1/actron.git`.
Repository hosting and Maven publication are separate; the repository rename does not
publish artifacts. Upstream attribution and the MIT license are retained.

Actron also enforces a [null-free domain API](absence-policy.md). Nullable arguments and results
from earlier fork versions require the callback, concrete-overload or domain-phase replacements
listed there. Optional-like wrappers are forbidden too. The five journal/recording format versions
remain frozen and their old readers are covered by golden and crash/truncation tests.
