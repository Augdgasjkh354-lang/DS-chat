# DS Chat

安卓聊天 App（Kotlin + Jetpack Compose），包名 `com.jack.dschat`。功能清单见 README.md。

## Git

- **改完直接推到 `main`**（Jack 明确要求，长期有效）：`git push origin HEAD:main`。不用开 PR，不用先问。
  会话指定了别的开发分支时，那个分支也照常推一份，但 `main` 一定要推。
- 推之前先编译、跑单测；推到 `main` 会自动打包发版（GitHub Actions「打包 APK」）。推完直接收工，不用等打包结果；只告诉他「已推送，打包在跑」。
- 提交说明用英文写清改了什么、为什么。
- 签名密钥不进仓库：打包时从 GitHub Secrets 的 `KAVI_KEYSTORE_BASE64`、`KAVI_KEYSTORE_PASSWORD` 还原，别名 `kavi`。没配这两个 Secrets 时打的是 debug 包（每次更新要先卸载旧的）。

## 和 Jack 沟通

- 用中文，简洁直接。复杂的事再展开讲，附上建议。
- 动手前拿不准的产品决定先问；工程上的事按惯例自己定，说一声就行。

## 代码

- 包结构：`data/`（数据模型、存储）、`ui/`（界面、主题、图标、文字），`ChatViewModel` 管状态。
- 注释、界面文字都是中文；界面文字放在 `ui/Strings.kt`（中英两套，App 内切换），不走 `strings.xml`。
- 图标是 `ui/Icons.kt` 里手写的路径，不引图标库。不要用鲸鱼、四角星这类和别家 AI 撞脸的标志。
- 单测在 `app/src/test`。

## 本地编译（云端容器）

容器里默认没有 Android SDK，装法和 Maven 镜像的配置见 jack-ai 仓库 `CLAUDE.md` 的同名小节。然后：

```bash
./gradlew --no-daemon -q :app:assembleDebug :app:testDebugUnitTest
```
