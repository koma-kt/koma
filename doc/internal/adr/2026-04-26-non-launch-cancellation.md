# 非 `launch` 処理には cancellation API を入れない

- 更新日: 2026-04-30

## 背景

`#190` では、`action { launch { ... } }` で開始した仕事を lane 単位で明示的に止める `cancelLaunch(lane)` を検討している。

一方で、`launch` を使わない通常の `action {}`、`enter {}`、`exit {}`、`recover {}`、および `transaction {}` には、いま実行中の処理を途中で止める API はない。

ここで判断したいのは、`#190` のような cancellation を非 `launch` の store work にも広げるべきかどうかである。

## 決定

非 `launch` の store work には、in-flight cancellation API を追加しない。

- 通常の `action {}`、`enter {}`、`exit {}`、`recover {}`、`transaction {}` は、短く終わる直列・原子的な store work として扱う。
- cancellation が必要な非同期処理や長く生きる仕事は、`launch {}` に出して扱う。
- `clearPendingActions()` は引き続き「後ろに積まれた pending action を捨てる API」として扱い、現在実行中の store work は止めない。

## 補足

- 非 `launch` の store work は、`dispatchAndWait()` の完了単位であり、middleware 実行や state 遷移判定とも同じ直列 pipeline に載っている。途中 cancel を入れると、「どこまで反映済みか」「middleware は完了扱いか」「error はどう扱うか」が読みにくくなる。
- 通常の handler の中で長い suspend 処理や I/O を直接走らせると、cancel 可否以前に Store 全体を塞ぎやすい。その種の処理は `launch {}` へ移す前提で考える。
- Koma では「action は処理開始のきっかけであり、継続中の仕事の所有者は state」という整理を取っている。`launch {}` の仕事が state scope にぶら下がるのはそのためであり、明示 cancellation もまずはその範囲に閉じるのが自然である。
- したがって `#190` は、一般 cancellation の入口ではなく、`action { launch { ... } }` という既存の state-owned な非同期処理に対する局所的な拡張として扱う。

## 関連

- [#190](https://github.com/koma-kt/koma/issues/190)
