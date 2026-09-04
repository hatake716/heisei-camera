# 平成カメラ

スマートフォンをかざしてシャッターを押すと、現在地付近の Google Street View へ切り替わる Android アプリです。起動時は背面カメラのライブプレビューを表示し、シャッター時に GPS・ネットワークから現在地を取得します。「現在に戻る」でカメラへ戻ります。

画面は黒と白を基調に、ピンク・オレンジ・紫のアクセントと円形シャッターを組み合わせた、平成カメラ独自のカメラ UI です。

**自動取得するのは現在地付近の Street View です。最古の画像や平成の画像が選ばれる保証はありません。** 見たい過去の画像がある場合は、Google マップで選んだ画像の共有リンクを「風景を選ぶ」から取り込めます。カメラ映像や Street View の写真・スクリーンショットをファイルへ保存する機能はありません。

## 現在地から使う

1. 平成カメラを開き、カメラ権限を許可して背面カメラのライブプレビューを表示します。
2. シャッターを押します。現在地の取得に必要な場合は、位置情報の許可を求めます。
3. GPS・ネットワークから取得した位置を使い、「現在地の Street View」を表示します。
4. 「現在に戻る」でカメラへ戻ります。次のシャッターで現在地を改めて取得します。

位置情報が許可されていない、端末の位置設定が無効、測位できない場合は、案内に従って許可・設定変更・再試行を行います。測位失敗時に、サンプル画像や前に選んだ別の場所を現在地の結果として表示することはありません。

結果は指で向きや拡大率を変えない閲覧用の Street View です。カメラの画像から場所を認識する処理や、カメラの向きに表示方向を合わせる処理はありません。Google の画像は実際の現在地と少し異なる地点から撮られている場合があります。

## 過去の画像を指定する

Google マップで見たい場所の Street View を開き、「他の日付を見る」から画像を選んで共有します。共有先に平成カメラを選ぶか、リンクをコピーして「風景を選ぶ」に貼り付けると、そのパノラマを表示対象にできます。

手動で選んだ画像は、パノラマ ID をしおりに保存できます。写真や撮影時の構図を保存する機能ではありません。自動取得した現在地の結果は、アプリが表示中のパノラマ ID を取得できないため、特定の画像のしおりとしては保存できません。

以前に取り込んだ画像としおりは保持しますが、新たにアプリを起動したときの標準動作は現在地からの自動取得です。保存済みの手動画像が、現在地の結果として勝手に選ばれることはありません。

共有リンクに過去画像の ID が含まれない場合や、その ID を Embed API が表示できない場合があります。Google マップで選んだ時期と表示内容を確認してください。画像の可用性は Google 側の更新・削除によって変わります。[Google マップの過去画像と共有](https://support.google.com/maps/answer/3093484?co=GENIE.Platform%3DAndroid&hl=ja)、[実現性と設計判断](docs/FEASIBILITY.md)

## 現在の開発状況

`0.3.0`（versionCode 4）で、シャッターから現在地付近の Street View を自動取得する構成へ変更しました。ビルド・28件のテスト・Lint が成功し、Android 15 のエミュレーターで位置権限、位置情報オフ、取得中のバックグラウンド移行、異なる2地点での自動表示、縦横の描画、手動リンク・しおりの維持を確認しています。実機の GPS・ネットワークによる測位と、過去画像の撮影時期の確認は別途必要です。[検証記録](docs/VALIDATION.md)

Maps Embed API の利用料は無料で、リクエスト数の制限もありませんが、API キーが必要です。現在地取得には追加の Google API キーは必要ありません。設定方法は下の「ローカル設定」を参照してください。キー未設定の場合は設定案内を表示します。[Google の料金・利用条件](https://developers.google.com/maps/documentation/embed/usage-and-billing)

## 表示とプライバシー

アプリ独自の撮影年月表示は行いません。埋め込み画面に Google が表示する撮影時期や帰属表示は、そのまま表示します。

カメラはライブプレビューだけに使用し、映像を Google に送信したり、写真を記録したりはしません。結果画面ではカメラの使用を止めます。現在地はシャッターによる検索のため、アプリが表示されている間に取得し、風景を表示するための座標として Google へ送信します。バックグラウンドの位置追跡は行いません。マイク・写真へのアクセス権限は要求しません。[プライバシーポリシー](docs/PRIVACY.md)

## 開発環境

| 項目 | 内容 |
| --- | --- |
| アプリ名 | 平成カメラ |
| パッケージ | `io.github.hatake716.heiseicamera` |
| 対応 OS | Android 10 以上（minSdk 29） |
| targetSdk | 36 |
| 言語・UI | Kotlin / Jetpack Compose |
| カメラ | CameraX の背面カメラプレビュー |
| 現在地 | Android の GPS・ネットワーク位置情報 |
| 風景の表示 | Android WebView 内の Maps Embed API iframe |

## ローカル設定

`local.properties.example` を参考に、`local.properties` へ SDK の場所と API キーを設定します。既存の `local.properties` がある場合は SDK 設定を保持し、必要なキーを追記してください。このファイルは Git に含めません。

```properties
sdk.dir=/absolute/path/to/Android/Sdk
MAPS_EMBED_API_KEY=YOUR_EMBED_API_KEY
```

Google Cloud で Maps Embed API を有効にし、`MAPS_EMBED_API_KEY` の API 制限を **Maps Embed API**、アプリケーション制限を **ウェブサイト** に設定します。現在のローカル HTML のオリジンに合わせ、許可する HTTP リファラーは `https://appassets.androidplatform.net/*` とします。必要な API キーはこの1つです。Embed 用キーに Android パッケージ名・署名 SHA-1 の制限を設定する構成ではありません。[Embed API の設定](https://developers.google.com/maps/documentation/embed/get-api-key)、[リファラーの仕様](https://developers.google.com/maps/documentation/embed/embedding-map#referrer-information-and-api-key-restrictions)

`appassets.androidplatform.net` は Android のローカル Web コンテンツで使われる共通オリジンです。このリファラーだけでは平成カメラ固有のアプリ識別になりません。実キーを使い、このオリジンからの認証が通ることは確認しました。Google Cloud 上の実際の制限設定と、許可外オリジンからの拒否動作は未確認です。一般配布前には専用の管理ドメインで HTML を配信する構成を含めて検討し、意図した制限が機能することを確認してください。キーの API 制限は Embed のみに維持します。[Android のローカル Web コンテンツ](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)

## ビルドと検証

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

デバッグ APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。背面カメラ、Android System WebView、ネットワーク接続を利用できる環境で確認してください。カメラ・位置情報の権限、測位、Google の実画像の表示をそれぞれ確認し、エミュレーターの模擬位置と実機の現在地は分けて記録します。
