# AutoStartPolicy.OnDispatchOrStateCollection または AutoStartPolicy.OnDispatch の挙動について

- 更新日: 2026-05-10

## 背景

`AutoStartPolicy.OnDispatchOrStateCollection`（default）と `AutoStartPolicy.OnDispatch` のいずれも、`dispatch()` を起点として Store の開始処理が走り得る。
このとき、開始処理と dispatch の関係、特に `PendingActionPolicy.ClearOnStateExit` との相互作用について、UI 側から見たときの前提を整理しておく。

## 現在の考え

### dispatch 時の開始処理の見え方

`dispatch()` 時に Store がまだ開始していなければ、開始処理を実行した上で、**開始処理完了後の state に対して本 dispatch および開始処理中に積まれた dispatch を適用する**仕組みである。

つまり、dispatch する UI 側から見れば、Store の開始処理が実際にされている/されていないに関わらず、`dispatch()` する時点で「開始処理が終わっている」前提で書ける。
開始処理が遅延しているせいで自分の dispatch が落ちる、という挙動を UI 側に意識させない。

「開始処理中に積まれた dispatch」には、外部（UI 等）からの dispatch だけでなく、**`Plugin.onStart` 内部から発火された dispatch も含まれる**。`Plugin.onStart` は開始処理の一部として走るため、そこから dispatch されたものも同じレールに乗り、開始処理完了後の state に対して適用される。Plugin 作者側も「onStart で dispatch すると消える / 落ちる」といったエッジケースを意識せずに書ける。

### collect / `Store.start()` 起点の場合

開始処理のトリガーが `dispatch()` 以外の場合も同様の見え方になる。

- `state` / `event` の collect が startup を起こす場合（`OnDispatchOrStateCollection`）: collect が開始処理のトリガーになるだけで、利用者から見たときの前提は dispatch 起点と同じ。
- `Store.start()` を明示呼び出しする場合: 明示的に開始処理を走らせるだけで、その後の `dispatch()` / collect の挙動は通常通り。

いずれの場合も「`dispatch()` 時点で開始処理が終わっている前提で書ける」のと同じ要領で、開始処理の内部挙動を意識せずに書ける。

### PendingActionPolicy.ClearOnStateExit との関係

開始処理中に state が変更され、かつ `PendingActionPolicy.ClearOnStateExit` の場合でも、**開始処理中の state class 遷移では本 dispatch および開始処理中の dispatch は削除しない**。

実装上は、`clearPendingActionsOnStateExitIfNeeded()` が `isInitialized == true` のときだけ pending dispatch をクリアするようにしている（[StoreImpl.kt:727](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:727)）。

#### 実装補足

- 現状は collect / `Store.start()` 起点の場合でも、開始処理中の state class 遷移での dispatch 削除は行わないが、もし仮に将来、dispatch 起点のみの挙動とする場合は [StoreImpl.kt:727](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:727) の判定を次のように変更する。
  - `if (pendingActionPolicy == PendingActionPolicy.ClearOnStateExit && !(activeDispatchJob != null && !isInitialized))`
- 仮に将来、開始処理中の state class 遷移で dispatch を落とす要件が出てきた場合、単純に `clearPendingActionsOnStateExitIfNeeded()` の `isInitialized == true` の判定を削除するだけでは足りず、[StoreImpl.kt:194](../../../koma-core/src/commonMain/kotlin/koma/core/StoreImpl.kt:194) の mutex に既に入っている dispatch の onActionDispatched() の実行を防止する必要がある。

### 根拠: 構成フェーズには ClearOnStateExit がそもそも適用されない

Store の lifecycle は 2 フェーズに分けられる。

- **構成フェーズ**: Store 生成から startup 完了まで。初期 state の復元・確定、observer / overrides の固定、最初の `enter {}` 連鎖を含む。`currentState` はこの間、稼働中の state ではなく初期 state を確定する過程の placeholder。
- **稼働フェーズ**: startup 完了以降。state は `dispatch()` でドライブする対象になる。

この区切りは新しいものではなく、`stateSaver.restore()` / `attachObserver()` / `applyOverrides()` がすべて「startup 前のみ許可」になっていることと同じ線。

`ClearOnStateExit` の意図は「**稼働中のある state class を狙って積まれた古い dispatch を、その state を抜けたら捨てる**」である（例: EditPost で積んだ `Save` を、PostList に遷移したら捨てる）。これが成立するのは稼働フェーズだけ。構成フェーズの `Loading → Main` は、利用者がまだ稼働窓に入っていないので「state exit」自体が定義できない。

したがって、構成フェーズの遷移は `ClearOnStateExit` の **例外ではなく適用範囲外**。起点（dispatch / collect / `Store.start()`）に依存しないのも、フェーズ境界だけで決まるため。

### 仕様文（案）

> `PendingActionPolicy.ClearOnStateExit` は **稼働フェーズ**（startup 完了以降）の state class 遷移にのみ適用される。**構成フェーズ**（startup 完了まで）の遷移は初期 state 確定の過程であり、適用対象外。
>
> 利用者から見れば、startup の進行状況によらず `dispatch()` した時点で Store は稼働状態に入っている前提で書ける。

### 補足: handler matching が暗黙の安全弁になる

「構成フェーズが分岐して、想定外の state class に dispatch が着地する」リスクは理屈上ありうるが、Koma の handler matching は `(state class, action class)` ペアでマッチさせており、マッチしなければ **無音で no-op**（[StoreBuilder.kt:124-127](../../../koma-core/src/commonMain/kotlin/koma/core/StoreBuilder.kt:124)）。

そのため、たとえば `Loading → if 認証済 then Home else Login` の分岐 startup 中に `OpenPost` を dispatch しても、`Login` 側に `action<OpenPost>` が無ければそのまま破棄される。事故が起きるのは「同じ action を複数 state class で意図的に handle しており、かつ副作用が state ごとに異なる」場合に限られ、これは利用者が明示的にリスクを引き受けている setup である。

つまり、構成フェーズで `ClearOnStateExit` が働かないことの実害は、handler matching の per-state class 設計によって大部分が吸収されている。

## 補足

- AutoStartPolicy に `Never` や `OnStateCollection` を追加する余地はあるが、優先度は低い。導入する場合の懸念として次がある。
  - `Never`: Store の開始前に dispatch された action を捨てることになる、または例外を投げることになる。いずれも UI 側の負担が増える。
  - `Never`: start 忘れに気づきづらい。
  - `OnStateCollection`: `dispatch()` したのに start しないという挙動が直感に反しやすい。

## 未解決事項

- 上記「開始処理中の dispatch は ClearOnStateExit から除外する」挙動は、README やリファレンス側で明示されていないため、ドキュメント側に補記するかは要検討。
- `AutoStartPolicy.Never` を将来追加する場合、start 前 dispatch の扱い（例外 / 破棄 / キュー保持）に加え、startup 中の dispatch を queue するか、startup 中の state 変更を `ClearOnStateExit` の対象とするかなど、いくつか論点を整理する必要がある。
