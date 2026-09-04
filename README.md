# 平成カメラ

スマートフォンをかざして現在の風景を見ながらシャッターを押すと、あらかじめ選んだ過去の Street View に切り替わる Android アプリです。起動時は背面カメラのライブプレビュー、シャッター後は過去の風景を一枚の写真のように眺める画面になります。「戻る」で現在のカメラへ戻ります。

画面は黒と白を基調に、ピンク・オレンジ・紫のアクセントと円形シャッターを組み合わせた、平成カメラ独自のカメラ UI です。

**過去の風景は Google マップで事前に選びます。** カメラで見ている場所・向きを判定したり、現在地に対応する最古の画像を自動検索したりはしません。カメラ映像や Street View の写真・スクリーンショットをファイルへ保存する機能もありません。

## 使い方

1. Google マップで見たい場所の Street View を開き、「他の日付を見る」から過去の撮影時期を選びます。
2. Google マップの共有から平成カメラを選ぶか、共有リンクをコピーして平成カメラへ貼り付けます。最後に選んだ風景は次回起動にも引き継ぎます。
3. 平成カメラでカメラ権限を許可し、背面カメラのプレビューで現在の風景を見ます。
4. シャッター形のボタンを押すと、選んだ過去の風景へ切り替わります。過去画像が未選択の場合はリンクの取り込みへ進みます。
5. 「戻る」でカメラへ戻ります。別の風景も残したい場合は、選んだ風景のリンクをしおりに保存できます。

結果画面の Street View は、指で向きや拡大率を変えない閲覧用の表示です。カメラの向きやシャッターを押した瞬間の構図とは連動しません。しおりに保存するのも選んだパノラマ ID であり、写真や撮影時の構図ではありません。

共有リンクに過去画像の ID が含まれない場合や、その ID を Embed API が表示できない場合があります。Google マップで選んだ時期と表示内容を確認してください。画像の可用性は Google 側の更新・削除によって変わります。[実現性と設計判断](docs/FEASIBILITY.md)

## 現在の開発状況

Street View の表示を Maps SDK for Android から Maps Embed API へ変更し、背面カメラのプレビューから過去の風景へ切り替える構成にしています。GPS・ネットワークによる測位と姿勢センサーの追従は使用しません。

Maps Embed API の利用料は無料で、リクエスト数の制限もありませんが、API キーが必要です。Google Cloud の設定とキー発行は未実施です。キー未設定の場合は過去画像の代わりに設定案内を表示します。確認済み・未確認の範囲は [検証記録](docs/VALIDATION.md) を参照してください。[Google の料金・利用条件](https://developers.google.com/maps/documentation/embed/usage-and-billing)

## 撮影時期とプライバシー

任意の metadata 用キーを設定すると、選んだパノラマの撮影時期を表示します。Google が返した年月・年をそのまま使い、不明な月日を補いません。未取得の場合は `----.--` を表示します。カメラを使った日付ではありません。metadata 用キーがなくても、Embed 用キーがあれば過去の風景を閲覧できます。

カメラはライブプレビューだけに使用し、映像を Google に送信したり、写真を記録したりはしません。結果画面ではカメラの使用を止めます。位置情報・マイク・写真へのアクセス権限は要求しません。[プライバシーポリシー](docs/PRIVACY.md)

## 開発環境

| 項目 | 内容 |
| --- | --- |
| アプリ名 | 平成カメラ |
| パッケージ | `io.github.hatake716.heiseicamera` |
| 対応 OS | Android 10 以上（minSdk 29） |
| targetSdk | 36 |
| 言語・UI | Kotlin / Jetpack Compose |
| 現在の風景 | CameraX の背面カメラプレビュー |
| 過去の風景 | Android WebView 内の Maps Embed API iframe |
| 撮影時期 | Street View Static API の metadata（任意） |

## ローカル設定

`local.properties.example` を参考に、`local.properties` へ SDK の場所と API キーを設定します。既存の `local.properties` がある場合は SDK 設定を保持し、必要なキーを追記してください。このファイルは Git に含めません。

```properties
sdk.dir=/absolute/path/to/Android/Sdk
MAPS_EMBED_API_KEY=YOUR_EMBED_API_KEY
# 任意: 撮影時期を取得する場合のみ設定
STREET_VIEW_METADATA_API_KEY=YOUR_SEPARATE_METADATA_KEY
```

Google Cloud で Maps Embed API を有効にし、`MAPS_EMBED_API_KEY` の API 制限を **Maps Embed API**、アプリケーション制限を **ウェブサイト** に設定します。現在のローカル HTML のオリジンに合わせ、許可する HTTP リファラーは `https://appassets.androidplatform.net/*` とします。以前の `MAPS_API_KEY` は使用しません。Embed 用キーに Android パッケージ名・署名 SHA-1 の制限を設定する構成ではありません。[Embed API の設定](https://developers.google.com/maps/documentation/embed/get-api-key)、[リファラーの仕様](https://developers.google.com/maps/documentation/embed/embedding-map#referrer-information-and-api-key-restrictions)

`appassets.androidplatform.net` は Android のローカル Web コンテンツで使われる共通オリジンです。このリファラーだけでは平成カメラ固有のアプリ識別になりません。実キーでの表示とリファラー制限は未検証です。一般配布前には専用の管理ドメインで HTML を配信する構成を含めて検討し、意図した制限が機能することを確認してください。キーの API 制限は Embed のみに維持します。[Android のローカル Web コンテンツ](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)

`STREET_VIEW_METADATA_API_KEY` は、Street View Static API の metadata にだけ使用する別キーです。Android アプリ制限にはパッケージ名 `io.github.hatake716.heiseicamera` と実際の APK の署名 SHA-1 を登録します。この REST エンドポイントでアプリ制限が有効に働くかは実通信での確認が必要です。詳しくは [API キーと通信](docs/FEASIBILITY.md#api-キーと通信) を参照してください。

## ビルドと検証

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

デバッグ APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。背面カメラ、Android System WebView、ネットワーク接続を利用できる環境で確認してください。カメラ権限・プレビューの確認と、Google の実画像を使う切り替え・再表示の確認は分けて記録します。
