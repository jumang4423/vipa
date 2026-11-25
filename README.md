# vipa

ガラホ（KYF39）を「何もできない端末」にする。

AI は家で働き、外には判定を通ったものだけが届く。

## 必要なもの

- **KYF39**（メルカリで 2〜5 千円。赤ロムでないこと）
- **Mac**（常時起動）
- **au 系の SIM**（povo など。docomo は SIM ロックで弾かれる）
- **ngrok** のアカウント（無料。固定ドメインが 1 本つく）

## インストール

```bash
# 1. Hermes
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash

# 2. これ
git clone https://github.com/jumang4423/vipa.git
cd vipa

# 3. プラグイン
cp -R hermes-plugin/vipa ~/.hermes/plugins/

# 4. 起動（プラグインが 8099 で待つ）
export VIPA_SECRET="好きな文字列"
export GROQ_API_KEY="音声を使うなら"
hermes gateway start

# 5. 外から届くようにする
ngrok http 8099
```

6. KYF39 を USB で挿し、エージェントに言う。

```
vipa のアプリを入れて
```

7. SIM を挿し、APN に `povo.jp` を設定する。

以上です。

## しくみ

```
[外]  KYF39（アプリは何もできない）
        ↕  ngrok
[家]  hermes-plugin/vipa（画面・受信・送信・音声）
        ↕
[家]  Hermes（考える・道具・定期実行）
```

サーバーは要りません。**プラグインがサーバーです。**

- 端末は 1 枚の画面。開けば今日の分、閉じれば沈黙
- 返答はエージェントが返す。判定は `criteria` ではなく **SOUL（人格）** に書く
- 定期実行は Hermes の cron から `deliver=vipa` で届く

## フォルダ

| | |
|---|---|
| `app/` | 端末アプリのソースと APK |
| `hermes-plugin/vipa/` | Hermes の platform プラグイン（サーバー本体） |

## 制約

- タッチパネルなし。4 つのキーで操作する
- 画面は 480×854。1 行 26 文字が上限
- 電池は 1 日
- Android 5.1。GMS なし。FCM は使えない
- プラグインは骨組み。実機での動作確認はまだ

## ライセンス

MIT
