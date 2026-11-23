# 小鸢输入法 Iris Keyboard

**小鸢输入法**（Iris Keyboard）是 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)（小企鹅输入法）的独立发行版：把 Rime 输入引擎与四套方言方案直接打进**一个 APK**，词库在构建机上预编译好随包分发，安装激活后**开箱即用**——不再需要装插件 APK，也不再需要手动「部署」。

## 内置方案

| 方案 | schema | 说明 |
|---|---|---|
| 吴语·苏沪混合腔 | `wugniu_suwu` | 拼音文、语法模型、搜狗词表齐备 |
| 吴语拼音文 | `wugniu_lumozy` | 汉字 ⇄ 拼音文互换（复用 `wugniu_suwu` 词典） |
| 西安话·城区音 | `chengqyu` | `q` 普通话拼音反查、`x` 笔画反查 |
| 西安话·南郊音 | `nanjiao` | 与城区音并行，南郊音体系 |

方案来源：

- 吴语两方案：[SiqYin/wugniu_suwu](https://github.com/SiqYin/wugniu_suwu)（安卓包内同步自该方案库）
- 西安话两方案：[SiqYin/android-xinganhua](https://github.com/SiqYin/android-xinganhua)（城区/南郊两套，词音数据源自 osfans/MCPDict 及逐字人工裁定）
- 引擎与界面：上游 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 及 librime 全家

## 下载

见 [Releases](https://github.com/SiqYin/fcitx5-iris/releases)。每个 Release 提供 4 个 ABI 各一个 APK，一般手机选 **arm64-v8a**。

安装后打开应用，按引导在系统设置中启用「小鸢输入法」并切换为默认输入法，即可直接输入（无需任何部署步骤）。

## 与旧直装包的关系

本仓库是 [SiqYin/fcitx5-wugniu](https://github.com/SiqYin/fcitx5-wugniu)（吴语直装包）与 [SiqYin/android-xinganhua](https://github.com/SiqYin/android-xinganhua)（西安话直装包）的合并继任者，两库已暂停更新。包名不同（`io.github.siqyin.iris`），可与旧包并存安装，确认数据无误后卸载旧包即可。

## 开箱即用的原理

- 构建时（CI）在 Ubuntu 上用与安卓包**同版本的 librime**（1.12.0）编译 `rime_deployer`，跑 `--build` 把 4 个方案与全部词典编译成 `table/prism/reverse` 产物，连同 `.buildinfo` 版本戳打进 APK 的 `assets/usr/share/rime-data/build/`；
- 该目录正是 librime 的 `prebuilt_data_dir`（`shared_data_dir/build`），运行时加载「已部署资源」的顺序是：手机端 `user_data/build`（无则跳过）→ APK 预置产物；
- 应用首次启动时（`Fcitx.prepareRimePrebuiltDeployment`）对比 `.buildinfo` 版本：变化则清理手机端旧部署产物并预写 `user.yaml`（`var/last_build_time`），使 librime 的修改检测（`DetectModifications`）直接判定「无修改」，**一次编译都不发生**；
- 词库更新只受 APK 体积约束——在手机上编译大词表导致内存超限的问题从根上消失。

兜底：APK 内无预编译产物（本地直接构建）、用户在 Rime 用户目录放了自定义 yaml 时，应用不干预，Rime 走常规部署路径；键盘方案菜单里的「部署」入口保留。

## 构建

```bash
git clone --recurse-submodules https://github.com/SiqYin/fcitx5-iris.git
cd fcitx5-iris
./gradlew :app:assembleRelease
```

本地直接构建（不跑 CI 预编译）时 APK 内无预编译产物，首次使用需手动部署一次，功能不受影响。

## 仓库结构

```
app/                    主应用（单 APK，含 Rime 引擎与全部方案）
plugin/rime/            Rime 源树（CMake 构建脚本、方案文件、fcitx5-rime 子模块）
  src/main/cpp/
    default.yaml        Rime 全局配置（schema_list 只含 4 方案）
    rime-wugniu-suwu/   吴语方案文件（含预编译产物 build/，不入库）
    rime-xinganhua/     西安话方案文件（城区 chengqyu、南郊 nanjiao）
lib/                   fcitx5、libime、fcitx5-lua、fcitx5-chinese-addons
build-logic/           Gradle 约定插件（assets 数据描述符、CMake 装配等）
```

## 维护说明

- 上游同步：与 fcitx5-android/fcitx5-wugniu 相同，逐提交核对上游变更；合并时按「保持删除」处理本仓库已移除的插件目录（anthy/chewing/clipboard-filter/hangul/jyutping/sayura/thai/unikey/plugin-base）。
- 方案文件为只读分发：吴语方案以 wugniu_suwu 上游库为准、西安话方案以 android-xinganhua 为准，更新时同步进 `plugin/rime/src/main/cpp/` 对应目录。
- CI：`build-custom.yml`（预编译 + 构建签名 APK）与 `release.yml`（tag 触发，等待本次 commit 构建变绿后发布，分支与 tag 需同一次 push）。
