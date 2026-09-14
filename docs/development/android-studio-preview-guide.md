# Android Studio 界面预览

## 入口

打开 app/src/debug/kotlin/com/masterofchessstrategy/ui/PreviewCatalog.kt，在编辑器右上角选择 **Split** 或 **Design**。Preview 面板的“已完成界面”分组按顺序包含：

1. 首页：本地档案、五类棋种入口和中国象棋快速进入。
2. 中国象棋模式：本地双人、人机、自动演局、残局占位和教程。
3. 自动演局：完整棋盘、双方棋钟、暂停态、连续局数和 2.0 倍速。
4. 设置：默认难度、自动续局上限、音效和对局时长。

## 边界

- 演示数据位于 Debug 源集，只用于设计验收，不写入数据库，也不进入 Release APK。
- Preview 复用正式 Composable，不复制页面实现。
- 当前 SDK 没有系统镜像，且 ADB 无连接设备，因此这里只能预览界面；真实点击、JNI 和性能测试需连接设备或先在 SDK Manager 安装一个 x86_64 系统镜像并创建 AVD。
