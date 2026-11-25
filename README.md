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
https://github.com/jumang4423/vipa を読んで、install.sh を最後まで実行して。途中で止まったら理由を報告して。
```

手でやるなら、これだけ。

```bash
git clone https://github.com/jumang4423/vipa.git && cd vipa
./install.sh                 # 家の中だけで使う
VIPA_NGROK=1 ./install.sh    # 外からも使う
```

`install.sh` は単体で完結する。順にこれをやる。

1. `hermes-plugin/vipa` を `~/.hermes/plugins/` に置く
2. `hermes plugins enable vipa`
3. `config.yaml` に `platforms.vipa.enabled` を入れる（既にあれば触らない／必ずバックアップを取る）
4. `8099` が他プロセスに使われていたら `8098` に逃がす（`~/.hermes/.env` の `VIPA_LISTEN`）。承認（ペアリング）は不要にする（`VIPA_ALLOW_ALL_USERS=1`）
5. gateway を再起動し、`/api/health` が返るまで最大40秒待つ。**再起動は切り離して仕掛ける**（Hermes のセッションの中から実行されると、その場で再起動するとセッションごと落ちるため）。結果は `/tmp/vipa-install.txt` に `ok` か失敗ログが出る
6. 端末が USB で挿さっていれば、APK を入れて接続先を書いて起動
7. `VIPA_NGROK=1` なら ngrok を張り、**その URL** を端末に書く。固定 URL を使うなら `~/.hermes/.env` に1行（ngrok の dev domain）。無いと起動のたびに URL が変わる。

```
VIPA_NGROK_URL=your-name.ngrok-free.dev
```

最後に **`NEXT:` を1行だけ**出す。エージェントはそれをそのまま伝えればいい。

確認:

```bash
curl http://127.0.0.1:8099/api/health    # 逃がした場合は 8098
```

## 端末

通常は `install.sh` がやる。手でやるなら:

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
`adapter.py` は**標準ライブラリだけで動く**（aiohttp 等の追加インストールは不要）。
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
- Android 5.1 は 2015 年の CA しか持たないため、Let's Encrypt（ngrok）の証明書を検証できない。`Tls.java` で自分のサーバーのホストに限って通している
- 電池1日

## ライセンス

MIT
