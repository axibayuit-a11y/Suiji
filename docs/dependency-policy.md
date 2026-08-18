# 随记依赖与实现边界

更新日期：2026-08-17

## 当前依赖

- 本地 ASR：sherpa-onnx 1.13.4 + SenseVoice。
- ASR 语音切分：sherpa-onnx + Silero VAD。
- 实时说话人分离：官方 LS-EEND 1–8 人权重导出的固定状态接口 ONNX。
- Android 推理：Microsoft ONNX Runtime Android 1.26.0，CPU 两线程。1.29.0 对本模型
  没有必要算子收益，却使 arm64 APK 增加约 32 MB，因此暂不升级。

## 项目实现

- Android 麦克风、前台服务、录音文件与两条实时音频队列。
- 与 LS-EEND 官方训练配置一致的流式声学前端。
- 模型状态生命周期、500 ms 批处理调度、预热帧过滤和时间戳队列。
- 说话人活动轨与 ASR 时间段对齐、匿名标签连续重命名及界面展示。

## 明确不保留

- Pyannote segmentation、3D-Speaker embedding、声纹注册表、相似度搜索和聚类。
- `OfflineSpeakerDiarization` 以及停止录音后重新跑一遍人物分离。
- 为旧模型 ID、旧下载目录、旧设置页面或旧阈值提供兼容分支。
- 把短窗口 embedding 搜索包装成“实时说话人分离”的替代实现。

模型格式、前端常量或循环状态形状变化时，必须同步更新真实模型测试；不能通过返回
固定“说话人 1”、伪造概率或在异常时静默退回旧算法来让界面看似可用。
