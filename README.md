# DS Chat

安卓聊天 App（Kotlin + Jetpack Compose），包名 `com.jack.dschat`。

接的是 DeepSeek 开放平台的 API（`deepseek-flash`，Chat Completions 流式接口）。第一次用先在 设置 → API Key 填上自己的 Key（[开放平台](https://platform.deepseek.com/api_keys) 创建），保存时会查一下余额确认能用。

- 对话：流式输出、深度思考（可折叠）、停止、重新生成、复制、Markdown 显示（标题、列表、代码块、表格等）、LaTeX 公式（`$…$`、`\(…\)`、`$$…$$`、`\[…\]`，本地用 JLaTeXMath 画）
- 智能搜索：用官方的 `deepseek_web_search`，搜索在 DeepSeek 服务端跑；回答下面列出引用的网页，点开用浏览器看
- 读链接：消息里贴网址，模型会自己去读（`deepseek_read_url`，一直开着）
- 自动标题：新对话的标题由模型顺手起
- 侧边栏：历史记录按时间分组、搜索、重命名、删除、多选删除
- 设置：API Key、外观（系统 / 浅色 / 深色）、字体大小、语言（简体中文 / English）、清空对话
- 个性化：自定义聊天背景图，可调遮罩浓度和模糊（模糊要 Android 12 以上）

还没做：语音输入（ASR）、朗读（TTS）、上传文件和图片。DeepSeek 2026-11-13 已经开放了这些接口，下一步接。

## 编译

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
