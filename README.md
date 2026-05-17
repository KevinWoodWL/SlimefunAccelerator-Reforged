# SlimefunAccelerator Reforged

一个面向 Leaf 1.21.11 的 SlimefunAccelerator 重制版。

本分支重点解决现代 Paper/Leaf 服务端中的异步世界访问问题。原来的异步机器 tick 容易在新版本 Leaf/Paper 上触发 `async world access`，因此本重制版移除了不安全的异步机器执行逻辑，并将机器 tick 改为“按区块分组 + 区域调度”的安全模型。


## 改动

- 移除机器 ticker 的异步执行。
- 默认配置中的 `async` 改为 `false`。
- `async: false` 现在会强制该加速组在区域/主线程串行执行，适合 LogiTech 等不适合并发的附属。
- `async: true` 只会让原本标记为异步的 Slimefun ticker 异步执行，并按加速组加锁串行，适合 Networks 等需要异步事件的附属。
- 新增 Leaf/Paper 区域调度工具。
- 机器位置先进入队列，再按区块分组处理。
- 每个区块的机器组会优先提交到 Paper/Leaf 的 `RegionScheduler`。
- 如果服务端没有 `RegionScheduler`，自动回退到 Bukkit 主线程调度。
- 修复原有运行状态判断反向导致的跳 tick/重入问题。
- 包装后的 Slimefun ticker 强制同步收集位置，避免 Slimefun 把收集逻辑丢到异步线程。
- 新增 `/slimefunaccelerator enable` 和 `/slimefunaccelerator disable`，可在运行时启用/关闭加速器。
- `/slimefunaccelerator disable` 会取消加速任务，并把机器 ticker 还原为 Slimefun 原始状态。
- `/slimefunaccelerator reload` 现在只重载加速器配置和运行时，不再调用插件自身的 `onDisable/onEnable`。
- 新增兼容旁路：MomoTech、MomoTechvOptimized、FinalTECH、FinalTECH-Changed 默认保留 Slimefun 原生 ticker，不进入加速器队列。
- 新增可选原生降频：兼容敏感附属可以在原生 ticker 上按 TPS 降频，适合粘液 tick 过高时削峰。

## 配置建议

`config.yml` 中的 `enabled` 控制插件启动后是否自动启用加速器运行时。

```yaml
enabled: true
```

`accelerates.yml` 中建议按附属分组配置：

```yaml
accelerates:
  logitech:
    enabled: true
    async: false
    period: 20
    delay: 20
    addons:
      - "LogiTech"
    items: []
    excludes: []
    remove-original-ticker: false

  networks:
    enabled: true
    async: true
    period: 40
    delay: 20
    addons:
      - "Networks"
    items: []
    excludes: []
    remove-original-ticker: false
```

如果某个附属出现并发报错，优先把对应加速组改为 `async: false`。

### MomoTech / FinalTECH 兼容策略

这类附属的机器常依赖原生 Slimefun tick 调用现场、菜单缓存和内部状态，不建议把它们放进加速器队列或异步执行。默认配置会让它们完全旁路：

```yaml
compatibility:
  native-addons:
    - "MomoTech"
    - "MomoTechvOptimized"
    - "FinalTECH"
    - "FinalTECH-Changed"
```

如果它们仍然是粘液 tick 大头，可以开启原生降频。它不会把机器搬到异步线程，也不会进入加速器队列，只是在 Slimefun 原生 ticker 现场按 TPS 少跑部分 tick：

```yaml
compatibility:
  native-throttle:
    enabled: true
    divisor: 4
```

默认逻辑：

- TPS 正常时全速运行。
- TPS 低于 `load-aware.throttle-tps` 时约 1/2 速度运行。
- TPS 低于 `load-aware.skip-tps` 时约 1/`divisor` 速度运行。

这能降低服务器压力，但代价是这些机器的实际工作速度会下降。

## 已知问题

### SlimefunTimeit 兼容问题

当前版本不建议与 `SlimefunTimeit` 同时使用。

`SlimefunTimeit` 会包装 Slimefun 机器 ticker 并写入性能统计数据。加速器改变机器 tick 的调度节奏后，可能导致 `SlimefunTimeit` 的统计容器被并发访问，从而出现以下问题：

```text
java.lang.ArrayIndexOutOfBoundsException
it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
com.balugaq.sftimeit.core.Monitor.getData
com.balugaq.sftimeit.api.MonitoringBlockTicker.tick
```

严重时服务器主线程可能卡在 `SlimefunTimeit` 的统计读取逻辑中，并触发 Watchdog：

```text
The server has not responded
```

建议：

- 测试或使用本加速器时，先移除或禁用 `SlimefunTimeit`。
- 需要使用 `SlimefunTimeit` 做性能分析时，先通过 `/slimefunaccelerator disable` 关闭加速器，或在 `config.yml` 中设置 `enabled: false` 后重启服务器。
- 不建议在启用 `async: true` 的加速组时同时运行 `SlimefunTimeit`。
- 如果已经出现上述异常，建议完整重启服务器，不要只热加载插件。

## 鸣谢

原项目：SlimefunAccelerator by balugaq。

本重制版面向 KevinWoodWL 的 Leaf 服务器环境维护，目标是安全、稳定、可调控地降低粘液机器 tick 压力。
