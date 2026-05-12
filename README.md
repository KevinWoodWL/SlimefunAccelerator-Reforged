# SlimefunAccelerator Reforged

一个面向 Leaf 1.21.11 的 SlimefunAccelerator 重制版。

本分支重点解决现代 Paper/Leaf 服务端中的异步世界访问问题。原来的异步机器 tick 容易在新版本 Leaf/Paper 上触发 `async world access`，因此本重制版移除了不安全的异步机器执行逻辑，并将机器 tick 改为“按区块分组 + 区域调度”的安全模型。


## 改动

- 移除机器 ticker 的异步执行。
- 默认配置中的 `async` 改为 `false`。
- 新增 Leaf/Paper 区域调度工具。
- 机器位置先进入队列，再按区块分组处理。
- 每个区块的机器组会优先提交到 Paper/Leaf 的 `RegionScheduler`。
- 如果服务端没有 `RegionScheduler`，自动回退到 Bukkit 主线程调度。
- 修复原有运行状态判断反向导致的跳 tick/重入问题。
- 包装后的 Slimefun ticker 强制同步收集位置，避免 Slimefun 把收集逻辑丢到异步线程。

## 鸣谢

原项目：SlimefunAccelerator by balugaq。

本重制版面向 KevinWoodWL 的 Leaf 服务器环境维护，目标是安全、稳定、可调控地降低粘液机器 tick 压力。
