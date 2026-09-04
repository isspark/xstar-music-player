# xstar-relay（一起听 · 自建公网中转）

让「一起听」跨互联网可靠工作的自建中继服务端。它**只做字节搬运**：把房间内两台手机的控制消息在两人之间转发，并把成员播放器的 HTTP 媒体请求（音频流/封面）隧道到房主手机本机的 `LocalMusicServer` 上，不解析、不落盘、不保存任何音乐内容。

> 与“第三方中转”无关：这是你自己的 VPS，只负责寻址与转发，音乐文件始终只存在于两台手机本地。

## 原理

一个 TCP 端口承载三类连接，通过首行文本区分：

| 类型 | 首行 | 作用 |
|---|---|---|
| 控制 | `CTRL <code> host\|member <nickname>` | 加入房间；配对后双方后续字节原样互转（复用 App 的 JSON 帧协议） |
| 隧道 | `TUNNEL <code>` | 房主手机连上后保活；房主 pump 把 HTTP 响应按 `[int32 len][chunk]` 分帧流式写回，零长度帧表示结束 |
| 媒体 | `GET /room/<code>/stream?t=..` 或 `/art?t=..` | 服务器把该请求转发给房主隧道，再把分帧响应流式回给成员 |

- 每房间限两人（host + member），第三方加入被拒绝。
- 房间 35s 内无人配对自动清理；任一方断开即结束会话并通知对方（断开 TCP 即触发 App 现有断线逻辑）。
- 媒体请求按房间串行处理，避免同一隧道上响应错位。

## 部署

在 VPS 上（需 Go ≥ 1.19）：

```bash
git clone <你的仓库> && cd music_player/server
sudo bash install.sh        # 默认端口 9301；或 sudo bash install.sh 9400
```

脚本会：编译单二进制 → 安装到 `/opt/xstar-relay/xstar-relay` → 写 systemd 服务并开机自启。

放行端口（二选一）：

```bash
ufw allow 9301/tcp
# 或
firewall-cmd --permanent --add-port=9301/tcp && firewall-cmd --reload
```

查看日志 / 状态：

```bash
systemctl status xstar-relay
journalctl -u xstar-relay -f
```

## App 侧对接

在 XSTAR「设置 → 一起听 → 公网中转服务器」中：

1. 开启“使用公网中转服务器”；
2. 填写你的 VPS 地址，如 `1.2.3.4:9301`。

两端都配置一致后，“跨网多路径”连接会自动把服务器作为中转候选；**服务器不可达时 App 仅提示，不会自动回退到 UPnP 通道**。

## 本地自测（无 Android 也可）

```bash
cd server && go build -o xstar-relay . && ./xstar-relay -listen=:9301
# 另开两个终端：
#  终端1(房主): echo "CTRL ABC123 host 手机A" | timeout 60 nc 127.0.0.1 9301
#  终端2(成员): echo "CTRL ABC123 member 手机B" | timeout 60 nc 127.0.0.1 9301
# 在终端1输入 hello，终端2应能收到。
```

## 注意事项

- 本期使用明文 TCP（与 App 现有 cleartext 配置一致）；需要 TLS 时可在前方加 Nginx/Caddy 反代，再让 App 使用域名与 wss 连接（后续版本支持）。
- 房间码有效期很短且仅作配对，不提供鉴权；请勿公开房间码。
- 若想更省 VPS 带宽，可在 App 端先尝试“局域网直连 / UPnP 公网映射”成功时不经服务器（当前“跨网多路径”默认顺序：局域网 → 服务器）。
