# 第三方组件

## DexKit 2.3.0

用于在宿主 APK 中根据业务字符串、方法调用及字段关系定位私有接口。仅后台加载和查询，未修改其 Java 库和 native 核心。

- 项目及对应版本源码：[LuckyPray / DexKit 2.3.0](https://github.com/LuckyPray/DexKit/tree/2.3.0)
- Java/Kotlin 库：[Apache License 2.0](licenses/DexKit-Apache-2.0.txt)
- Native 核心：[GNU LGPL 3.0](licenses/DexKit-Core-LGPL-3.0.txt)，包含其引用的 [GNU GPL 3.0](licenses/GPL-3.0.txt)
- 官方构建说明：[项目 README](https://github.com/LuckyPray/DexKit/blob/2.3.0/README.md)

完整授权文本也包含在 APK 的 `assets/licenses/` 中。项目使用公开 Maven 依赖 `org.luckypray:dexkit:2.3.0`，可从上述源码重建替换依赖；native 核心以独立 `libdexkit.so` 加载。

本说明不改变 MusicEnhance 或其他第三方组件原有的授权条款。
