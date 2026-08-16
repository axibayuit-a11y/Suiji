# 随记依赖与自研边界

更新日期：2026-08-16

## 原则

对语音活动检测、语音识别、说话人嵌入、说话人搜索、聚类、音频解码等专业算法，
优先采用维护活跃、许可明确、有移动端支持和官方测试的成熟开源组件。应用层不复制
它们的核心数学实现，不自行维护另一套余弦相似度、聚类或声纹质心算法。

## 当前采用

- 本地 ASR：sherpa-onnx + SenseVoice。
- ASR 人声切分：sherpa-onnx + Silero VAD。
- 实时声纹：sherpa-onnx + 3D-Speaker embedding。
- 实时注册与搜索：sherpa-onnx `SpeakerEmbeddingManager`。
- 实时人物调度：1.5 秒重叠窗口、0.5 秒步长；它只负责调用时机，不计算声纹。
- 保存后最终分离：sherpa-onnx `OfflineSpeakerDiarization`，内部组合 Pyannote
  segmentation、3D-Speaker embedding 与 FastClustering。

## 应用层可以保留

- Android 权限、前台服务、文件存储与模型下载。
- ASR、说话人分离、照片与 AI 服务之间的模块编排。
- 暂定结果防抖、确认后回溯移动文字等产品交互状态；它不计算声纹相似度。
- 匿名聚类标签按首次出现顺序连续重命名。
- 时间线、界面、设置和错误提示。

## 引入新组件前

1. 确认 Android/ARM64 支持、维护状态、Release 与官方示例。
2. 确认代码和模型权重各自的许可证，不能只看仓库许可证。
3. 首先封装官方 API 并添加设备级冒烟测试，不复制算法源码到业务包。
4. 只有成熟组件明确不提供某项产品状态时，才编写薄适配层，并在文档中说明边界。
