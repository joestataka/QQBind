# QQBind

[![Release](https://img.shields.io/github/v/release/joestataka/QQBind)](https://github.com/joestataka/QQBind/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Minecraft 服务器「进服绑定验证」白名单插件。

未绑定 QQ 的玩家在**登录阶段**就被拦下，客户端断开界面会显示验证码；玩家到 QQ 群发送「绑定 <验证码>」完成绑定后，重新进入即可放行。适合 QQ 群服、朋友服，防止陌生人直接进服。

> 已在 Paper 26.3（Minecraft 26.3 / Java 27）实测通过；更早的 Paper 版本未逐一验证，逻辑只依赖 Bukkit API，理论上 1.20+ 均可使用。

## 特性

- **登录前拦截**：监听 `AsyncPlayerPreLoginEvent`，未绑定玩家进不了服务器世界，也不会触发原生白名单的「不在白名单」提示。
- **断开界面显示验证码**：6 位数字，默认 10 分钟有效；验证码过期后重新进服会刷新。
- **一个 QQ 可绑定多个游戏账号**：绑定列表用英文逗号拼接，支持配置上限（默认 3 个）。
- **自动同步 QQ 群名片**：配合 [AstrBot](https://github.com/AstrBotDevs/AstrBot) + 鹊桥插件，绑定/解绑后自动把群昵称改成绑定的游戏 ID（多个用逗号分隔），解绑到空时恢复 QQ 昵称。
- **管理命令**：`/qqbind verify|unbind|list|status|whois|reload`，可通过 RCON 远程调用。
- **数据持久化**：绑定表保存在 `plugins/QQBind/bindings.yml`。

## 安装

1. 从 [Releases](https://github.com/joestataka/QQBind/releases/latest) 下载 `QQBind-1.0.0.jar`（附 `SHA256SUMS.txt` 校验和），放进服务端 `plugins/` 目录。
2. **关闭原生白名单**：编辑 `server.properties`，设置 `white-list=false`（原生白名单会在登录时先拦截，导致看不到本插件的自定义提示）。
3. 启动服务器，插件会生成 `plugins/QQBind/config.yml`。
4. 在 QQ 机器人侧加「绑定 <验证码>」到 `/qqbind verify` 的接线，详见 [docs/astrbot-integration.md](docs/astrbot-integration.md)。

玩家第一次进服时会被拦下，界面类似：

```text
未绑定社交账号
Steve 进入服务器需要绑定您的社交账号

如您未加群，请先加入 QQ 群：123456789
发送内容：绑定 123456
发送后方可进入服务器

请在 2026/11/23 21:03:11 前完成验证
请勿将验证码发送给其他人
验证超时后需重新进入服务器
```

## 配置

`plugins/QQBind/config.yml`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `code-ttl-seconds` | `600` | 验证码有效期（秒） |
| `max-bindings-per-qq` | `3` | 一个 QQ 最多绑定的游戏账号数 |
| `bypass-players` | `[]` | 直接放行的玩家名（忽略大小写） |
| `bypass-ops` | `false` | OP 是否直接放行 |
| `group-ids` | - | 提示文案里展示的 QQ 群号 |
| `message` | - | 断开界面文案，支持 `§` 颜色码与 `{player}`、`{code}`、`{expire}`、`{group}` 占位符 |

## 命令

| 命令 | 说明 |
| --- | --- |
| `/qqbind verify <验证码> <QQ号>` | 校验验证码并绑定（供机器人 RCON 调用） |
| 群消息 `绑定 123456` / `123456` | 机器人侧触发绑定（需接 AstrBot） |
| 群消息 `解绑 <游戏ID>` | 机器人侧触发解绑（需接 AstrBot） |
| `/qqbind unbind <玩家名>` | 解除绑定 |
| `/qqbind list` | 列出全部绑定 |
| `/qqbind status <玩家名>` | 查询某玩家的绑定状态 |
| `/qqbind whois <QQ号>` | 查询某 QQ 绑定的全部游戏账号 |
| `/qqbind reload` | 重载 config.yml |

绑定成功时 `verify` 会返回类似：

```text
OK:绑定成功 玩家=Steve QQ=123456789 列表=Steve,Alex
```

机器人可解析 `列表=` 后的内容来同步群名片。

## 构建

### 方式一：Maven

```bash
mvn -q package
# 产物 target/QQBind-1.0.0.jar
```

> `pom.xml` 使用 `io.papermc.paper:paper-api:26.3-R0.1-SNAPSHOT`。若该版本在你的仓库不存在，请改用方式二或改成你服务端对应的 paper-api 版本。
>
> ⚠️ **必须用 `--release 21`（或 ≤25）编译**：用 JDK 27 默认目标会生成 class major 71，Paper 自带的 ASM 9.9.1 不支持，启动时报 `Unsupported class file major version 71`。Java 21 字节码在 Java 25/27 运行时完全可用。

### 方式二：用服务端自带的 libraries 编译（无需联网下载依赖）

Paper 启动后会把 API jar 下到 `libraries/`，可直接作为 classpath：

```bash
SERVER=/path/to/server
CP=$(find "$SERVER/libraries" -name "*.jar" | tr "\n" ":")
CP="$CP:$(find "$SERVER/versions" -name "paper-*.jar" | head -1)"
mkdir -p out
javac --release 21 -encoding UTF-8 -cp "$CP" -d out src/main/java/qqbind/QQBindPlugin.java
cp src/main/resources/* out/
jar cf QQBind-1.0.0.jar -C out .
```

## 注意

- 本插件接管进服校验后，**原生白名单请保持关闭**；需要白名单效果由本插件的绑定表提供。
- 离线服务器（`online-mode=false`）同样可用；绑定校验只看「玩家名 -> QQ」，与正版验证无关，建议配合 AuthMe 等登录插件使用。
- 修改配置后可用 `/qqbind reload` 生效。

## 许可

MIT License，见 [LICENSE](LICENSE)。
