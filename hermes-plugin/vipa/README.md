# vipa plugin

端末（KYF39）と Hermes をつなぐ口。**UI もサーバーも持たない。**

## エンドポイント

| | |
|---|---|
| `GET /api/out?since=N` | 新しい返答を取る（端末が5秒ごと） |
| `POST /api/in` | 文字を渡す `{"text":"..."}` |
| `POST /api/voice` | 録音を渡す（`audio/mp4`）→ Groq で文字起こし |
| `GET /api/health` | 生死 |

`adapter.py` は**標準ライブラリだけで動く**（追加の pip 不要）。

承認（ペアリング）は不要。`VIPA_ALLOW_ALL_USERS=1` を `install.sh` が入れる。

`GET /api/out` は `{"messages":[{seq,role,text,ts}],"last":N}` を返す。`role` は `user` か `assistant`。
端末は `last` を次の `since` に使う。

## 置き方

```bash
cp -R hermes-plugin/vipa ~/.hermes/plugins/
hermes plugins enable vipa
```

`~/.hermes/config.yaml` に有効化を1ブロック足す（**これが無いと待受が開かない**）:

```yaml
platforms:
  vipa:
    enabled: true
```

`platforms:` が既にあるなら、その下に `vipa:` を足す。

```bash
hermes gateway restart
```

`~/.hermes/.env`（任意）:

```
VIPA_LISTEN=0.0.0.0:8099
GROQ_API_KEY=（音声を使うなら）
```

## 外から届かせる

```bash
ngrok http 8099
```

出た URL を端末の `/sdcard/vipaos.url` に書く。

```bash
echo 'https://xxxx.ngrok-free.app' > /tmp/vipa.url
adb push /tmp/vipa.url /sdcard/vipaos.url
adb shell am start -n os.vipa.kiosk/.MainActivity
```
