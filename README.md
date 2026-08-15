# 随记 Android

“随记”的第一阶段 Android 框架，使用 Kotlin、Jetpack Compose 和 Material 3 构建。当前 0.8.0 版本已经打通文件首页、后台录音、录音中拍照、本地转录、统一时间线、保存归档、设置、多语言和黑白主题的核心流程。

## 当前功能

- 两个底部入口：文件、设置。
- 录音圆球只显示在文件页中央。
- “全部文件”可打开分类与排序面板。
- 使用 `AudioRecord` 采集 48 kHz 单声道 PCM，并保存为标准 WAV；支持暂停、继续、停止。
- 录音页波形直接由麦克风每帧 RMS 振幅绘制，不使用预制动画。
- 安装并选择 SenseVoice 本地模型后，录音期间会按语音片段持续刷新“AI 识音”文字。
- 录音由 microphone 前台服务持有；按返回键或回到桌面不会停止，通知栏可暂停或停止并保存。
- WAV 文件头和草稿元数据每秒检查点保存，降低异常退出时丢失整段录音的风险。
- 转录时间戳由 Silero VAD 检测到的自然停顿生成，不再按固定秒数切分。
- 转录片段、照片和标记全部以毫秒时间戳写入同一时间线，点击详情时间可跳转播放位置。
- 开启说话人模块后，每个自然语音段在写入时间线前即完成声纹归类，不等到录音结束才显示人物。
- 说话人分离是独立模块和独立开关，提供 Pyannote + 3D-Speaker 离线模型，并支持把说话人编号改成姓名。
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
- 设置页可检查 GitHub Release：自动选择 ARM64 或通用 APK，校验版本、包名、签名与 SHA-256 后交由 Android 系统安装器更新。

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
    ├── SpeakerDiarizationSettingsScreen（独立说话人模型与开关）
    └── AiServiceSettingsScreen（总结与对话服务，只处理文字）

SuijiViewModel（UI 状态、归档与转录调度）
├── RecordingService（前台录音、通知控制、草稿检查点）
│   └── AudioRecorder（AudioRecord、PCM/WAV、实时振幅）
├── FileRecordingRepository（JSON 元数据与本地文件）
├── AppPreferences / SecureValueStore（设置与加密凭据）
├── LocalModelManager（仅管理语音转录模型）
├── NaturalSpeechSegmenter（神经 VAD 自然停顿分段）
├── SenseVoiceLocalTranscriptionEngine（离线文件与分段实时转录）
├── speaker/SpeakerDiarizationModelManager（独立模型目录与下载）
├── speaker/LocalSpeakerDiarizationEngine（只输出谁在何时说话）
├── speaker/LiveSpeakerAttributor（自然语音段关闭时立即声纹聚类）
├── speaker/SpeakerAttribution（组合转录时间段与说话人结果）
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

本地转录和独立说话人分离主链路已经接通。下一阶段是让用户基于转录文字主动生成 AI 总结、继续 AI 对话，并增强说话人识别、真机兼容性和性能测试。

## 开源许可

本项目使用 [Apache License 2.0](LICENSE) 开源。
