# CarbonCore

Minecraft Paper 服务端综合玩法插件：银行（含贷款与信用评分）、红包、祈愿、任务四合一。
Java 玩家使用箱子 GUI，Geyser 基岩玩家自动切换原生表单（SimpleForm / ModalForm / CustomForm），两端共用同一套业务逻辑。

A multi-module gameplay plugin for Paper servers: Bank (with loans & credit score), Red Packet, Wish (gacha) and Quest.
Chest GUI for Java Edition players, native Bedrock forms for Geyser players — one business core, two UIs.

## 功能 / Features

- **银行 Bank** — 存款 / 取款 / 转账 / 余额、定期利息（利率与周期可配）、管理员调账；
  **贷款 Loans** — 基于信用评分（300–850）的额度与利率派生、按期自动扣款、逾期利率上浮、违约惩罚与修复。
- **红包 Red Packet** — 余额发红包（总额 / 份数 / 时长）、随机领取保底不为零、防重复领取、过期自动退回、重启恢复、口令红包。
- **祈愿 Wish** — 奖池 / 权重 / 保底全部配置化，单发与十连，发放物品 / 货币 / 指令，抽取历史可查。
- **任务 Quest** — 击杀 / 采集 / 钓鱼 / 登录 / 交易 / 完成次数六类任务，每日 / 每周重置，事件驱动进度，GUI 与表单展示进度条并发放奖励。
- **双端 UI** — 玩家选择页采用头颅纹理按钮（缓存 + 进服异步预热，最终一致策略）。
- **本地化** — `messages.yml` 中 / 英文全文案集中管理。

## 技术栈 / Stack

Paper API (compileOnly) · Geyser `geyser-api` + Cumulus 表单（经 `GeyserApi.sendForm`）· Floodgate API · Vault Economy · Bukkit Permission · PlaceholderAPI · SQLite + HikariCP（全部 DB 操作单线程异步，不阻塞主线程）。

## 构建 / Build

```
./gradlew build
```

产物：`build/libs/CarbonCore-<version>.jar`。需要 JDK 25（Paper 26.2 运行时要求）。

## 安装 / Install

1. 将 jar 放入服务端 `plugins/`。
2. 必装 [Vault](https://www.spigotmc.org/resources/vault.34315/) + 任一经济插件（如 EssentialsX）。
3. 按需安装 Geyser/Floodgate（基岩表单）、PlaceholderAPI、LuckPerms。
4. 启动后自动生成 `plugins/CarbonCore/{config.yml, messages.yml, quests.yml}` 与 SQLite 数据库。
5. `/carboncore reload` 热重载配置。

## 命令 / Commands

`/bank`（含 `loan apply|repay|info|score|history`）、`/redpacket`、`/wish`、`/quest`，别名与开关见 `config.yml`；权限节点见 `src/main/resources/plugin.yml`。

## 致谢 / Acknowledgements

- 头颅箱子 UI 的缓存 + 异步预热模式参考了 [TRCStudioDean/LiteSignIn](https://github.com/TRCStudioDean/LiteSignIn)（MIT）。
- 贷款逾期利率上浮与到期滚动模型参考了 [DemonZ-Development/DZTradeHub](https://github.com/DemonZ-Development/DZTradeHub) 的 BankLoan 设计。

## 许可 / License

    Copyright (C) 2026 Chaihu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

完整条款见 [LICENSE](LICENSE)（GPL-3.0-or-later）。
