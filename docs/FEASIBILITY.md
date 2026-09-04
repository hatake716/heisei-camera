# 実現性と設計判断

確認日: 2026-09-05

`0.3.0` は、背面カメラのライブプレビューでシャッターを押すと、現在地を取得して付近の Street View を表示する構成です。表示は Maps Embed API を WebView の iframe に埋め込みます。結果画面は「現在地の Street View」として扱い、最古の画像や平成の画像を自動取得したとは表示しません。

## 採用する動作

| 項目 | 動作 |
| --- | --- |
| 起動時 | 背面カメラのライブプレビューを表示し、現在地からの自動取得を標準のモードにする。 |
| 自動取得のシャッター | 必要な位置権限を確認し、前景で GPS・ネットワークから位置を取得する。 |
| 自動取得の結果 | 取得した緯度・経度を Embed の `location` に渡し、Google がその地点付近で選んだ画像を表示する。 |
| 測位の失敗 | 権限拒否、位置設定の無効、測位失敗を案内し、設定変更や再試行の導線を出す。別の場所の画像へ自動で代替しない。 |
| 手動の画像選択 | Google マップで選んだ画像の共有リンクを「風景を選ぶ」から取り込み、既知の `pano` を指定する。 |
| しおり | 手動で取り込んだパノラマ ID を保存する。自動取得した結果の画像 ID や構図は保存できない。 |
| 戻る | Street View からカメラプレビューへ戻る。 |

カメラ映像から場所を推定する処理、端末の向きに画像を追従させる処理はありません。Google の撮影地点は、利用者の現在地から離れている場合があります。座標が合っていても、撮影高さや車道・歩道の違いまで一致するものではありません。

## 現在地の取得と保持

現在地の検索に必要なときに Android の位置情報権限を求め、GPS・ネットワークの位置プロバイダーを使います。シャッター操作に伴う前景での取得とし、バックグラウンドで継続的に位置を追跡しません。位置設定・権限・電波環境・端末によって、取得の可否や精度は異なります。[Android LocationManager](https://developer.android.com/reference/android/location/LocationManager)

取得した座標は風景を表示する目的で Google へ送ります。自動取得の結果に、以前の手動選択やしおりを現在地として代用しません。以前の手動パノラマ ID は保持しますが、新たな起動の標準モードを現在地からの取得にするため、過去の選択が自動取得を上書きすることはありません。

## 自動取得と撮影履歴の違い

Embed の `location` は、指定座標に近い撮影地点のパノラマを表示します。Google 側で画像が更新されると、同じ座標でも別の画像が選ばれる場合があります。撮影履歴の列挙、特定年への限定、最古の画像を選ぶパラメーターは公開されていません。自動取得した画像の撮影時期は保証しません。[Maps Embed API: Street View](https://developers.google.com/maps/documentation/embed/embedding-map#streetview_mode)

Google マップの「他の日付を見る」は、過去画像がある場所で利用者が画像を選ぶ機能です。実際の過去画像を指定したいときは、この画面で選択し、そのパノラマの共有リンクを取り込みます。[Google マップの過去画像と共有](https://support.google.com/maps/answer/3093484?co=GENIE.Platform%3DAndroid&hl=ja)

撮影年月の独自表示を省いても、履歴一覧や最古の検索ができるようになるわけではありません。アプリは撮影年月の取得・表示・年代による分類を行わず、Google が埋め込み画面へ提供する日付・ロゴ・帰属表示をそのまま表示します。

## 手動選択としおり

Android の共有操作（`ACTION_SEND`）とリンク貼り付けから、公式 Maps URL の `pano` や共有リンクの ID を読み取ります。短縮リンクの転送と内部的な URL 形式の解析は互換処理であり、Google の形式変更により使えなくなる可能性があります。

```text
https://www.google.com/maps/@?api=1&map_action=pano&pano=PANORAMA_ID
```

共有リンクが選択した過去画像の ID を保持しない場合や、Google マップで見られる ID を Embed API が表示できない場合があります。手動で既知の `pano` を開く場合、ID 不明時に別画像へ切り替えるための `location` は付けません。自動取得の `location` と、特定の画像を指定する `pano` は別のモードとして扱います。[Maps URLs: Street View](https://developers.google.com/maps/documentation/urls/get-started#street-view-action)、[Embed の `pano` と `location`](https://developers.google.com/maps/documentation/embed/embedding-map#streetview_mode)

Embed には表示中の画像 ID や構図をアプリへ返す公開 API がありません。そのため、自動取得した現在地の結果から特定の画像のしおりは作りません。手動で取り込んだ既知のパノラマ ID と既存のしおりは保持し、再表示するときは初期の向きで開きます。カメラの向きや構図は保存しません。

## カメラと静止画風の結果

CameraX の Preview を背面カメラに接続します。写真撮影用の ImageCapture や動画録画は使いません。プレビューは端末内の表示にだけ使用し、画像を Google へ送信しません。結果画面へ切り替えるとカメラを解放し、戻るとプレビューを再開します。[CameraX: Preview](https://developer.android.com/media/camera/camerax/preview)

結果は、指による操作を止めたオンラインの Embed です。Google 側の読み込みや進行中の表示アニメーションまで停止する保証はなく、保存された固定フレームではありません。カメラや Street View のピクセルを取得し、画像ファイルを生成する処理はありません。

Google の Geo Guidelines は Street View のスクリーンショットや埋め込み元からの画像の取り出しを認めていません。結果画面でも Google のロゴと帰属表示を遮らずに表示します。[Google Geo Guidelines](https://about.google/brand-resource-center/products-and-services/geo-guidelines/)

## API キーと通信

`MAPS_EMBED_API_KEY` の1つだけを使用します。Google Cloud の API 制限は **Maps Embed API**、アプリケーション制限は **ウェブサイト** に設定します。

Maps Embed API は無料で、短期・長期のリクエスト制限はありません。利用には API キーが必要です。Google Cloud の設定は利用者が行い、他の API の有効化・利用分まで無料になるとは扱いません。[Embed の料金](https://developers.google.com/maps/documentation/embed/usage-and-billing)、[API の設定手順](https://developers.google.com/maps/documentation/embed/get-api-key)

ローカルの HTML は `loadDataWithBaseURL` により `https://appassets.androidplatform.net/heisei-camera/viewer.html` をベース URL として読み込みます。Embed キーの HTTP リファラーには `https://appassets.androidplatform.net/*` を許可し、iframe からのリファラー送信を有効にします。これは Android パッケージ名や署名 SHA-1 による認証ではありません。[Android: ローカル HTML の読み込み](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content#loaddatawithbaseurl)、[Embed: リファラー制限](https://developers.google.com/maps/documentation/embed/embedding-map#referrer-information-and-api-key-restrictions)

`appassets.androidplatform.net` は Android アプリのローカル Web コンテンツで使われる共通オリジンです。同じオリジンを用いる他のアプリと平成カメラを区別する強い制限にはなりません。実キーを使ったこのオリジンからの認証とサンプル画像の表示はエミュレーターで確認済みです。Google Cloud 上の実際の制限設定と、許可外オリジンを拒否する動作は未確認です。一般配布では、管理する専用 HTTPS オリジンから埋め込み HTML を配信する案も検討し、そのオリジンだけを許可した状態で動作を確認する必要があります。API 制限を Embed のみに限定し、無制限のキーにはしません。

## 受け入れ確認

新しい版では、カメラと位置権限、シャッター時の測位、測位失敗時の案内、取得座標による Google 画像の表示、カメラへの復帰を確認します。手動の共有リンク・しおりが維持され、自動取得の結果や再起動時の標準モードと混同されないことも確認します。

旧版での Google 認証・サンプル画像の表示成功は、今回の現在地取得が正しく動く証拠ではありません。エミュレーターの模擬位置と、実機で測った現在地も分けて記録します。具体的な状況は [検証記録](VALIDATION.md) を参照してください。
