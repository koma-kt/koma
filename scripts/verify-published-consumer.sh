#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TARGET=${1:-jvm}
CONSUMER_ROOT=${ACTRON_CONSUMER_PROJECT:-$ROOT}
CONSUMER_WRAPPER="$CONSUMER_ROOT/gradlew"
if [[ "$CONSUMER_ROOT" != "$ROOT" ]]; then
  CONSUMER_WRAPPER=$(cd "$CONSUMER_ROOT" && pwd)/gradlew
fi
case "$TARGET" in
  jvm) PUBLICATIONS=(Jvm); TASKS=(:app:jvmTest :tooling:compileKotlinJvm) ;;
  android) PUBLICATIONS=(Android); TASKS=(:app:compileAndroidMain :tooling:compileAndroidMain) ;;
  js) PUBLICATIONS=(Js); TASKS=(:app:compileKotlinJs :tooling:compileKotlinJs) ;;
  wasm) PUBLICATIONS=(WasmJs); TASKS=(:app:compileKotlinWasmJs :tooling:compileKotlinWasmJs) ;;
  ios) PUBLICATIONS=(IosArm64 IosSimulatorArm64); TASKS=(:app:compileKotlinIosArm64 :app:compileKotlinIosSimulatorArm64 :tooling:compileKotlinIosArm64 :tooling:compileKotlinIosSimulatorArm64) ;;
  *) printf 'Usage: %s [jvm|android|js|wasm|ios]\n' "$0" >&2; exit 2 ;;
esac
WORK=$(mktemp -d "${TMPDIR:-/tmp}/actron-published-consumer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
# Read the actual publishing coordinates and plugin versions, without duplicating release values.
value() { python3 "$ROOT/verification/published-consumer/config.py" "$ROOT" "$1"; }
consumer_value() {
  if [[ "$CONSUMER_ROOT" == "$ROOT" ]]; then
    value "$1"
  else
    python3 "$ROOT/verification/published-consumer/config.py" "$CONSUMER_ROOT" "$2"
  fi
}
GROUP=$(value group)
VERSION=$(value version)
MODULES=(actron-decompose actron-core actron-compose actron-logging actron-message actron-test actron-statechart actron-observability actron-persistence actron-timetravel actron-statechart-test actron-timetravel-test actron-timetravel-compose actron-statechart-compose)
MODULES+=(actron-diagnostics)
case "$TARGET" in
  jvm) MODULES+=(actron-diagnostics-sdk) ;;
  android) MODULES+=(actron-diagnostics-sdk actron-diagnostics-crashlytics) ;;
esac
PUBLISH_TASKS=(:actron-bom:publishMavenPublicationToConsumerRepository)
for module in "${MODULES[@]}"; do
  for publication in KotlinMultiplatform "${PUBLICATIONS[@]}"; do
    PUBLISH_TASKS+=(":$module:publish${publication}PublicationToConsumerRepository")
  done
done
"$ROOT/gradlew" -p "$ROOT" "${PUBLISH_TASKS[@]}" -Pactron.consumer.repository="$WORK/maven" --no-configuration-cache --max-workers=2 --no-daemon
python3 "$ROOT/verification/published-consumer/verify_metadata.py" "$WORK/maven" "$GROUP" "$VERSION" "$TARGET"
PROPERTIES=(-Pconsumer.repository="$WORK/maven" -Pconsumer.group="$GROUP" -Pconsumer.version="$VERSION" -Pconsumer.target="$TARGET"
  -Pconsumer.kotlin="$(consumer_value kotlin kotlin)" -Pconsumer.agp="$(consumer_value agp android-gradlePlugin)" -Pconsumer.compose="$(consumer_value compose-multiplatform composeMultiplatform)" -Pconsumer.coroutines="$(consumer_value coroutines kotlinxCoroutinesCore)"
  -Pconsumer.compileSdk="$(consumer_value android-compileSdk android-compileSdk)")
if [[ "$TARGET" == jvm ]]; then
  if [[ -n "${ACTRON_CONSUMER_JAVA11_HOME:-}" ]]; then
    PROPERTIES+=(-Pconsumer.java11.home="$ACTRON_CONSUMER_JAVA11_HOME")
  elif [[ "${CI:-}" == true ]]; then
    printf 'JVM consumer CI requires ACTRON_CONSUMER_JAVA11_HOME; Java 11 execution must not be skipped\n' >&2
    exit 1
  else
    printf 'Java 11 home not supplied; local smoke uses the Gradle runtime (CI executes on Java 11)\n'
  fi
fi
mkdir -p "$WORK/positive" "$WORK/negative"
# Compile the exact screen declaration and Store construction shown to new users.
awk '/^<!-- compiled-quick-start -->$/ { marker=1; next }
     marker && /^```kotlin$/ { code=1; next }
     code && /^```$/ { done=1; exit }
     code { print }
     END { if (!done || !code) exit 1 }' "$ROOT/README.md" > "$WORK/positive/ReadmeCounter.kt"
cp "$ROOT/verification/published-consumer/contracts/PositiveContracts.kt" "$WORK/positive/"
cp "$ROOT/verification/published-consumer/contracts/"*.kt "$WORK/negative/"
"$CONSUMER_WRAPPER" -p "$ROOT/verification/published-consumer" "${PROPERTIES[@]}" -Pconsumer.contractSources="$WORK/positive" "${TASKS[@]}" verifyPublishedGraphs --no-configuration-cache --max-workers=2 --no-daemon
if [[ "$TARGET" == jvm ]]; then
  REPORTS="$ROOT/verification/published-consumer/build/reports/contracts"
  mkdir -p "$REPORTS"
  if "$CONSUMER_WRAPPER" -p "$ROOT/verification/published-consumer" "${PROPERTIES[@]}" -Pconsumer.contractSources="$WORK/negative" :app:compileKotlinJvm --no-configuration-cache --max-workers=2 --no-daemon > "$REPORTS/negative-compile.log" 2>&1; then
    printf 'Forbidden adapter DSL compiled successfully; contract verification failed\n' >&2
    exit 1
  fi
  python3 "$ROOT/verification/published-consumer/verify_negative.py" "$WORK/negative/NegativeContracts.kt" "$REPORTS/negative-compile.log"
fi
printf 'Published consumer verification passed: %s (%s:%s)\n' "$TARGET" "$GROUP" "$VERSION"
