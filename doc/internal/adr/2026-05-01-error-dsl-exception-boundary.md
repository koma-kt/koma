# `recover {}` DSL は `Exception` の回復経路に限定する

- 更新日: 2026-05-01

## 背景

Koma の `recover {}` DSL（deprecated な `error {}` alias を含む）は、state machine の中で発生した失敗を state 遷移として扱うための入口である。
一方で Kotlin の `Throwable` には、通常の業務例外として回復を試みるべき `Exception` だけでなく、`AssertionError` などの `Error` 系や、独自 `Throwable` のような非標準の失敗も含まれる。

これまでの実装では、fatal として即再送出していたものを除き、広く `Throwable` を `recover {}` 側へ流しうる形になっていた。
しかしこの形だと、次の境界が曖昧になる。

- `recover {}` が回復対象として扱う失敗
- `exceptionHandler()` が最後の受け皿として扱う失敗
- coroutine / job として成功完了にしてよい失敗
- job failure として扱うべき失敗

また、公開 DSL の型境界を `Throwable` のまま保つと、利用者からは「どの `Throwable` まで回復対象なのか」が読み取りにくい。

## 決定

`recover {}` DSL は、`Exception` を回復するための経路に限定する。

- `recover<T>` の `T` は `Exception` のみを受け付ける
- `RecoverScope.error` の型も `Exception` に限定する
- Store 内の recoverable path は `Exception` のみを `recover {}` に流す
- `Exception` ではない `Throwable` は recoverable とみなさず、job failure として扱う
- `Exception` ではない `Throwable` は `exceptionHandler()` に流れるが、`recover {}` には入れない
- middleware / observer / persistence など framework boundary で発生した `Exception` も、`recover {}` の回復対象には入れない

この判断により、意味づけは次のように固定する。

- `recover {}` は recovery path
- `exceptionHandler()` は last-resort path
- `recover {}` で処理できた失敗は、Store work としては成功完了でよい
- `recover {}` に乗らない失敗は、未回復のまま success 扱いにしない

## 補足

- `CancellationException` は `Exception` ではあるが、coroutine cancellation の制御信号でもあるため、通常の recovery 対象には入れない。
- したがって runtime 上は、「`Exception` なら常に recoverable」ではなく、「recoverable exception path へ流してよい `Exception` だけを対象にする」という整理になる。
- recoverable / non-recoverable の境界は型だけでは決まらない。state handler や launched `transaction {}` の中で投げられた `Exception` は recovery path に流すが、middleware hook、observer callback、`stateSaver.save()` など framework boundary で投げられた `Exception` は recovery path に再投入しない。
- そのため実装では、framework boundary で起きた `Exception` を `InternalError` で包み、`recover {}` に再突入しないようにしている。`exceptionHandler()` に渡す直前には unwrap して、利用者には元の `Exception` を見せる。
- `action {}` / `enter {}` / `exit {}` / launched `transaction {}` の中では、利用者は Kotlin の言語仕様上 `throw Throwable(...)` を書ける。この点は API 上の違和感になりうるため、README / KDoc では `recover {}` が `Exception` 専用の recovery path であることを明示する。
- この判断は source-compatible ではない。旧 `error<Throwable> { ... }` や custom `Throwable` を回復対象にしていたコードは移行が必要になる。
- 本メモは、Store の通常 runtime path における error handling 境界を対象とする。起動時の state restore など、`_state` 初期化まわりの個別事情はここでは扱わない。

## 関連

- [Koma の設計原則](../design/2026-04-23-design-principles.md)
