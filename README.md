# DS Chat

安卓聊天 App（Kotlin + Jetpack Compose），包名 `com.jack.dschat`。

第一版只有界面，回复是演示用的假数据，还没有接模型：

- 对话：流式输出、深度思考（可折叠）、停止、重新生成、复制
- 侧边栏：历史记录按时间分组、搜索、重命名、删除、多选删除
- 设置：外观（系统 / 浅色 / 深色）、字体大小、语言（简体中文 / English）、清空对话

还没做：语音输入、朗读、上传附件、智能搜索（开关在，不会真的联网）。

## 编译

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
