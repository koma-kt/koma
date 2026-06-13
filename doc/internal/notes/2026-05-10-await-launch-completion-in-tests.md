# enter / action 内 launch の完了を待つテスト用 API 案

- 更新日: 2026-05-10

## 背景

`enter {}` および `action {}` の中で、Store DSL の `launch {}` を使って副作用処理を起動できる。
テストコードからこの launch の完了を待ちたい場面がある。
具体的には、次のような前提を置く。

- `launch(Dispatchers.IO)` のように dispatcher を直書きしているケースもある
- そのため、テスト側は `kotlinx-coroutines-test` の `TestDispatcher` / `runTest` / `advanceUntilIdle` などには寄せたくない
- 既存の `startAndWait()` / `dispatchAndWait()` は、起動 / dispatch 自体の同期部分の完了は待つが、その内部から `launch` で起動された子 job の完了は待たない

副作用の結末が必ず state 遷移か event 発火に出る設計であれば、テストは標準の Flow API（`state.first { ... }` 等）で待てる。
ただし、副作用だけで完了する fire-and-forget な launch（ロギング、計測、外部送信のみ等）は state / event 経由では観測できない。
このような launch も含めて「Store が落ち着いた状態」を待ちたい、というのが本 note の動機である。

## 現在の考え

Store 内部に「現在動いている launch の子 job 群を join するまで待つ」API を入れる方針。
便宜上ここでは `awaitIdle()` と呼ぶ。

実装上の足場はすでに揃っている。
[StoreImpl.kt:131](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt) の `stateRuntimes` が state クラスごとに `StateRuntime` を持ち、
[StoreImpl.kt:143](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt) の `StateRuntime` は `scope` と `actionLaunchJobs` を保持する。
enter / action の `launch {}` は [StoreImpl.kt:443](../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt) の `launchInStateRuntime()` を経由してこの scope 上で起動される。
したがって、現在追跡されている子 job をすべて辿って join できる。

概念実装は次の通り。

```kt
internal suspend fun StoreImpl<*, *, *>.awaitIdle() {
    while (true) {
        val jobs = stateRuntimes.values.flatMap { runtime ->
            runtime.actionLaunchJobs.values + listOfNotNull(
                // enter 内 launch も追跡対象に含めるなら、ここで参照できる形に揃える
            )
        }
        if (jobs.none { it.isActive }) return
        jobs.joinAll()
        // join 中に新たな launch が積まれた可能性があるので、安定するまで反復する
    }
}
```

特徴は次のとおり。

- dispatcher の種類に依存しない。`Dispatchers.IO` 直書きでも、Job は state-scope の子なので追跡できる
- 固定 sleep を使わない。Job の完了という事実を直接見る
- `Store.close()` とは目的が異なる。`close()` は「打ち切る」、`awaitIdle()` は「終わるまで待つ」

### 終わらない launch (Flow 購読など) の扱い

`awaitIdle()` の素直な実装には穴がある。
launch の中で Flow を購読しているケース、例えば `launch { repository.userFlow.collect { ... } }` のようなものは、
state が遷移して scope が cancel されるまで完了しない。
`joinAll()` するとそのまま固まる。

`Job` だけを見て「処理中」と「`collect` 等で suspend して待機中」を区別する手段はない。
`kotlinx-coroutines-test` の `advanceUntilIdle` がこれを実現できているのは、
TestDispatcher のキューを直接覗いて「実行待ちタスクの有無」を判定しているためで、
実 dispatcher (`Dispatchers.IO` 等) では原理的に同じことができない。
TestDispatcher を使わない方針を取る以上、この区別は Koma 側で何らかの形で持ち込む必要がある。

考えられる方向はいくつかある。

- **DSL 側で長命購読を別 API に分ける**。`launch {}` は短命副作用前提、長命購読は `subscribe { ... }` 相当の別 API に切り出し、`awaitIdle()` の追跡対象からは外す。意味付けはきれいだが API 追加が要る
- **launch にフラグを足す**。`launch(awaitable = false) { ... }` のような形で、待機対象から除外することを呼び出し側が宣言する。互換性は保ちやすいが、書き忘れによるテスト固まりが起きうる
- **quiescence ベースに切り替える**。「state も event も一定時間動かない」を idle とみなす。実装は単純だがタイミング依存で fragile。本方針の前提（TestDispatcher を使わない）と相性が悪い
- **`awaitIdle()` 自体を諦める**。終わる予定の launch だけ標準の Flow API で待ち、購読系を含む全体待機はサポートしない。例えば次のように書ける

  ```kt
  val loaded = store.state.first { it is Loaded || it is Error }
  ```

  ここで使う `first` / `filter` / `take` などは `kotlinx-coroutines-core` 側の標準 API であり、`kotlinx-coroutines-test` への依存ではない。前提と矛盾しない。
  ただし、副作用の終端が state / event に必ず出ない launch（fire-and-forget なロギングや計測など）は観測できないため、本 note の動機は満たせない

### 公開範囲

既存の `startAndWait()` / `dispatchAndWait()` と同じ二段構成に揃えるのが自然である。

- `koma-core` の [`StoreInternalApi`](../../koma-core/src/commonMain/kotlin/koma/core/StoreInternalApi.kt) (`@InternalKomaApi`) に `suspend fun awaitIdle()` を追加する
- `StoreImpl` で実装する
- `koma-test` の [`StoreExtensions`](../../koma-test/src/commonMain/kotlin/koma/test/StoreExtensions.kt) に public extension を置き、`requireStoreInternalApi().awaitIdle()` に委譲する

これにより、テスト用の段階的な待機 API として一貫する。

- `startAndWait()`: start の同期部分まで待つ
- `dispatchAndWait(action)`: dispatch の同期部分まで待つ
- `awaitIdle()` (新): start / dispatch から派生した launch がすべて落ち着くまで待つ

`dispatchAndWait(action)` の直後に `awaitIdle()` を呼ぶ、というのが典型的な使い方になる。

### 現時点での見立て

- `awaitIdle()` を入れる方向で進める。fire-and-forget な launch まで含めて Store の落ち着きを待てるのは、本案以外には現状ない
- ただし長命 launch（Flow 購読など）とそれ以外を区別する仕組みを併せて入れることが前提。区別なしでは Flow 購読を含む Store のテストが容易に固まる
- 区別の仕組みを入れるコストが見合わないと判断する場合に限り、`awaitIdle()` 自体を諦めて Flow API による観測に倒す選択肢が残る

## 未解決事項

- API 名。`awaitIdle()` / `awaitAllLaunches()` / `quiesce()` のいずれにするか
- `koma-test` の extension 名。`awaitIdle()` のまま出すか、`startAndWait()` / `dispatchAndWait()` の語感に合わせた別名にするか
- enter 内 launch の Job 追跡。`actionLaunchJobs` には載っていない可能性があるため、enter 起動の launch も同じ map で追跡するか、別の追跡経路を持つか
- 反復 join のセマンティクス。`awaitIdle` 中に新規 launch が無限に積まれるケースをどう扱うか（タイムアウト / 上限回数 / そもそも保証しない）
- 長命 launch の区別方法。本文で挙げた 4 案のうち、どれを採るか
- state 遷移をまたぐ場合の意味。state が切り替わると前 state の scope は cancel されるので、cancel された Job への join 待ちが意図せず長引かないかを検証する必要がある
- `public` 公開の是非。本番コードで `awaitIdle()` を呼びたくなる場面が出るかどうか。テスト専用に留めるなら API surface に出さない方が安全
- タイマー駆動など、dispatch 起点でない launch をどう扱うか。完全な idle を待つ意味付けにするか、dispatch 由来のものに限定するか
