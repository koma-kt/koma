# Unhandled action behavior の整理

- 更新日: 2026-04-25

## 背景

現状の Store は、dispatch された action に対して現在の `state` と `action` の両方にマッチする handler を 1 件だけ選んで実行する。

action handler は登録順に評価され、最初にマッチしたものだけが採用される。
マッチする handler が 1 件もなければ、action はそのまま何もせず終わり、state も変わらない。

この挙動自体は、state machine としては不自然ではない。
UI の都合や非同期処理のタイミングにより、「今の state では無効な action」が届くことは普通に起こりうる。

一方で、今の挙動だと次の 2 つが外から見分けにくい。

- その state では本当に ignore されてよい action
- Store DSL の書き漏れや、複数マッチ時の順序違い、想定違いにより accidental に ignore されている action

## 問題整理

`Unhandled action behavior` で主に問題になるのは、runtime policy が足りないことではなく、次の診断ギャップである。

### 1. `unhandled` と `handled だが state unchanged` が外から区別しにくい

現状では、未処理 action は単に state unchanged として観測される。
しかし state unchanged は unhandled のときだけでなく、次のような場面でも普通に起こる。

- handler はマッチしたが `nextState { ... }` を呼ばない
- handler は event だけを emit する
- handler は `clearPendingActions()` などの副作用だけを行う

このため、利用者からは「未処理だった」のか「処理されたが state は変わらなかった」のかが分かりにくい。

### 2. DSL surface が first-match-wins を強く伝えない

現在の `state<S2>` / `action<A2>` / `anyState` / `anyAction` は見た目が宣言的であり、条件に合う handler 群を追加しているように読みやすい。
しかし実際の解決は registration order に従う first match wins であり、意味論としては ordered rule chain に近い。

特に `anyAction` や `anyState` は、「広い条件の handler を追加している」ように見えやすい。
その一方で、実際には置き方によって後続 handler を覆いうる。

このため、利用者は additive なつもりで DSL を書いたのに、実際には先勝ちの順序依存が効いている、というズレが起きやすい。

### 3. 複数マッチの意味づけはライブラリ側では決められない

複数の handler が同時にマッチする場合も、それがミスなのか利用者が意図した overlap なのかはライブラリ側だけでは判定できない。
ライブラリが分かるのは、「0 件だった」「1 件だった」「2 件以上だった」「実際に選ばれたのはどれか」という事実までである。

したがって、主眼は runtime semantics の変更ではなく diagnostics/debug の不足にある。
また、action の dispatch は非同期であり、production runtime の一般機能として `IGNORE / LOG / THROW` のような policy を増やすと、次の問題がある。

- `THROW` はどの call site にどう失敗を返すかが分かりにくい
- `LOG` は正常な ignore まで大量に拾ってノイズになりやすい
- state machine として自然な ignore を runtime policy で過度に意味づけしやすい

## 現在の考え

`UnhandledActionPolicy` のような一般 runtime policy は入れず、default behavior は現状の ignore のまま維持するのがよい。

代わりに、必要なチームだけが test/debug 時に action routing を見える化できる opt-in diagnostics を用意する。
さらに、低コストな改善として DSL の意味論を README / KDoc で今より明示した方がよい。

優先順としては、次の並びが自然である。

### 1. README / KDoc で first-match-wins を明示する

最小の改善として、少なくとも次は明記した方がよい。

- action handler の解決は registration order に従う
- 複数マッチした場合は先頭 1 件だけが採用される
- `anyAction` / `anyState` は additive な意味ではなく、置き方によって後続 handler を覆いうる
- fallback として使う場合は末尾に置く方が分かりやすい

これだけでも、「宣言的に見える DSL」と「実際は先勝ちの ordered rules」というズレを多少は埋められる。

### 2. `:koma-test` の routing diagnostics

dispatch せずに、「この `state` と `action` で何件 handler がマッチするか」を確認する API を用意する。

例:

```kt
fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    state: S,
    action: A,
): ActionMatchDiagnostics<S, A>

suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.diagnoseActionMatches(
    action: A,
): ActionMatchDiagnostics<S, A>
```

`matchedHandlerCount`、`matchedHandlerIndices`、`selectedHandlerIndex` を返せば、次の区別ができる。

- `0 件`: unhandled
- `1 件`: 一意に handled
- `2 件以上`: 複数マッチがある

ここで重要なのは、`2 件以上` をライブラリが直ちに異常と断定しないことである。
複数マッチは accidental な overlap かもしれないし、利用者が意図した fallback 構成かもしれない。
この API は判定ではなく観測結果を返すものとして扱うのがよい。

これは runtime behavior を変えず、routing 定義の意図を直接テストできる。

### 3. `:koma-test` の dispatch 時 assert

dispatch と同時に、想定したマッチ件数を assert する API を用意する。

例:

```kt
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndWait(
    action: A,
    expectedMatchCount: Int,
)
```

または名前を分けて、

```kt
suspend fun <S : State, A : Action, E : Event> Store<S, A, E>.dispatchAndRequireMatchCount(
    action: A,
    expected: Int,
): ActionDispatchDiagnostics<S, A>
```

のようにしてもよい。

これは dispatch 前に現在の `state` と `action` に対する match count を確認し、期待と違えば `AssertionError` で落とす。
その後に通常の `dispatchAndWait()` を実行する。

これは routing diagnostics の convenience として位置づけるのが自然であり、まず純粋な観測 API を持ったうえで載せる方が整理しやすい。

test では `expectedMatchCount = 1` が最もよく使われる想定である。
`0` を指定すれば「この action は今の state では未処理のはず」を確認できる。

### 4. core の opt-in reporter

test 専用 API とは別に、debug build や検証環境でだけ未処理 action を報告したい場合に備えて、core に opt-in reporter を用意する案はある。

例:

```kt
fun <S : State, A : Action, E : Event> StoreBuilder<S, A, E>.unhandledActionReporter(
    reporter: UnhandledActionReporter<S, A>,
)

fun <S : State, A : Action, E : Event> StoreOverridesBuilder<S, A, E>.unhandledActionReporter(
    reporter: UnhandledActionReporter<S, A>,
)
```

用途は logging、debug fail-fast、telemetry などである。
ただしこれは `unhandled` だけを扱うものであり、複数マッチや shadowing の診断までは扱えない。
そのため優先度は `:koma-test` の match diagnostics より下でよい。

## 補足

- `StoreObserver` に action diagnostics を足す案は、public surface を広げるわりに責務が重くなりやすい。
- middleware で unhandled を後付け検知する案は、handled だが state unchanged のケースと区別しにくい。
- `unhandled` と `複数マッチ` はどちらもライブラリが意味づけまで決めるべきではなく、まず事実を観測可能にする方がよい。

## 関連する別方向の案

以下は同じ曖昧さを別の層で扱う案だが、#175 の主対象である runtime/test diagnostics からは少し外れる。

### 型でのコンパイル時解決

完全な compile-time 解決は難しい。

理由は、action handler の解決が `Action` の型だけではなく、「dispatch 時点の current state」に依存するためである。
同じ `Action` でも、どの state にいるかによって `0 件 / 1 件 / 2 件以上` が変わりうる。

このため、通常の `Store.dispatch(action)` を前提にしたまま compiler に一意解決を求めるのは難しい。
もし compile-time で厳密に扱いたいなら、state-scoped な dispatch API に作り替えるか、KSP / compiler plugin により別の型付き API を生成する方向が必要になりやすい。

これは現在の Store DSL の延長というより、別の設計に近い。

### Store の `build()` 時の検証

`build()` 時の検証は、型による compile-time 解決よりは現実的である。

現在の DSL は `state<S2>` / `anyState` と `action<A2>` / `anyAction` を最終的に predicate として登録している。
ただし現状の実装では、build 時に残っているのは主に predicate ラムダであり、「どの matcher から来たか」の高水準情報は保持していない。

そのため、`build()` 時に検証するなら、predicate だけでなく次のような matcher metadata を保持する必要がある。

- `AnyState`
- `StateType(S2)`
- `AnyAction`
- `ActionType(A2)`

これを持てば、`build()` 時に次のような検査はしやすくなる。

- 複数マッチが起きうる組み合わせがあるか
- 先行 handler によって後続 handler が覆われうるか
- 選択順が registration order に依存している箇所があるか

ただし、ここでもライブラリが分かるのは構造上の事実までである。
それが accidental な overlap なのか、利用者が意図した fallback 構成なのかまでは決められない。

したがって、`build()` 時の検証を入れる場合でも、最初は hard error より warning や debug assertion に寄せる方が自然である。

## 未解決事項

- `ActionMatchDiagnostics` / `ActionDispatchDiagnostics` にどこまで情報を載せるかは未決定。少なくとも `matchedHandlerCount` と `selectedHandlerIndex` は必要になりやすい。
- `dispatchAndWait(expectedMatchCount)` のような assert API を最初から同時に入れるか、routing diagnostics の上に後から載せるかは未決定。
- README / KDoc では `first match wins` をそのまま用語として出すか、`registration order` 中心に説明するかは未決定。
- core reporter を入れる場合、reporter が例外を投げたときに `recover{}` ではなく `ExceptionHandler` 側へ流す整理でよいかは確認が必要。

## 関連

- [#175](https://github.com/koma-kt/koma/issues/175)
