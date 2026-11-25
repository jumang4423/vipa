# vipa

ガラホ（KYF39）を、**外では何も起きない端末**にする。

家の AI が判断し、端末は扉ひとつ。画面は APK の中、頭は Hermes の中。
あいだに別のサーバーは無い。

```
KYF39（アプリ）  ⇄  Hermes ＋ vipa plugin（1プロセス）
```

## 入れ方

Hermes にこう言う。

```
https://github.com/jumang4423/vipa を読んで、入れて
```

手でやるなら2行。

```bash
git clone https://github.com/jumang4423/vipa.git && cd vipa
./install.sh
```

`install.sh` は、plugin を `~/.hermes/plugins/` に置き、`hermes plugins enable vipa` を実行し、`8099` が応答するか確かめる。端末が USB で挿さっていれば、APK を入れて接続先を書いて起動する。

## 端末

```bash
adb install -r app/vipaos.apk
echo 'http://192.168.2.101:8099' > /tmp/vipa.url   # 外から使うなら ngrok のURL
adb push /tmp/vipa.url /sdcard/vipaos.url
adb shell am start -n os.vipa.kiosk/.MainActivity
```

接続先は `/sdcard/vipaos.url` から読む。APK を焼き直さなくてよい。

## しくみ

| | |
|---|---|
| `POST /api/voice` | 録音 → Groq で文字起こし → エージェント |
| `POST /api/in` | 文字 → エージェント |
| `GET /api/out?since=N` | 新しい返答を取る。**端末が5秒ごとに叩く** |
| `GET /api/health` | 生死 |

受信は即 `202` を返し、エージェントは裏で走る（1ターンは数分かかる）。
返答は箱に積まれ、端末が次のポーリングで拾う。push は使わない（GMS が無い）。

画面を見ていないときは、通知（`PollReceiver`）が同じ箱を5秒ごとに見て、新着だけ通知する。

## フォルダ

| | |
|---|---|
| `app/` | 端末アプリ（Java 3本＋`assets/index.html`＋APK） |
| `hermes-plugin/vipa/` | Hermes の platform プラグイン（`adapter.py` だけ） |
| `install.sh` | 上の導入 |

## 制約

- タッチパネルなし。キー4つ（左上=録音、下段=一番下へ、十字=スクロール）
- 480×854、1行26文字
- Android 5.1、GMS なし → push 不可、ポーリングのみ
- 電池1日

## ライセンス

MIT
