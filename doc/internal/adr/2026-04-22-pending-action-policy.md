# PendingActionPolicy 拡張案の却下

- 更新日: 2026-04-29

## 背景

現在の `PendingActionPolicy.ClearOnStateExit` は、別の state variant への遷移が確定したときだけ、すでに待機している action を捨てる。

検討対象にしたのは、次の 2 案。

- 同じ state 型のまま値だけ更新されたケース
- ある `dispatch()` の処理中に別の `dispatch()` が呼ばれ、待機列に積まれたケース

どちらも「古い前提で積まれた action を後続で実行しない」を自動化したい、という動機から出た案だった。

## 決定

次の 2 案は、どちらも `PendingActionPolicy` には入れない。

- state が変更されたら、待機中の action を捨てる
- ある `dispatch()` の処理中に追加で `dispatch()` された action を捨てる

現状の `PendingActionPolicy` は維持し、必要な場面では `clearPendingActions()` などの明示的な手段で対処する。

## 補足

- 現状の `ClearOnStateExit` は「state exit ベース」の挙動であり、「state change ベース」ではない。
- 「state が変更されたら捨てる」は、`state != nextState` を基準にすると効きすぎる。通常の `copy(...)` を含む多くの更新で待機 action が消えるため、一般 policy としては強すぎる。
- 「state が変更されたら捨てる」は、利用者から見ても挙動が読みづらい。見た目には通常の state 更新でも待機 action が消えるため、どの更新が action 破棄を引き起こすのかを追いにくい。
- その種の要件は利用頻度も高くなさそうで、汎用機能として持つより、必要な箇所で `clearPendingActions()` を呼ぶか、state の分け方や世代管理で表現するほうが意図が読みやすい。
- 「`dispatch()` の処理中に `dispatch()` された action を捨てる」は state ベースではなくタイミングベースのルールで、挙動が読みづらい。
- 同じ action でも「いつ dispatch されたか」で実行可否が変わるため、`dispatchAndWait()`、middleware、follow-up action との整合も悪くなりやすい。
- どちらも `PendingActionPolicy` の責務を広げすぎるため、現時点では採用しない。
- 関連する動きとして、検索、再送防止、二重 submit 防止、同じ非同期処理の多重起動抑止のようなユースケース向けに、`PendingActionPolicy` ではなく `action { launch(...) }` に局所的な重なり制御を入れる [PR #181](https://github.com/koma-kt/koma/pull/181) を進めている。
