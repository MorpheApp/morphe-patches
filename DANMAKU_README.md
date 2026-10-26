# ライブチャット弾幕パッチ（v1）

YouTube Morphe に、ライブ配信のコメントをニコニコ風の弾幕として
動画プレイヤー上に流す機能を追加するパッチです。
このフォルダは `MorpheApp/morphe-patches` をクローンしたものに、
今回の変更だけを加えた作業コピーです。上流リポジトリには送っていません。

## 追加・変更したファイル

| ファイル | 役割 |
|---|---|
| `patches/src/main/kotlin/app/morphe/patches/youtube/video/livestream/LiveChatDanmakuPatch.kt` | パッチ本体。設定画面への追加と、再生情報フック＋プレイヤーオーバーレイフックの配線 |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/playback/livestream/LiveChatDanmakuPatch.java` | 注入ポイント。動画開始・再生時間・オーバーレイ候補を受け取り、弾幕層とポーリングを制御 |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/playback/livestream/DanmakuOverlay.java` | 弾幕レイヤーそのもの。タッチを奪わず、レーン割り当てに従って右から左へ流す |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/playback/livestream/DanmakuLaneAllocator.java` | Android非依存のレーン割り当てロジック（速度・滞留時間・ドロップ判定） |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/playback/livestream/DanmakuMessage.java` | 弾幕1件のデータ（本文・投稿者・スパチャかどうか・ハイライト色） |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/playback/livestream/LiveChatPollingSource.java` | 公開ライブチャットのポーリング取得（後述の制限あり） |
| `extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java` | 設定値の定義とシークバー範囲の登録（変更のみ） |
| `patches/src/main/resources/addresources/values/youtube/strings.xml` | 設定項目の英語表示名（変更のみ） |
| `patches/src/main/resources/addresources/values-ja-rJP/youtube/strings.xml` | 設定項目の日本語表示名（変更のみ） |

パッチは既存の `RememberLiveStreamPositionPatch` と同じ形で作ってあるため、
パッチ一覧への手動登録は不要なはずです（`bytecodePatch` として定義）。

## 動作の流れ

1. ライブ配信を開くと、既存の動画情報フック（`newVideoStarted` / `videoTimeChanged`）
   から「ライブ配信かどうか」と「動画ID」が分かります。
2. 公開チャットの場合、`LiveChatPollingSource` が
   `live_chat` ページから初期 continuation を取り、
   `youtubei/v1/live_chat/get_live_chat` をポーリングして新着コメントを受け取ります。
3. プレイヤーオーバーレイのフックから渡されたコンテナのうち、
   ロックモード以外で最も面積の大きいものに `DanmakuOverlay` を取り付けます。
4. コメントはレーン（行）に割り当てられます。割り当てはニコニコ式で、
   どのコメントも「右端から入って左端から消えるまで」が同じ時間
   （既定10秒、設定で4〜20秒）になるよう、長いコメントほど
   速く流れます。行ごとに最後に流したコメントを覚えておき、新しい
   コメントを入れる前に次の2つを確認します。
   - 前のコメントの右端が、画面の右端より「すき間（24dp）」の分だけ
     左に進んでいるか
   - 新しいコメントの方が速い場合でも、前のコメントが左端に消える
     までに追いつかないか（消える瞬間の位置で判定）
   両方を満たす行がなければ、そのコメントは即座には流さず待たせます。
   200msごとに入れる行がないか再判定し、行が空けば流します。
   5秒待っても入れなければ捨てます。待ち行列は最大50件で、
   それを超えた分は落とします。

## 設定（Morphe設定 → 動画）

| 設定 | 既定値 | 範囲 |
|---|---|---|
| ライブチャット弾幕 | OFF | — |
| 弾幕に投稿者名を表示 | OFF | — |
| スーパーチャットを目立たせる | ON | — |
| フルスクリーンのみ | OFF | — |
| 弾幕の横断時間 | 10秒 | 4–20秒 |
| 弾幕の不透明度 | 100% | 20–100% |
| 弾幕の列数 | 12 | 3–30（プレイヤーの高さ÷列数で文字サイズが決まる。最大行数も兼ねる） |
| 弾幕ボタン | 表示 | プレイヤー上部の操作ボタン。タップで設定ダイアログ、長押しで弾幕ON/OFF。操作ボタンの表示中のみ現れる |

## 実機でのテスト手順

1. Morphe で対応バージョンの YouTube APK を選び、このパッチを含めてパッチする。
2. Morphe設定 → 動画 →「ライブチャット弾幕」を ON にする。
3. 公開ライブ配信を開き、横向きフルスクリーンでコメントが流れるか確認する。
4. 弾幕が出ない場合、Morphe のデバッグログで
   `Danmaku overlay candidate: <名前>` を確認する。
   想定と違うコンテナが選ばれていたら、その名前を教えてもらえれば
   `LiveChatDanmakuPatch.java` で対象コンテナを固定する変更ができる。

## 正直な制限（v1）

- **この環境では実機・実APKでのビルド検証はしていません。**
  確認済みなのは、追加ファイルの括弧バランス、設定キーと文字列キーの一致、
  XMLの well-formedness、git diff の範囲のみです。
  Android SDK / YouTube APK がないため、Gradle ビルドと端末テストは未実施です。
- **チャット取得は公開配信のみです。**
  メンバー限定などログインが必要なチャットは、このポーリング方式では取れません。
  その場合は、アプリ内チャット描画へのフック（`onChatMessage` を用意済み）に
  置き換えるのが正攻法で、弾幕層と設定はそのまま使えます。
- **オーバーレイの選択はヒューリスティックです。**
  「ロックモード以外で最も大きいコンテナ」を選んでいます。YouTube の
  バージョンによっては別コンテナが選ばれる可能性があり、その場合は上記の
  ログから固定するのが確実です。
- **フルスクリーンのみ設定は、横向きかどうかで近似しています。**
  厳密なフルスクリーン状態のフックは未配線です。
- YouTube 側の Web ページ構造が変わると、continuation の抽出が壊れます。
  その場合は `LiveChatPollingSource` の正規表現部分だけの修正になります。
