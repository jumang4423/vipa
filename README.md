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

手でやるなら、この4つ。

```bash
git clone https://github.com/jumang4423/vipa.git && cd vipa
./install.sh
```

`install.sh` の中身はこれだけ。順に実行される。

1. `hermes-plugin/vipa` を `~/.hermes/plugins/` に置く
2. `hermes plugins enable vipa`
3. `~/.hermes/config.yaml` に有効化の1ブロックを追記する（下記）
4. `hermes gateway restart`

```yaml
platforms:
  vipa:
    enabled: true
```

`platforms:` が既に config.yaml にある人は、その下に `vipa:` を足す（`install.sh` は追記せず、そう表示して止まる）。

`8099` が他の用途で埋まっている場合は、`~/.hermes/.env` に逃がす。

```
VIPA_LISTEN=0.0.0.0:8098
```

確認:

```bash
curl http://127.0.0.1:8099/api/health   # {"ok":true,...}
```

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
