# 随记 Android

“随记”的 Android 客户端，使用 Kotlin、Jetpack Compose 和 Material 3 构建。当前版本已经打通后台录音、手机端实时转录、LS-EEND 实时说话人分离、统一时间线和保存归档。

## 当前功能

- 两个底部入口：文件、设置。
- 录音圆球只显示在文件页中央。
- “全部文件”可打开分类与排序面板。
- 使用 `AudioRecord` 采集 48 kHz 单声道 PCM，并保存为标准 WAV；支持暂停、继续、停止。
- 录音页波形直接由麦克风每帧 RMS 振幅绘制，不使用预制动画。
- 安装并选择 SenseVoice 本地模型后，录音期间会按语音片段持续刷新“AI 识音”文字。
- 录音由 microphone 前台服务持有；按返回键或回到桌面不会停止，通知栏可暂停或停止并保存。
- WAV 文件头和草稿元数据每秒检查点保存，降低异常退出时丢失整段录音的风险。
- 录音时约每秒刷新一次临时转录结果，不等待停顿、暂停或说话人模型；Silero VAD 只负责后台确认文字，不生成可见的固定时间节点。
- 转录片段、照片和标记全部以毫秒时间戳写入同一时间线，点击详情时间可跳转播放位置。
- 转录和说话人分离使用两条独立音频队列；LS-EEND 每 100 ms 接收一帧、每 500 ms 批量推理一次，编码器、解码器和卷积缓存贯穿整场录音。
- 模型直接输出最多 8 条说话人活动轨，支持重叠语音；不再使用声纹窗口注册、相似度阈值或录音结束后的离线聚类。
- 模型约需 1.1 秒完成声学前端与卷积预热，之后持续输出；延迟到达的活动帧会修正尚未稳定的近期文字归属。
- 录音阶段不展示人物节点时间戳，保存后才按确认的说话人节点显示起始时间；不同人物使用不同的柔和标签色。
- 说话人分离是独立开关，内置 1–8 人 LS-EEND 流式模型，并支持把匿名说话人编号改成姓名。
- 匿名活动轨按首次发言顺序稳定重排为说话人 1、2、3，不显示模型内部轨号。
- 录音语言可选择中文、English、粤语（香港）。
- 录音期间调用系统相机，照片与当前录音一起归档。
- 停止后自动返回文件页，新录音立即显示并持久保存。
- 点击归档录音可进入详情页，播放、暂停并拖动音频进度。
- 详情页展示录音时间、时长、相关照片以及转录和总结入口。
- 支持搜索录音、标记收藏、设置会议/课堂分类及删除档案。
- 语音转录只使用设备上的本地模型，不提供云端音频转录模式，也不会向 AI 服务发送录音文件。
- 智能总结与对话服务单独配置 OpenAI 兼容的 HTTPS `/v1` 地址、API 密钥和对话模型；该模块只接收用户提交的转录文字。
- 本地转录支持关闭或开启，所用模型可独立下载、切换和删除。
- 集成 sherpa-onnx 1.13.4 与 SenseVoice 离线推理，支持中文、英文和粤语。
- 本地模型中心支持下载进度、断点续传、安装校验、切换与删除模型。
- API 密钥通过 Android Keystore 的 AES-GCM 加密后保存在设备本地。
- 界面支持简体中文、English、繁體中文。
- 支持跟随系统、浅色和深色三种外观；配色严格使用黑白灰。
- 设置页可检查 GitHub Release：自动尝试两个下载加速源并以 GitHub 官方源兜底，支持跨源断点续传；下载后校验版本、包名、签名与 SHA-256，再交由 Android 系统安装器更新。

## 架构

```text
MainActivity
└── SuijiApp（权限与页面导航）
    ├── MainShell
    │   ├── FilesScreen（文件、分类、排序）
    │   └── SettingsScreen（语言、主题、本地转录、说话人分离与 AI 服务）
    ├── RecordingScreen（录音语言、波形、统一时间线、拍照、暂停、停止）
    ├── RecordingDetailScreen（播放、进度、照片、转录与总结入口）
    ├── LocalModelSettingsScreen（语音转录模型）
    └── AiServiceSettingsScreen（总结与对话服务，只处理文字）

SuijiViewModel（UI 状态、归档与转录调度）
├── RecordingService（前台录音、通知控制、草稿检查点）
│   └── AudioRecorder（AudioRecord、PCM/WAV、实时振幅）
├── FileRecordingRepository（JSON 元数据与本地文件）
├── AppPreferences / SecureValueStore（设置与加密凭据）
├── LocalModelManager（仅管理语音转录模型）
├── NaturalSpeechSegmenter（神经 VAD，仅后台确认转录文字）
├── SenseVoiceLocalTranscriptionEngine（离线文件与连续动态转录）
├── speaker/LsEendFeatureExtractor（8 kHz 流式 Mel、累计均值与上下文拼接）
├── speaker/LsEendStreamingModel（ONNX Runtime 与显式循环状态）
├── speaker/RealtimeSpeakerDiarizer（100 ms 音频到 8 条实时活动轨）
├── speaker/LiveConversationTimeline（按活动轨对齐实时文字）
└── update/AppUpdateManager（Release 检查、APK 下载校验与系统安装）
```

录音和照片保存在 Android 应用专属目录，不需要申请公共存储权限；卸载应用时这些数据会被系统清理。麦克风和相机权限只在用户触发对应功能时请求。

## 构建

项目要求 JDK 17 和 Android SDK 36。在项目目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建会输出按 CPU 架构拆分的 APK 和一个通用 APK。大多数现代 Android 手机使用 `arm64-v8a`；不确定设备架构时可安装通用版。

## 在电脑模拟器测试麦克风与摄像头

Android Emulator 默认不会把电脑麦克风送入虚拟设备。启动 AVD 时加入以下参数：

```powershell
emulator.exe -avd Suiji_Pixel_7_API_36 -allow-host-audio -camera-back webcam0 -camera-front none
```

然后在模拟器右侧工具栏打开“更多（…）→ Microphone”，启用 **Virtual microphone uses host audio input**。Windows 的默认输入设备应选择真实麦克风（本机为“麦克风 (Realtek High Definition Audio)”），不要选虚拟声卡。摄像头可在模拟器 Camera 应用中先行确认；本机映射的真实设备为 `webcam0 / HD Webcam`。

录音期间和停止后的文字都只由已安装的本地模型产生，界面会如实显示当前状态，不会用模拟文字冒充转录结果。AI 服务与录音链路隔离，只允许在总结或对话功能中接收转录文字。

## 下一阶段

实时转录和实时说话人分离主链路已经接通。下一阶段是补充更多真机、远场、重叠语音与功耗基准，并完善基于转录文字的总结和对话。

## 开源许可

本项目使用 [Apache License 2.0](LICENSE) 开源。
