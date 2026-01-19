# DivineBanItem

DivineBanItem 是一款面向 Mohist 1.20.1 混合端的物品管控插件，核心用于封禁模组物品与物品变体（NBT 区分），并提供可选的配方移除、配方覆写，以及基于 Vault 经济的付费授权能力。

本插件的主要使用对象：
- 服务器核心：Mohist 1.20.1
- 主要目标：封禁模组物品（Forge 物品）并治理相关配方与使用行为

如果你运行的是纯 Spigot 或 Paper，本插件仍可在“基础模式”下运行，但无法封禁不存在于纯原版环境中的模组物品，也无法保证对 Forge 配方的深度治理效果。

## 核心能力

### 1. 模组物品封禁（重点）
支持按命名空间 ID 封禁物品，例如：
- `minecraft:elytra`
- `botania:specialflower`
- `mekanism:atomic_disassembler`

每条封禁条目可独立选择拦截范围：
- 禁止使用（右键、消耗、交互）
- 禁止放置
- 禁止合成或禁止取出合成结果
- 禁止熔炼及同类设备产出
- 可选：禁止拾取、禁止丢弃

默认策略偏开放：尽量不影响玩家携带与存储，仅拦截条目定义的关键行为，减少公益服管理摩擦。

### 2. NBT 级别识别（用于变体封禁）
解决“同一物品 ID 不同 NBT 代表不同变体”的场景，例如不同的花、不同的数据组件、不同附魔书等。

支持的 NBT 匹配模式：
- `ANY`：只按物品 ID 匹配
- `EXACT_SNBT`：SNBT 完全相等
- `CONTAINS_SNBT`：SNBT 字符串包含
- `PATH_EQUALS`：按路径取值对比（推荐），例如 `BlockEntityTag.SomeKey`

### 3. 可选移除合成表
对封禁物品可选择是否删除其合成表，提供两层治理：
- 基础层：移除 Bukkit 可见配方
- 深度层（Mohist 优先）：尽可能从底层 RecipeManager 移除输出为目标物品的配方，用于覆盖 Forge 模组配方

说明：
- 深度移除属于 best effort 行为，混合端对配方暴露与底层结构可能随版本变化。
- 插件会输出移除统计与失败原因，便于升级整合包后回归测试。

### 4. 配方覆写
支持通过配置文件对指定物品的配方进行覆写：
- 移除目标输出的原配方
- 注册新配方
- 支持模组物品作为输出与材料
- 支持精确材料（ExactChoice）与带 NBT 的材料

### 5. 付费授权（Vault 经济）
对封禁条目支持“购买许可后放行”：
- 每条条目可独立开启购买
- 支持永久许可与限时许可并存（条目级配置）
- 支持仅放行使用，合成仍保持封禁，或一并放行合成（按条目配置）
- 经济依赖 Vault，兼容 RoyaleEconomy 等经济实现

若未安装 Vault，本功能自动禁用，其余封禁与配方功能不受影响。

### 6. 查询物品 NBT
提供命令输出手持物品的命名空间 ID 与 NBT（SNBT），用于快速编写规则：
- 输出 `namespace:item_id`
- 输出 SNBT
- NBT 过长时截断聊天输出，并将完整内容输出到控制台日志

## 兼容性与运行环境

推荐环境：
- Mohist 1.20.1
- Java 17

基础模式：
- Spigot 1.20.x 可以运行，但仅对原版物品与 Bukkit 可见配方有效。
- “封禁模组物品、深度移除 Forge 配方、覆写模组配方”在纯 Spigot 环境没有意义或无法保证效果。

可选依赖：
- Vault（启用付费授权）

## 安装

1. 将插件 jar 放入 `plugins/`
2. 启动服务器生成配置
3. 如需付费授权，安装 Vault 与经济插件
4. 修改配置后执行重载命令或重启

## 命令

主命令：`/dbi`（可配置别名）

- `/dbi help` 查看帮助
- `/dbi reload` 重载配置与语言
- `/dbi nbt` 输出手持物品 key 与 NBT
- `/dbi list` 列出封禁条目 key
- `/dbi info <key>` 查看条目详情
- `/dbi buy <key> [duration]` 购买许可  
  - `duration` 缺省表示购买永久许可  
  - 示例：`/dbi buy botania_flower 7d`
- 管理员：
  - `/dbi banhand` 将手持物品写入封禁条目（默认 `ANY` NBT 匹配）
  - `/dbi gui` 打开封禁 GUI，可批量放入物品后一键保存
  - `/dbi grant <player> <key> [duration]`
  - `/dbi revoke <player> <key>`
  - `/dbi grant` / `/dbi revoke` 仅支持在线玩家目标（离线授权可直接编辑 licenses.yml）

## 权限

- `divinebanitem.use` 基础命令
- `divinebanitem.reload` 重载
- `divinebanitem.nbt` NBT 查询
- `divinebanitem.buy` 购买许可
- `divinebanitem.admin` 管理员命令
- `divinebanitem.bypass.*` 绕过封禁（仅管理组）

说明：
- `divinebanitem.bypass.*` 将绕过 use/place/craft/smelt/pickup/drop 全部拦截。
- `/dbi nbt` 与 `/dbi reload` 默认仅 OP。
- `/dbi buy` 仅当条目开启购买且安装 Vault 才有效。

## 配置结构（概念说明）

配置按“条目”管理，每条条目包含：
- `item`：物品 key 与 NBT 规则
- `actions`：use/place/craft/smelt/pickup/drop 开关
- `recipes`：是否移除配方，是否启用深度移除
- `purchase`：是否可购买、永久价格、限时档位（duration+price）
- `licenseEffect`：许可放行范围（仅 use 或包含 craft 等）

你可以自行添加条目与配方覆写，本仓库不提供固定模板，避免限制你的整合包玩法。

## 管理 GUI 说明

管理员可使用 `/dbi gui` 打开封禁 GUI：
- 将需要封禁的物品放入 GUI 中（支持多件）
- 点击“Save ban entries”按钮或直接关闭界面，即可生成封禁条目
- 保存后物品会返还到玩家背包（空间不足将掉落在脚下）
- 默认以 `ANY` 作为 NBT 匹配模式，可在配置中手动调整为 `PATH_EQUALS` 等模式

### 配置示例

```yml
entries:
  botania_flower:
    item:
      key: botania:specialflower
      nbtMode: PATH_EQUALS
      nbtPath: BlockEntityTag.flower
      nbtValue: botania:manastar
    actions:
      use: true
      place: true
      craft: true
      smelt: false
      pickup: false
      drop: false
    recipes:
      removeBukkit: true
      removeForge: false
    purchase:
      enabled: true
      price: 20000
      durations:
        - duration: 7d
          price: 5000
        - duration: 30d
          price: 12000
      licenseEffect:
        allowUse: true
        allowCraft: false

recipeOverrides:
  - key: custom_elytra
    type: SHAPED
    result:
      key: minecraft:elytra
      amount: 1
    shape:
      - "ABA"
      - "CDC"
      - "ABA"
    ingredients:
      A:
        key: minecraft:phantom_membrane
      B:
        key: minecraft:netherite_ingot
      C:
        key: minecraft:diamond
      D:
        key: minecraft:nether_star
```

### duration 格式说明

`duration` 支持组合单位：`s` 秒、`m` 分、`h` 小时、`d` 天、`w` 周。  
例如：`7d`、`1h30m`、`2w3d`。

### recipeOverrides 说明

支持 `SHAPED` 与 `SHAPELESS` 两种类型：

```yml
recipeOverrides:
  - key: custom_shapeless
    type: SHAPELESS
    result:
      key: minecraft:golden_apple
      amount: 1
      snbt: "{CustomModelData:1}"
    ingredients:
      - key: minecraft:apple
        amount: 1
      - key: minecraft:gold_ingot
        amount: 8
```

每个材料支持 `snbt`，会以 ExactChoice 注册（带 NBT 精确匹配）。

## 运维建议

- 公益服建议只封禁少数会破坏生态的关键物品，保持核心玩法开放。
- 使用 `/dbi nbt` 获取 NBT 后优先用 `PATH_EQUALS` 规则，稳定性与可维护性更好。
- 更新 Mohist 或模组版本后，建议检查深度移除统计日志，必要时对关键物品做回归测试。

## 许可协议

MIT License
