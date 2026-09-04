# 平成カメラ

現在地から Google ストリートビューの過去の街並みを探し、スマートフォンをかざして眺める Android アプリです。Google マップで過去の撮影時期を選び、その画像の共有リンクを平成カメラで開くと、端末の向きに合わせて表示方向が変わります。画面には、実際に表示している画像の撮影時期を重ねます。

カメラ風の UI を持つ、Street View の閲覧アプリです。シャッター形のボタンは、表示中のパノラマと向きをブックマークします。写真撮影、ライブカメラ、Street View のスクリーンショット保存は行いません。

## 現在の開発状況

Google の過去の画像を閲覧する体験を優先した初期実装です。API キーの発行、Google Cloud の課金設定、アカウント操作は行っていません。キーを使った画像表示、撮影時期の取得、過去年を保った共有リンクの受け渡し、実機での姿勢追従は未検証です。ビルドやローカルテストの結果だけでは、これらの動作確認は完了しません。

デバッグビルド、21 件のユニットテスト、Lint のエラーなしを確認しました。API キー未設定のエミュレーターで画面と共有リンク・位置権限の動作を確認しています。[検証記録](docs/VALIDATION.md)

Google の公開 API には撮影履歴を列挙して最古の画像を指定する機能が確認できないため、最古の平成画像の選択は Google マップ上で手動で行います。初期要件からの変更と公式資料は [実現性と設計判断](docs/FEASIBILITY.md) にまとめています。

## 使い方

1. 位置情報を許可し、現在地付近の Street View を開きます。
2. Google マップで同じ場所の Street View を開き、「他の日付を見る」から過去の撮影時期を選びます。平成の画像を探す場合は、表示される撮影時期を確認します。
3. Google マップの共有から平成カメラを選ぶか、共有リンクをコピーして平成カメラに貼り付けます。
4. 読み込まれた画像の撮影時期を確認し、端末を上下・左右に向けて風景を眺めます。
5. 気に入った眺めはシャッター形のボタンでブックマークします。保存するのはパノラマ ID と表示方向で、画像データは保存しません。

共有されたリンクの形式によっては、選択した過去の画像が含まれないことがあります。Google マップで選んだ年と、平成カメラに表示された撮影時期が一致するかを確認してください。リンクに画像を特定する情報がない場合や、SDK で画像を取得できない場合は、そのリンクから過去の画像を表示できません。

過去の画像を開いた後は、歩いて現在地が変わってもその画像を維持します。「現在地」への切り替えは現在地付近の画像を改めて検索する操作で、平成の画像に限定されません。別の場所の過去を見たい場合は、その場所で撮影時期を選び直します。

## 表示と権限

- 撮影時期は Google が返した精度で表示します。年月があれば年月、年だけなら年を表示し、不明な月日を補いません。
- 位置情報はアプリ表示中に GPS とネットワークの位置プロバイダーから取得します。バックグラウンドの位置追跡は行いません。
- 方角と傾きには端末の姿勢センサーを使います。カメラ権限は使用しません。
- Street View の撮影地点と現在地は異なる場合があります。端末の向きが合っていても、近くの物体の位置や見え方まで実景に重なるものではありません。
- ブックマーク先の画像は Google 側の更新・削除により再表示できなくなる場合があります。

## 開発環境

| 項目 | 内容 |
| --- | --- |
| アプリ名 | 平成カメラ |
| パッケージ | `io.github.hatake716.heiseicamera` |
| 対応 OS | Android 10 以上（minSdk 29） |
| targetSdk | 36 |
| 言語・UI | Kotlin / Jetpack Compose |
| Street View | Maps SDK for Android |
| 撮影時期 | Street View Static API の metadata |
| 現在地 | Android `LocationManager` の GPS / NETWORK |

## ローカル設定

`local.properties.example` を `local.properties` にコピーし、Android SDK の場所と自分の API キーを設定します。`local.properties` は Git に含めません。

```properties
sdk.dir=/absolute/path/to/Android/Sdk
MAPS_API_KEY=YOUR_ANDROID_MAPS_SDK_KEY
STREET_VIEW_METADATA_API_KEY=YOUR_SEPARATE_METADATA_KEY
```

`MAPS_API_KEY` は Maps SDK for Android 用です。Google Cloud で Maps SDK for Android を有効にし、キーの API 制限を同 SDK に、アプリケーション制限を Android アプリに設定します。登録するパッケージ名は `io.github.hatake716.heiseicamera`、署名証明書の SHA-1 は実際にインストールする APK のものを使用します。デバッグ署名と配布用署名は別です。

`STREET_VIEW_METADATA_API_KEY` は Street View Static API の metadata 用の別キーです。同 API を有効にし、API 制限と Android アプリ制限を設定します。アプリはパッケージ名と署名証明書の SHA-1 を HTTP ヘッダーに付けて問い合わせます。Google は REST エンドポイントごとの制限対応確認を求めているため、公開前に**誤ったパッケージ名・証明書のリクエストが拒否されること**を確認してください。対応しない場合は、認証付きサーバーを経由する構成が必要です。設定の詳細は [実現性と設計判断](docs/FEASIBILITY.md#api-キーと-metadata-通信) を参照してください。

Street View の表示には Google Maps Platform のプロジェクトと課金設定が必要です。metadata の問い合わせは公式に無償とされていますが、Dynamic Street View の表示には別の料金体系が適用されます。[Street View の課金説明](https://developers.google.com/maps/documentation/android-sdk/streetview)、[metadata の説明](https://developers.google.com/maps/documentation/streetview/metadata)

## ビルドと検証

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

デバッグ APK の出力先は `app/build/outputs/apk/debug/app-debug.apk` です。Google Play 開発者サービスが利用可能な端末で確認してください。

API キーの設定後に必要な実機確認は、現在地での表示、過去の共有リンクと表示年月の一致、端末の上下・左右の動きとの一致、移動中の過去画像の維持、ブックマークからの再表示です。キー未設定時の画面確認と、Google の実画像を使った動作確認は別々に記録します。

[プライバシーポリシー](docs/PRIVACY.md)
