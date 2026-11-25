# vipa plugin for Hermes

このプラグインが vipa のサーバーです。別にサーバーを立てません。

## 何をするか

- `GET /` — 端末に配る画面（`ui/index.html`）
- `GET /api/stream` — SSE（状態・差分・確定）
- `GET /api/messages` — 履歴（8件ずつ）
- `POST /api/in` — 文字で送る
- `POST /api/voice` — 声で送る（Groq で文字起こし）
- Hermes の `send()` — エージェントの返答を端末へ
- Hermes の cron — `deliver=vipa` で端末へ

## 設定

```bash
export VIPA_SECRET="好きな文字列"     # 任意
export VIPA_LISTEN="0.0.0.0:8099"     # 既定
export GROQ_API_KEY="..."             # 音声を使うなら
```

`~/.hermes/plugins/` に置いて `hermes gateway start`。

## 端末側

`app/vipaos.apk` を入れて、接続先を `http://<MacのIP>:8099/` か ngrok の URL にする。

## 状態

骨組み。`register_platform` / `MessageEvent` / `build_source` / `handle_message` は
ドキュメントに合わせて書いたが、**実機での確認はまだ**。
