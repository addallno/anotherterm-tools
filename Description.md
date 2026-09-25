# AnotherTerm Tools 项目说明

## 用途

改造 [green-green-avk/AnotherTerm](https://github.com/green-green-avk/AnotherTerm)（minSdk 14，本地 PTY + SSH/Telnet + PRoot）为 Termux 风格环境，面向 Android 4.4+：

- 自定义 `$PREFIX` / `$HOME` / `$TMPDIR` 目录结构
- 启动时后台运行 Dropbear SSH（端口 8022）
- 默认 shell 优先级 `fish > bash > zsh > sh > /system/bin/sh`
- 内置 78 个静态 ARM32 工具（busybox、curl、python、jq、nano、vim、git、wget、strace、lsof、tcpdump 等）+ `pkg` 包管理器
- `$PREFIX`/`$HOME`/`$TMPDIR`/`PATH`/`SHELL` 注入到本地终端会话环境

## 架构

```
本仓库 = upstream AnotherTerm + Termux 移植层
```

- 本地 Git：`git@github.com:addallno/anotherterm-tools.git`（HTTPS 443 不通）
- upstream remote：`https://github.com/green-green-avk/AnotherTerm.git`
- 无本地 Android SDK，编译全部在 GitHub Actions 完成

### 移植改动

| 文件 | 改动 |
|------|------|
| `app/src/main/java/.../termtools/TermEnv.java` | 新增：目录结构、工具/骨架安装、shell 探测、Dropbear 启动 |
| `app/src/main/java/.../App.java` | onCreate 调用 `TermEnv.init()` |
| `app/src/main/java/.../backends/local/LocalModule.java` | 注入 Termux 环境变量；默认 shell 走 `TermEnv.detectShell()` |
| `app/build.gradle` | `-Psigning.properties` 签名（v1+v2+v3） |
| `app/src/main/assets/tools/usr/bin/` | 78 个工具 + pkg（从 termoneplus-tools 移植） |
| `app/src/main/assets/skel/` | home 骨架（.shrc） |
| `scripts/` | dropbear/fish musl 交叉编译（CI 内重编替换 assets） |
| `patches/dropbear-android-user.py` | dropbear Android 补丁，HOME_DEFAULT 已改本应用 |
| `.github/workflows/build.yml` | CI：NDK 23.2 → musl dropbear → musl fish → 签名 → oldgoodDowngradableRelease |

### 运行时目录结构

```
/data/data/green_green_avk.anotherterm/files/   # redist 变体带 .redist 后缀
  usr/          # $PREFIX（bin/ etc/ lib/ tmp/）
  home/         # $HOME
```

## 构建与命令

### CI（.github/workflows/build.yml）

```bash
# 触发：push master 或 workflow_dispatch
# 变体：oldgoodDowngradableRelease（targetSdk 28，最适合 Android 4.4）
# 产物：dist/*.apk + release.keystore + signing.properties
```

### 本地下载产物

```bash
TOKEN=$(gh auth token)
gh run list -R addallno/anotherterm-tools
# 单进程断点续传，绝不能并发 curl 同一文件！
curl -L -C - --connect-timeout 30 --max-time 550 \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -o artifact.zip \
  "https://api.github.com/repos/addallno/anotherterm-tools/actions/artifacts/<ID>/zip"
stat -c%s artifact.zip   # 必须精确等于 API 报告的 size_in_bytes
```

### 推送到设备

```bash
scp -P 48022 -o BatchMode=yes <apk> u0_a207@8.162.6.112:~/
```

## 依赖

- **构建**：AGP 8.4.1、Gradle 8.6、compileSdk 33、targetSdk 33（oldgood=28）、minSdk 14、NDK 23.2.8568313、Java 17
- **CI 脚本**：`scripts/build-dropbear-musl.sh`、`scripts/build-fish-musl.sh`
- **pkg 远程源**：`polaco1782/linux-static-binaries` @ `armv7l-eabihf/`
- **依赖库**：jitpack `green-green-avk:libusbmanager` / `anothertermshellpluginutils`

## 已知问题 / 待办

1. TermOne Plus 在 Android 4.4.2 崩溃问题未定位（run-*.log 待用户提供），本项目为替代方案
2. fish 依赖 CI musl 编译，assets 中初始无 fish（首次 CI 构建后才有）
3. bash 不在 assets（上游已删），优先级列表中保留检测位
4. CI 临时签名 keystore 可固化到 secrets（`KEYSTORE_B64` / `KEYSTORE_PASS`）
