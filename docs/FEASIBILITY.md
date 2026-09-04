# 実現性と設計判断

確認日: 2026-09-05

平成カメラは、起動時に背面カメラのライブプレビューを表示し、シャッターを押すと事前に選んだ過去の Street View を写真のように眺める画面へ切り替える構成です。過去の風景は Maps Embed API を WebView の iframe で表示します。従来の Maps SDK for Android、GPS・ネットワークによる測位、姿勢センサーによる追従は使用しません。

## 採用する体験

| 項目 | 採用する動作 |
| --- | --- |
| 起動時 | カメラ権限を許可した端末で背面カメラのライブプレビューを表示する。 |
| 過去の風景を選ぶ | Google マップの「他の日付を見る」で選び、共有リンクを取り込む。 |
| シャッター | カメラのプレビュー開始後に押すと、選択済みの過去画像の表示に切り替える。未選択時はリンク取り込みへ進む。 |
| 過去の風景 | 閲覧中の向き・拡大率の操作を止めた Embed を表示する。「戻る」でカメラへ戻る。 |
| しおりと再起動 | 選んだパノラマ ID を端末内に保持し、その画像を再び開く。写真や構図は保存しない。 |
| 撮影年月 | 任意の metadata キーで、選んだパノラマの撮影時期を取得し、iframe の外側に表示する。 |
| 最古の平成画像 | 利用者が Google マップで撮影時期を比較して選ぶ。自動選択や最古の保証はしない。 |

背面カメラの画像から場所を特定する認識処理や、シャッターを押した向きと過去画像の向きを合わせる処理はありません。現在のプレビューと結果画面の関連付けは、利用者が同じ場所の過去画像を選ぶことによって行います。

## カメラと静止画風の結果

CameraX の Preview を背面カメラに接続します。写真撮影用の ImageCapture や動画録画は使いません。プレビューは端末内の表示にだけ使用し、画像を Google へ送信しません。結果画面へ切り替えるとカメラを解放し、戻るとプレビューを再開します。[CameraX: Preview](https://developer.android.com/media/camera/camerax/preview)

シャッターを押しても、カメラや Street View のピクセルを取得して画像ファイルを生成する処理はありません。過去画像の結果画面は、操作を止めたオンラインの Embed です。Google 側の読み込みや進行中の表示アニメーションまで停止する保証はなく、保存された固定フレームではありません。

Google の Geo Guidelines は Street View のスクリーンショットや埋め込み元からの画像の取り出しを認めていません。結果画面でも Google のロゴと帰属表示を遮らずに表示します。[Google Geo Guidelines](https://about.google/brand-resource-center/products-and-services/geo-guidelines/)

## Embed API の役割と制約

`streetview` モードにはパノラマ ID の `pano`、初期方位の `heading`、上下角の `pitch`、画角の `fov` を指定できます。一方、公開仕様には、iframe の現在の視点や画像 ID をアプリへ通知する API はありません。本実装は選んだ ID を初期表示で開き、表示方向の操作を提供しません。[Maps Embed API: Street View](https://developers.google.com/maps/documentation/embed/embedding-map#streetview_mode)

しおりと最後の選択の保存はパノラマ ID を対象とし、カメラの向き、構図、拡大率を対象としません。旧版のしおりにあるパノラマ ID は保持しますが、旧 SDK の表示方向は再現しません。再表示するたびに、初期表示から開きます。

## 撮影履歴と共有リンク

Google マップの「他の日付を見る」は、過去画像が存在する地点で撮影時期を手動選択する機能です。Embed API の公開パラメーターには、撮影履歴の一覧取得や最古の画像を指定する条件はありません。[Google Maps Help](https://support.google.com/maps/answer/3093484?hl=en-GB)、[Embed API の仕様](https://developers.google.com/maps/documentation/embed/embedding-map)

Android の共有操作（`ACTION_SEND`）とリンク貼り付けを入口にします。公式 Maps URL の `pano` や、共有リンクの `!1s` 形式などからパノラマ ID を読み取ります。短縮リンクの転送と内部的な URL 形式の解析は互換処理であり、Google の形式変更により使えなくなる可能性があります。

公式のパノラマ指定 URL は次の形です。

```text
https://www.google.com/maps/@?api=1&map_action=pano&pano=PANORAMA_ID
```

共有リンクが過去画像の ID を保持しない場合や、Google マップで見られる ID を Embed API が表示できない場合があります。座標だけの URL を、選んだ過去年を確実に保持したリンクとは扱いません。既知の `pano` を開く Embed リクエストには、ID 不明時に別画像へ切り替えるための `location` を付けません。[Maps URLs: Street View](https://developers.google.com/maps/documentation/urls/get-started#street-view-action)、[Embed の `pano` と `location`](https://developers.google.com/maps/documentation/embed/embedding-map#streetview_mode)

## 撮影時期

撮影時期には、任意設定の Street View Static API metadata を使用します。取得結果は年・年月・欠落のいずれかであり、架空の月日を補いません。「選んだ風景の撮影年月」として iframe の外側に表示し、著作権表示の年やカメラを使用した日付と区別します。[Street View Image Metadata](https://developers.google.com/maps/documentation/streetview/metadata)

表示例は「2008.09」「2008」で、未取得・不明の場合は `----.--` です。metadata が示すのは問い合わせた ID の時期であり、Google の iframe 内を読み取って得た情報ではありません。結果画面のナビゲーションを止めることで、別のパノラマへ移動した後も元の年月を表示し続けることを避けます。

平成は 1989年1月8日から2019年4月30日までです。例えば `2019-04` は平成ですが、`2019` だけでは確定できません。撮影年が古いことと、その地点で利用可能な画像の中で最古であることも区別します。

## API キーと通信

| 設定 | 用途・制限 |
| --- | --- |
| `MAPS_EMBED_API_KEY` | Maps Embed API。API 制限を同 API、アプリケーション制限をウェブサイトにする。 |
| `STREET_VIEW_METADATA_API_KEY` | 任意。Street View Static API metadata 用の別キー。API 制限と Android アプリ制限を設定し、実エンドポイントで拒否動作を確認する。 |

Maps Embed API は無料で、短期・長期のリクエスト制限はありません。利用には API キーが必要です。Google Cloud の設定は利用者が行い、他の API の有効化・利用分まで無料になるとは扱いません。metadata の問い合わせも公式に無償ですが、Embed とは別の API です。[Embed の料金](https://developers.google.com/maps/documentation/embed/usage-and-billing)、[API の設定手順](https://developers.google.com/maps/documentation/embed/get-api-key)、[metadata](https://developers.google.com/maps/documentation/streetview/metadata)

ローカルの HTML は `loadDataWithBaseURL` により `https://appassets.androidplatform.net/heisei-camera/viewer.html` をベース URL として読み込みます。Embed キーの HTTP リファラーには `https://appassets.androidplatform.net/*` を許可し、iframe からのリファラー送信を有効にします。これは Android パッケージ名や署名 SHA-1 による認証ではありません。[Android: ローカル HTML の読み込み](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content#loaddatawithbaseurl)、[Embed: リファラー制限](https://developers.google.com/maps/documentation/embed/embedding-map#referrer-information-and-api-key-restrictions)

`appassets.androidplatform.net` は Android アプリのローカル Web コンテンツで使われる共通オリジンです。同じオリジンを用いる他のアプリと平成カメラを区別する強い制限にはなりません。現在のキー設定は開発時の設定案であり、実キーでの動作は未検証です。一般配布では、管理する専用 HTTPS オリジンから埋め込み HTML を配信する案も検討し、そのオリジンだけを許可した状態で動作を確認する必要があります。API 制限を Embed のみに限定し、無制限のキーにはしません。

metadata 通信では `X-Android-Package` と `X-Android-Cert` に、実際のパッケージ名と署名証明書 SHA-1 を送ります。Google は古い REST エンドポイントで Android アプリ制限が十分に対応していない場合があると説明しています。正しい識別情報での成功と、誤った識別情報での拒否を確認してください。対応しない場合は認証付きサーバー経由の構成が必要です。[Google Maps Platform security guidance](https://developers.google.com/maps/api-security-best-practices#secure-direct-mobile-web-service-calls)

## 受け入れ確認

カメラ権限、ライブプレビュー、シャッターによる画面遷移、結果画面でのカメラ停止、戻る操作によるプレビュー再開を確認します。実キーを使う検証では、選んだ過去画像と Embed の一致、初期表示と Google 帰属表示、しおり再表示、日付とキー制限を別途確認します。

キー未設定・検証用 ID の画面テスト、HTML の読み込み完了、ビルドの成功は、Google の実画像の表示成功を意味しません。具体的な確認状況は [検証記録](VALIDATION.md) を参照してください。
