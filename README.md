# vipa

ガラホ（KYF39）を「何もできない端末」にする。AI は家で働き、外には家の判断を通ったものだけが届く。

## 入れ方（一言）

Hermes にこう言うだけ。

```
https://github.com/jumang4423/vipa を読んで、全部入れて
```

エージェントはこれを読んで、以下を順にやる。

```bash
git clone https://github.com/jumang4423/vipa.git
cd vipa
./install.sh
```

`install.sh` がやること。

1. `hermes-plugin/vipa` を `~/.hermes/plugins/` に置く
2. `VIPA_SECRET` を生成して `~/.hermes/.env` に書く
3. `hermes plugins enable vipa`
4. `hermes gateway start`（プラグインが 8099 で待つ）
5. `curl http://127.0.0.1:8099/api/health` で確認
6. **端末が USB で挿さっていれば** APK を入れて、接続先を `adb push` して起動する

その後、外で使うなら:

```bash
ngrok http 8099
# 出たURLを端末に書く
adb push /tmp/vipa.url /sdcard/vipaos.url && adb shell am start -n os.vipa.kiosk/.MainActivity
```

## 端末側の残り2つ（人間の手）

- **開発者モード＋USBデバッグ**をON（設定 → 端末情報 → ビルド番号を7回）
- **SIMを挿して APN を `povo.jp` に設定**

## しくみ

```
[外] KYF39（アプリは表示だけ）
       ↕ ngrok
[家] hermes-plugin/vipa（HTTP + SSE + 音声）
       ↕
[家] Hermes（考える・道具を使う・定期実行）
```

- 画面はプラグインが配る `ui/index.html`
- 端末は `GET /api/stream` を開きっぱなしにして、差分を受け取る
- 声は `POST /api/voice` → Groq で文字起こし → エージェント
- 返答は `send()` → SSE → 吹き出し
- 定期実行は Hermes の cron から `deliver=vipa`

**サーバーは要りません。プラグインがサーバーです。**

## フォルダ

| | |
|---|---|
| `app/` | 端末アプリのソースと APK |
| `hermes-plugin/vipa/` | プラグイン本体（`adapter.py` がサーバー） |
| `install.sh` | 上の 1〜6 をやる |

## 状態

- 端末アプリ：動く（KYF39 実機で確認済み）
- プラグイン：**骨組み。実機では未確認**

## 制約

- タッチパネルなし。キー4つ
- 480×854、1行26文字
- Android 5.1、GMSなし（FCM不可）
- 電池1日

## ライセンス

MIT
