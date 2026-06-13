# Store overrides の pre-start mutation 案

- 更新日: 2026-04-30

## 背景

現状の `Store(...)` ファクトリは `overrides` 引数を含む 4 つのオーバーロードを持つ。

```kt
fun Store(overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(initialState: S, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(coroutineContext: CoroutineContext, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
fun Store(initialState: S, coroutineContext: CoroutineContext, overrides: Overrides<...> = {}, setup: Setup<...>): Store<...>
```

ここで `overrides` の用途は次のどちらかである。

- テストで shared *Store* configuration を rewrite せずに環境設定を上書きする
- AppStore ラッパー経由で project-wide な default を集約しつつ、debug build や test で差し替えできるようにする

設計原則上、`overrides` で触れるのは環境設定だけで、state/action handler の identity には触れさせない（[design-principles.md:21,30](../design/2026-04-23-design-principles.md:21)）。

一方で次の 3 点が API 上の摩擦になっている。

- `overrides` は典型ユーザーが触る引数ではないのに、4 オーバーロード全てのシグネチャ中央に常駐する
- AppStore ラッパー側にも `overrides: Overrides<...> = {}` 引数を生やす必要があり、ボイラープレート化している
- インラインで `Store {...}` を書いた本番コードはテスト側から override する手段がない（必ず関数化する必要がある）

## 現在の考え

`StoreImpl` の環境設定フィールド（`coroutineContext` / `stateSaver` / `exceptionHandler` / `middlewares` / `pendingActionPolicy` / `middlewareExecutionPolicy`）はいずれも `protected abstract val` で保持されており、`coroutineScope` や `_state` は `by lazy` で初回参照時のみこれらを読む。
つまり「Store が start するまでは値の入れ替えが安全に効く」状態が、すでに成立している。

これに乗り、`attachObserver()` と同じ流儀で **start 前のみ許可される環境設定の書き換え API** を `StoreInternalApi` に追加する。

```kt
@InternalKomaApi
interface StoreInternalApi<S,A,E> {
    suspend fun dispatchAndWait(action: A)
    fun attachObserver(observer: StoreObserver<S,E>, notifyCurrentState: Boolean = true)
    fun applyOverrides(block: Overrides<S,A,E>)        // 追加
}
```

公開拡張は `koma-core` 側に置く。

```kt
@OptIn(InternalKomaApi::class)
fun <S,A,E> Store<S,A,E>.applyOverrides(block: Overrides<S,A,E>): Store<S,A,E> {
    requireStoreInternalApi().applyOverrides(block)
    return this
}
```

実装側は `StoreImpl` の該当 6 フィールドを `internal var` に変えるだけで済む。`initialState` および `onEnter` / `onAction` / `onExit` / `onError` は state machine の identity なので `val` のまま据え置き、設計原則を型レベルで守る。

この方向の利点は次のとおり。

- ユーザーコードから `Overrides<>` 引数が完全に消える
  - `Store(...)` ファクトリから `overrides` 引数を削除でき、4 オーバーロードを 1 本に統合できる
  - AppStore ラッパー側からも `overrides` 引数を削除できる
- インライン本番 Store もテスト側から後がけで override 可能になる
  - 構築済み Store を rebuild する方式と違い、setup closure 保持なし、冪等性契約なし、新しい概念モデルなし
- 既存の `attachObserver` の "before start" gating パターンに完全に整合する
- 実装増分は最小（`StoreInternalApi` に 1 メソッド、`StoreImpl` の `val → var`、`StoreOverridesBuilder.applyTo(StoreImpl)` の overload 1 個、公開拡張 1 本）

期待されるユーザーコード:

```kt
// 通常の Store 定義（インラインでよい）
val store = Store {
    initialState(CounterState(count = 0))
    middleware(AppLoggingMiddleware())
    state<CounterState> { ... }
}

// AppStore ラッパー（overrides 引数が消える）
fun <S,A,E> AppStore(
    initialState: S,
    setup: Setup<S,A,E>,
): Store<S,A,E> = Store {
    initialState(initialState)
    middleware(AppLoggingMiddleware())
    exceptionHandler(AppExceptionHandler)
    setup()
}

// テスト
val testStore = CounterStore().applyOverrides {
    clearMiddlewares()
    exceptionHandler(ExceptionHandler.Log)
}

// debug build のスイッチ
val store = createMyStore().also {
    if (BuildConfig.DEBUG) it.applyOverrides { middleware(DebugMiddleware()) }
}
```

## 補足

- start 判定は既存の `attachObserver` と同じく、`coroutineScope` / `_state` の `lazy` 初期化や `initializeIfNeeded()` 呼び出しを基準にする想定。新たな仕組みは不要。
- start 後に `applyOverrides` を呼んだ場合は、暗黙適用や silently ignore ではなく `IllegalStateException` で落とす。`attachObserver` の挙動と揃える。
- 公開拡張の配置は `koma-core` を想定する。debug build / staging 切替などの production 利用も実需としてあり、テスト専用として `koma-test` に閉じ込める必然性は薄い。`Overrides<>` typealias と `StoreOverridesBuilder` も `koma-core` にある並びとも整合する。
- 拡張名は `applyOverrides` を仮置き。`withOverrides` は immutable copy の語感が強く、mutate-in-place の意味とずれる。`overrides` は既存 typealias と被るが短く読みやすい。最終決定は別に行う。
- AppStore ラッパー側の `overrides` 引数を削除しても、`AppStore(...) { ... }.applyOverrides { ... }` という形でテスト側の差し替えが可能になるため、現行の使い方の意図を失わない。

## 未解決事項

- 拡張名 (`applyOverrides` / `withOverrides` / `overrides` のいずれか) は未決定。
- `Store(...)` ファクトリのオーバーロード整理は別トピックだが、この案の採用と同タイミングで進める方が呼び出し側の移行コストを 1 度で済ませられる。
- `StoreImpl` 側で `val → var` 化する範囲の具体は未確定。`coroutineContext` のように `by lazy` 経由で 1 度しか読まれないものはそのまま `var` 化可能だが、`middlewares` のように複数箇所から参照されるものは並行性の確認が要る（とはいえ start 前なら基本的に single thread から触る前提でよい）。
- `applyOverrides` の戻り値を `Store<S,A,E>`（self を返す）にするか `Unit` にするかは未決定。chaining 利便性と副作用の見えやすさのトレードオフ。

## 関連

- [#182](https://github.com/koma-kt/koma/pull/182)
