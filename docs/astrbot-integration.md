# AstrBot 接入说明

QQBind 只负责服务端拦截与 RCON 命令，群消息 → 验证码的接线在 AstrBot 侧完成。下面以 [astrbot_plugin_minecraft_queqiao](https://github.com/XTsat/astrbot_plugin_minecraft_queqiao)（鹊桥插件）为例。

## 前提

1. AstrBot 已安装并能正常收发 QQ 群消息；
2. MC 端已安装 QueQiao 并连上 AstrBot 的鹊桥插件；
3. MC 端 `server.properties` 开启 RCON（`enable-rcon=true`、`rcon.password=...`）；
4. QQBind 插件已安装，`/qqbind verify` 可在服务器控制台执行。

## 绑定消息处理

在鹊桥插件的消息监听里拦截以下三种写法（都指向同一个验证码）：

```text
绑定 123456
绑定123456
123456
```

匹配后通过 RCON 执行：

```text
qqbind verify 123456 <发送者QQ号>
```

插件返回 `OK:绑定成功 玩家=Steve QQ=... 列表=Steve,Alex` 时回复用户并同步群名片；返回 `FAIL:...` 时把原因回给用户。

参考实现（节选）：

```python
    _BIND_VERIFY_RE = re.compile(r"^(?:/)?(?:绑定\\s*[:：]?\\s*)?(\\d{4,8})$")

    async def _try_handle_qq_bind(self, event, targets, text: str) -> str | None:
        match = self._BIND_VERIFY_RE.fullmatch(text.strip())
        if match is None:
            return None
        code = match.group(1)
        qq = str(event.get_sender_id() or "").strip()
        instance = self.server_manager.get(servers[0])
        output = await instance.execute_command(f"qqbind verify {code} {qq}")
        result = str(output).strip()
        if result.startswith("OK:"):
            detail = result[3:].strip()
            players = self._extract_bind_list(detail)
            if players:
                await self._sync_group_card(event, players)
            clean = detail.split(" 列表=")[0]
            return f"✅ {clean}。请重新进入服务器。"
        if result.startswith("FAIL:"):
            return f"❌ {result[5:].strip()}"
        return f"ℹ️ {result}"
```

> 上面的回复清洗一行请按实际实现处理：内部字段 `列表=...` 不要回给用户，取 `列表=` 之前的部分即可。

## 群昵称同步

绑定成功后把群名片改成该 QQ 名下的全部游戏 ID（英文逗号分隔）；解绑到空时用 QQ 昵称恢复。

```python
    async def _sync_group_card(self, event, players: list[str]) -> None:
        group_id = str(event.get_group_id() or "").strip()
        user_id = str(event.get_sender_id() or "").strip()
        if not group_id or not user_id:
            return
        if players:
            card = ",".join(players)
        else:
            card = str(event.get_sender_name() or "").strip()
        if not card:
            return
        if len(card) > 30:
            card = card[:30]
        bot = getattr(event, "bot", None)
        await bot.call_action(
            action="set_group_card",
            group_id=int(group_id),
            user_id=int(user_id),
            card=card,
        )
```

注意：

- `set_group_card` 传空字符串在部分 OneBot 实现（如 SnowLuma）会报 `OIDB error 1007`，所以解绑到空时要传 QQ 昵称而不是空串；
- 机器人需要是群管理员才能修改**其他成员**的群名片；
- 群名片长度有限，超过 30 字符请截断。

## 解绑

机器人在群里识别 `解绑 <游戏ID>`（也兼容 `解绑<游戏ID>`、`解除绑定 <ID>`），通过 RCON 执行：

```text
qqbind unbind <游戏ID>
```

返回 `OK:已解绑 玩家=... 原QQ=... 列表=剩余绑定的游戏ID`，机器人可以据此决定是否同步群名片（仅当被解绑的账号属于操作者本人时）。