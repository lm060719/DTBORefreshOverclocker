[English](README_EN.md) | **简体中文**

# DTBO Studio

Android DTBO（Device Tree Blob Overlay）的查看、编辑与重建工具：导入镜像，识别显示/充电等设备树节点，暂存修改，校验后重新打包导出。当前版本 `1.1.5`。

## 功能

| 模块 | 能力 | Root 直刷 |
| --- | --- | --- |
| 刷新率 | 修改、新增、删除时序档位；平衡时序 / 仅 Pixel Clock / 仅 Framerate / 自定义 | ✅（仅 Framerate 除外） |
| Charging | 编辑已识别的电流、电压、温控表等参数，见 [Charging 参数说明](docs/charging.md) | ❌ 仅导出 |
| 设备树 | 节点浏览、搜索、引用关系；属性增删改，节点新增/克隆/重命名/删除 | ❌ 仅导出 |

所有修改先进入事务队列，可撤销、合并打包。导出方式：DTBO 镜像、Recovery 刷机包、PC Fastboot 包。

## 使用流程

1. **导入**：选择本地 `dtbo.img`，或用 Root 提取当前槽位分区。
2. **识别**：自动扫描时序档位、充电节点，并按面板分组。Root 提取时会读取 `androidboot.dtbo_idx` 与内核启动参数，标出本机在用面板和生效的 DTB。
3. **修改**：在功能模块或设备树页面暂存修改。
4. **打包**：重编译修改过的 DTB，校验未声明的属性没有变化、元数据一致，再重建镜像。
5. **导出 / 刷写**：导出后离线验证，或在允许的情况下 Root 直刷（会先备份原分区）。


## 使用前须知

- **AVB**：打包时会自动更新 DTBO 哈希；带签名的镜像同时更新 vbmeta 认证摘要，原签名数据保留。
- **分区残留数据**：没有 AVB 的分区镜像在 DTBO 之后若有旧数据残留（常见于真我），导入时会提示，打包时将该区域清零。
- **多个 DTB**：同一面板通常在每个 DTB 中都有一份，开机时只会加载其中一个。请修改本机生效的 DTB，面板列表中会标注「本机生效」。
- **非量产屏节点**：高通参考面板、仿真面板会显示「非量产屏节点，改后不生效」。
- **命令模式屏**：部分面板的刷新率由写入屏幕驱动芯片的命令决定，各档位的时钟与前后肩相同。这类面板只改时序通常不会提升实际刷新率，刷入后请用帧率工具确认。
- **没有 clockrate 的档位**：高通驱动会按时序自动计算链路时钟，这类档位修改时不写入时钟属性。

## 风险提示

修改 DTBO 可能导致黑屏、显示异常或无法开机。刷写前请确认 Bootloader 已解锁、原始 DTBO 已备份，并准备好 Fastboot 或 Recovery 恢复方式。仅导出的修改不要绕过限制直接刷入。

## 构建

需要 JDK 17。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Windows 使用 `.\gradlew.bat`。APK 中的 `app/src/main/jniLibs/arm64-v8a/libdtc.so` 是 ARM64 的 DTC 可执行文件，用于 DTS 与 DTB 互转。

推送到 `chatgpt/**` 分支后，GitHub Actions 会运行单元测试并上传 Debug APK。

### 真实镜像测试（可选）

镜像不随仓库提供。将样本按设备放在目录中，并提供本机可执行的 `dtc`：

```bash
./gradlew testDebugUnitTest --tests "*DeviceWorkflowVerificationTest*" \
  -PworkflowSampleDir=<样本目录> -PhostDtc=<dtc 路径> [-PtestMaxHeap=256m]
```

`-PtestMaxHeap=256m` 用于模拟 Android 默认的应用内存上限。

## 协议

[GNU General Public License v3.0](LICENSE)
