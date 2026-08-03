# FuckCainiao

[![GitHub license](https://img.shields.io/github/license/lisrain/FuckCainiao_Fork?style=flat-square)](https://github.com/lisrain/FuckCainiao_Fork/blob/main/LICENSE)
![Android SDK min 26](https://img.shields.io/badge/Android%20SDK-%3E%3D%2026-brightgreen?style=flat-square&logo=android)
![Android SDK target 37](https://img.shields.io/badge/Android%20SDK-target%2037-brightgreen?style=flat-square&logo=android)
![libxposed API 101](https://img.shields.io/badge/libxposed-API%20101-blue?style=flat-square)

菜鸟界面优化和广告移除

## Fork 说明

本仓库 fork 自 [duzhaokun123/FuckCainiao](https://github.com/duzhaokun123/FuckCainiao)，与上游的主要差异：

- **包名/应用 ID** 改为 `io.github.lisrain.fuckcainiao`，可与上游版本共存安装
- **libxposed API 101**（`minApiVersion=101` / `targetApiVersion=101`），不使用 API 102 专属的热重载能力，兼容范围聚焦 101+ 框架
- 适配菜鸟 **8.11.727**：底栏 tab 隐藏、导航栏颜色同步、物流详情广告清理等 hooks 调整
- 首页移除“物换物”入口（金刚栏 / DinamicX 缓存渲染双重过滤）
- 拦截“关于 FuckCainiao”入口的跳转，点击不弹原作者的捐赠提示

## 下载

https://github.com/lisrain/FuckCainiao_Fork/releases

## 效果

|              前               |             后              |
|:----------------------------:|:--------------------------:|
|  ![before](arts/before.png)  |  ![after](arts/after.png)  |
| ![before2](arts/before2.png) | ![after2](arts/after2.png) |

## Thanks

### 工具

[jadx](https://github.com/skylot/jadx)

开发者助手专业版(`cn.trinea.android.developertools.pro`)

### 库

[AOSP](https://source.android.com/)

[libxposed](https://github.com/libxposed/api)
