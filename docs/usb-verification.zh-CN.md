# USB 跨设备调试验收

[English](usb-verification.md) · 简体中文

首版的 USB 调试已通过 Android 系统 USB Host 接口、授权弹窗和 bulk 端点的端到端测试。IDE 与被调试 App 分别运行在两台 API 36 x86_64 模拟器中。真实手机之间的 OTG 供电、数据线、角色协商和厂商 USB 驱动仍需要实体验收。

## 测试拓扑

```text
第一台 Android 模拟器
  掌上 Studio → UsbManager → 系统 USB 授权弹窗
  → UsbDeviceConnection.bulkTransfer → Linux dummy_hcd → FunctionFS ADB 外设
                                                           │
                                              透明 TCP 传输 ADB 数据包
                                                           ↓
第二台 Android 模拟器                              真实 adbd → ART → 调试测试 Activity
```

虚拟 USB 外设报告 VID `18d1`、PID `4ee7` 和标准 ADB 接口 `ff/42/01`。2026-11-18 的验收中，Android 将其枚举为 `/dev/bus/usb/001/002`，厂商为 `OnDeviceTest`，名称为 `Developer phone`。系统显示「Allow 掌上 Studio to access Developer phone?」。确认后，Studio 在普通应用 UID 下打开设备并读写 USB 端点。桥接程序在外设侧传输 ADB 数据包，安装、shell 和调试请求均由第二台模拟器的 adbd 执行。

## 验收结果

| 操作 | 观察到的结果 |
| --- | --- |
| 连接和 APK 安装 | 通过 USB shell 读到目标 AVD 名称 `studio_usb_target`，目标 PackageManager 安装并返回 `example.hello` 的 APK 路径 |
| Logcat 和进程选择 | 读取到为本次运行生成的日志标记，在 `track-jdwp` 中找到目标进程 |
| 断点和变量 | 点击目标按钮后，执行停在 `MainActivity$1.onClick:18`，栈中可见该方法，局部参数 `view` 为 `android.widget.Button` |
| 单步、恢复和分离 | 单步到第 19 行，恢复后按钮显示 `Hello 1`，调试器正常分离 |

这个系统镜像默认关闭 adbd 的 RSA 询问。单独的协议测试在 AndroidKeyStore 上验证了标准 RSA 质询、签名、ADB 公钥格式和持久密钥。这里实际操作的系统弹窗是第一台 Android 的 USB 设备访问授权。实体目标的「允许 USB 调试」RSA 弹窗仍需在实体设备上核验。

## 重跑

需要 Linux x86_64 工作站、可以静态链接的 C 编译器、Linux FunctionFS 开发头文件、Python 3，以及包含 `system-images;android-36;google_apis;x86_64` 的 Android SDK。先按[项目构建说明](../README.zh-CN.md#从源码构建及验证)准备工作区并构建一次，以缓存 Gradle 依赖。脚本使用 SDK 的 AAPT2、D8 和 apksigner 编译专用测试 Activity。测试脚本会创建两台临时 AVD，仅在测试 Android 客体内以 root 配置虚拟 USB 控制器。宿主机使用普通用户权限。默认模拟器端口为 5592 和 5594。

```sh
export ANDROID_HOME=/path/to/your/android-sdk
tools/test/usb-gadget/run.sh
```

成功时，instrumentation 输出包含下面的结果。

```text
PASS cross-device install, logcat, JDWP list, breakpoint, stack, locals, step, resume, detach
```

如需单独验证分帧、USB 零长度包、断线和密钥签名，可运行 `tools/test/usb/run.sh`。在命令末尾附上另一台测试模拟器的序列号，还会使用真实 AndroidKeyStore 执行授权协议测试。
