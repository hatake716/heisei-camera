# 実現性と設計判断

確認日: 2026-09-05

平成カメラは、Google の過去の Street View を見る体験を優先します。Google マップで利用者が選んだ過去画像を共有リンクから受け取り、Maps SDK for Android で表示し、端末の向きに追従させる設計です。過去画像の自動選択と画像ファイル保存を、利用可能な公式機能であるかのようには扱いません。

## 要件と採用する動作

| 初期要件 | 採用する動作・確認結果 |
| --- | --- |
| 現在地を GPS と電波から算出 | アプリ表示中だけ `LocationManager` の GPS / NETWORK を使用する。 |
| Google に登録された最古の平成画像 | Google マップの「他の日付を見る」で利用者が選ぶ。公開 API から全履歴を取得して最古を保証する経路は確認できない。 |
| 撮影日を重ねて表示 | 実際に SDK が表示したパノラマ ID の metadata を問い合わせ、得られた年・年月をその精度のまま表示する。 |
| 端末のカメラ方向に追従 | 背面方向を姿勢センサーから求め、Street View の方位・上下角に反映する。カメラ映像は取得しない。 |
| 歩きながら過去の風景を見る | 選択した過去画像を維持する。移動しただけで別の撮影時期に置き換えない。次の地点の過去画像は再選択する。 |
| カメラ風 UI のシャッター | パノラマ ID と表示方向をブックマークする。 |
| 日付入りスクリーンショット保存 | 採用しない。Google の Geo Guidelines は Street View のスクリーンショットと埋め込み元からの画像の取り出しを禁止している。 |

スクリーンショットに Google のロゴや撮影時期を残すだけで保存が許可される、という条件は公式資料にありません。ブックマークにも画像データは含めません。[Google Geo Guidelines](https://about.google/brand-resource-center/products-and-services/geo-guidelines/)

## 撮影履歴と共有リンク

Google マップの「他の日付を見る」は、場所ごとの過去の Street View を利用者が選択するための機能です。過去画像がない場所では利用できません。この画面が提供されていることと、第三者アプリ向けの履歴一覧 API があることは別です。[Google Maps Help](https://support.google.com/maps/answer/3093484?hl=en-GB)

Android SDK の公開機能は、緯度経度・半径・屋外指定から適切なパノラマを検索する方法と、既知のパノラマ ID を指定する方法です。隣接パノラマへのリンクもありますが、同じ地点の歴代撮影一覧ではありません。確認した公開仕様には、撮影時期の検索条件や「最古」の指定はありません。[Maps SDK for Android: Street View](https://developers.google.com/maps/documentation/android-sdk/streetview#set_the_location_of_the_panorama)

Street View Tiles の最大 100 地点検索も、各地点に最も近いパノラマをまとめて検索する機能です。同じ地点の 100 時点を返す機能ではありません。JavaScript API にも、今回必要な履歴一覧の公開仕様は確認できませんでした。[Street View Tiles](https://developers.google.com/maps/documentation/tile/streetview)、[JavaScript Street View Service](https://developers.google.com/maps/documentation/javascript/reference/street-view-service?hl=en)

アプリは Android の共有操作（`ACTION_SEND`）またはリンク貼り付けを入口にします。公式 Maps URL の `pano` と、共有リンクで使われることがある `!1s` 形式などから画像の ID を読み取ります。短縮リンクの転送や Google マップの内部的な URL 表現は、公開された履歴取得 API ではなく、形式変更の影響を受ける互換処理です。

公式 Maps URL の例は次の形です。

```text
https://www.google.com/maps/@?api=1&map_action=pano&pano=PANORAMA_ID
```

公式 URL には `viewpoint` による座標指定もありますが、画像 ID が見つからなければ座標付近の別画像に切り替わる場合があります。そのため、座標だけのリンクを「過去年を保持したリンク」とは扱いません。`pano` の値には `F:` で始まる形式などもあり、固定長の英数字と決めつけることもできません。[Maps URLs: Street View](https://developers.google.com/maps/documentation/urls/get-started#street-view-action)

共有時に選択した撮影時期がリンクに含まれない場合や、Google マップで見られても SDK では ID を読み込めない場合があります。全ての歴史画像の ID の可用性は保証できません。読み込み後は SDK が返した表示中の ID を基準に撮影時期を取得し、共有前に選んだ年や直前の画像の年を推測して表示しません。

## 撮影時期と平成の判定

Street View Static API の metadata は、撮影時期について `YYYY-MM`、`YYYY`、値の欠落を認めています。画像の撮影時期と、著作権表示の年、アプリを使った日付は区別します。年月まで分かる場合に架空の日を付けることもありません。[Street View Image Metadata](https://developers.google.com/maps/documentation/streetview/metadata)

表示例は「2008.09」「2008」「撮影時期不明」です。平成の期間は 1989年1月8日から2019年4月30日です。例えば `2019-04` は平成ですが、`2019` だけでは平成と確定できません。「最古」は、比較する画像の集合が全て得られて初めて保証できるため、利用者が選んだ画像をアプリが自動的に最古と認定しません。

## 位置と姿勢

現在地は `LocationManager` の GPS / NETWORK プロバイダーから受け取ります。利用できるプロバイダー、測位の精度、更新頻度は端末の位置設定と権限に依存します。アプリが見えている間だけ位置更新を要求し、画像の選択地点と実際の現在地を区別します。[Android LocationManager](https://developer.android.com/reference/android/location/LocationManager)

姿勢は回転ベクトルなどのセンサーから求めます。背面の光軸に当たる端末の `-Z` 方向を基準にし、真北に対する地磁気の偏角を補正します。Street View の `bearing` は真北から時計回り、`tilt` は上下方向で、SDK による表示更新が可能です。北を基準にしないゲーム用回転ベクトルだけでは、実景の方角と対応しません。[Android Position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)、[Android GeomagneticField](https://developer.android.com/reference/android/hardware/GeomagneticField)、[Street View の視点制御](https://developers.google.com/maps/documentation/android-sdk/streetview#set_the_camera_orientation_point_of_view)

この設計で同期するのは主に上下・左右の表示方向です。Street View は撮影地点から見た球面画像なので、利用者の現在地から見た画像に視点を移動できるわけではありません。車道と歩道の位置差、撮影高さ、端末センサーの精度により、近くの物体の位置や建物の重なり方は実景とずれます。この視差は、GPS の改善や方位補正だけでは解消しません。

## API キーと metadata 通信

`local.properties` の設定は用途ごとに分けます。

| 設定 | 用途・制限 |
| --- | --- |
| `MAPS_API_KEY` | Maps SDK for Android。API 制限を同 SDK、アプリ制限を実際の Android パッケージ名と署名証明書にする。 |
| `STREET_VIEW_METADATA_API_KEY` | Street View Static API の metadata。SDK 用とは別キーを用意し、API 制限と Android アプリ制限を設定する。 |

metadata を端末から直接問い合わせる場合、`X-Android-Package` に実際のパッケージ名、`X-Android-Cert` に署名証明書の SHA-1 を送ります。後者はコロンや接頭辞を含まない 40 桁の 16 進数です。Cloud Console の入力欄が要求するコロン区切り表記とは異なります。

Google は、古い REST エンドポイントでは Android アプリ制限が完全に対応していない場合があるとしています。したがって公開前に、正しい識別情報では利用でき、誤ったパッケージ名・証明書ではリクエストが拒否されることを、使用する metadata エンドポイントで確認する必要があります。このリポジトリではキー未提供のため未検証です。制限が機能しない場合、Google の推奨する認証付きプロキシを使用します。制限を解除したキーをアプリへ埋め込む運用には切り替えません。[Google Maps Platform security guidance](https://developers.google.com/maps/api-security-best-practices#secure-direct-mobile-web-service-calls)

Street View 表示と metadata 通信は別々に失敗し得ます。SDK の画像が表示できたことだけで、撮影時期取得や REST キーの制限が正しく機能しているとは判断しません。

## ブックマークと再表示

シャッター形のボタンが保存するのは、パノラマ ID と視点です。画像、スクリーンショット、タイルは保存しません。Google はパノラマ ID が変更・削除される可能性を説明しているため、ブックマークを永久に再表示できるとは保証しません。再表示できない過去画像を、同じ座標の新しい画像に黙って置き換えないことを設計上の原則とします。[Street View Image Metadata](https://developers.google.com/maps/documentation/streetview/metadata)

## 未検証の受け入れ条件

実際の API キーと Google Play 開発者サービスが利用可能な Android 端末で、次を確認する必要があります。

1. 現在地の取得と、その付近の Street View の表示。
2. Google マップで選んだ過去年が共有リンクに残り、SDK で同じ画像を表示できること。
3. 表示中の画像と metadata の撮影時期の一致。欠落時に直前の年月を表示しないこと。
4. 端末をかざした状態での東西南北と上下方向の一致。
5. GPS 更新で選択した過去画像が置き換わらず、現在地操作で明示的に切り替わること。
6. ブックマークの保存・再表示と、取得できない ID の扱い。
7. metadata REST キーのアプリ制限が、不正な識別情報のリクエストを拒否すること。

Google Cloud のアカウント設定、課金の有効化、API キーの発行はこの作業では行っていません。ローカルのビルド・テスト結果は、これらの外部サービスや実機の受け入れ確認を代替しません。
