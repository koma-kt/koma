# `cancelLaunch()` の公開範囲は `ActionScope` に留める

- 更新日: 2026-04-30

## 背景

`cancelLaunch(lane)` は、現状は `ActionScope` からのみ呼べる。
これは、`action { launch { ... } }` で開始した tracked launch を、現在 active な state runtime の中で lane 単位に止める API として導入しているためである。

一方で、設計上は次の 2 箇所へ広げる候補もある。

- `RecoverScope`: recovery の中で、関連する launched job も明示的に止めたい場合がある
- `ActionScope.LaunchScope.TransactionScope`: launched coroutine の結果を transaction で採用するタイミングで、別の tracked launch を止めたい場合がある

ここで判断したいのは、これらの候補に `cancelLaunch()` をそのまま広げるべきかどうかである。

## 決定

現時点では、`cancelLaunch()` の公開範囲は `ActionScope` のまま維持し、`RecoverScope` と `ActionScope.LaunchScope.TransactionScope` への公開は見送る。

- `cancelLaunch()` は、引き続き「action をきっかけに始めた tracked launch を止める API」として扱う
- error recovery や launched coroutine 内 transaction からの lane cancellation は、現段階では標準公開しない
- 具体的なユースケースが将来たまった場合のみ、別 API を含めて再検討する

## 補足

- `RecoverScope` は store work ではあるが、役割の中心は recovery である。ここに lane cancellation を足すと、「例外処理として何を回復しているのか」と「state-owned な非同期仕事をどの時点で止めるのか」が同じ場所に混ざりやすい。
- launched job を止めたい理由が「この error 以後は古い仕事を採用したくない」なのであれば、state transition による runtime の入れ替えや、明示 action による cancellation の方が追いやすい。
- `ActionScope.LaunchScope.TransactionScope` は一見すると有力だが、そのまま `cancelLaunch()` を公開すると self-cancel の問題がある。transaction は launched job 本体の中で直に実行されるのではなく、別 job で実行して外側が `join()` しているため、同じ explicit lane を共有している場合に自分自身の tracked launch を止められてしまう。
- この場合、transaction 側は state 更新まで進める一方で、外側の launched job だけが cancel される形になりうるため、挙動が直感的でない。
- さらに、`ActionScope.LaunchScope.TransactionScope` から見て「別 lane を止めたい」のか「自分が属する lane も止めてよい」のかを、現在の `cancelLaunch(lane)` 署名だけでは表現できない。
- Koma では「action は処理開始のきっかけであり、継続中の仕事の所有者は state」という整理を取っている。そのうえで、lane cancellation の入口はまず `ActionScope` に閉じていた方が、どの action 判断で停止したのかを読み取りやすい。
- したがって今回は、候補があることは認めつつも、具体的なユースケースが判明するまでは現在の `cancelLaunch()` を他 scope に広げる判断は採らない。

## 関連

- [#190](https://github.com/komakt/koma/issues/190)
