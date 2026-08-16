# 说话人分离调研与随记实现决策

更新日期：2026-08-16

## 结论

说话人分离输出的是匿名聚类标签，不是人物身份。底层返回的簇号可能是
`1、2、4、5`，产品层不能把这些原始数字直接展示。随记统一按整段录音中
“第一次出现的时间”重命名：第一个声音为 `speaker_0`（界面显示说话人 1），
第二个此前未出现的声音为 `speaker_1`，依次连续编号；再次匹配到旧声音时沿用
原编号。

本次两人被分成四人的直接原因是 sherpa-onnx 未知人数聚类阈值使用了默认
`0.50`。sherpa-onnx 明确说明阈值越小会产生越多聚类，官方未知人数示例使用
`0.90`。随记现改为 `0.90`，优先抑制手机短录音中因音量、播放设备、距离和
信道变化造成的过度分裂。

## 成熟项目的共同结构

1. 语音活动/分段：先确定哪些时间有人声以及可能的说话人变化。
2. 声纹嵌入：对有声窗口提取 embedding，而不是靠转录文字猜人物。
3. 聚类或在线归属：把相似 embedding 归到已有人物；只有证据充分时才新增人物。
4. 后处理：合并邻近同人物片段、过滤过短变化、形成可供 ASR 对齐的时间轴。
5. 展示层重命名：匿名簇标签只在内部使用，界面使用稳定、连续的人物名称。

## 参考项目

### sherpa-onnx

- Android 可离线运行，当前随记使用其 Pyannote segmentation、3D-Speaker embedding
  和 FastClustering。
- 已知人数时官方强烈建议传入 `num_clusters`；未知人数时使用距离阈值。
- 官方说明：阈值越小聚类越多，越大聚类越少；未知人数示例为 `0.90`。
- 官方动态识别示例使用 Silero VAD 过滤语音，再通过 `SpeakerEmbeddingManager.search`
  匹配已注册人物；未命中时才用连续编号调用 `manager.add` 注册新人。随记实时链路
  已采用该官方流程。
- 来源：<https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/sherpa-onnx-offline-speaker-diarization.cc>
- 参数定义：<https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/fast-clustering-config.h>
- 动态识别示例：<https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/speaker-identification-with-vad-dynamic.py>

### pyannote.audio

- 成熟流水线把 segmentation、embedding、clustering 和重建分开。
- 支持已知 `num_speakers`，也支持 `min_speakers` / `max_speakers` 约束。
- 没有真实人物资料时，输出同样只是 `SPEAKER_00、SPEAKER_01...` 匿名标签。
- 来源：<https://github.com/pyannote/pyannote-audio/blob/main/src/pyannote/audio/pipelines/speaker_diarization.py>

### NVIDIA NeMo

- 使用 VAD、TitaNet embedding、聚类和多尺度解码；多尺度窗口兼顾稳定声纹与
  较准确的切换边界。
- 对片段较少的短录音启用增强人数估计，说明短音频的人数判断需要单独稳健化。
- 来源：<https://docs.nvidia.com/nemo-framework/user-guide/24.12/nemotoolkit/asr/speaker_diarization/configs.html>

### WeSpeaker

- 官方 VoxConverse 流程用 1.5 秒窗口、0.75 秒步长提取重叠 embedding，再进行
  spectral clustering，最后输出 RTTM 时间轴。
- 来源：<https://github.com/wenet-e2e/wespeaker/blob/master/docs/voxconverse_diar.md>

### FluidAudio

- 移动端在线方案保留短暂的 tentative prediction（暂定结果）后再稳定输出，
  与随记“先归当前人物、证据达到阈值后回溯改派”的产品规则一致。
- 其传统聚类实现也暴露 clustering threshold 和 minimum speech duration。
- 来源：<https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Diarization/GettingStarted.md>

### WhisperX

- ASR、对齐与说话人分离是独立阶段；Pyannote 先产生人物时间段，随后把人物标签
  赋给转录词/片段。
- 允许用户提供最少/最多说话人数以减少人数漂移。
- 来源：<https://github.com/m-bain/whisperX/blob/main/whisperx/__main__.py>

## 随记当前规则

- 录音中：ASR 持续输出；独立人物队列使用 3 秒重叠窗口，每 0.5 秒通过
  sherpa-onnx 3D-Speaker embedding 和官方 `SpeakerEmbeddingManager` 完成搜索。
  匹配已登记人物时立即切换；只有未登记的新声音保留暂定状态，连续两个窗口均
  未匹配后才注册新人并回溯调整近期文字。因此不会等待整句结束，也不会等下一段
  发言才切换回已有说话人。
- 保存后：Pyannote segmentation + 3D-Speaker embedding + FastClustering 对整段
  音频重新计算，以获得更稳定的最终时间轴。
- 最终标签：不采用模型的原始簇号，严格按首次出现顺序连续重排。
- 当前未知人数阈值：`0.90`。后续应基于真实手机录音测试集用 DER、误分裂率和
  漏分裂率校准，而不是继续凭单条样本修改。

注意：FastClustering 的 `0.90` 是“距离阈值”，值越大人物越少；
`SpeakerEmbeddingManager.search` 的 `0.50` 是“相似度最低值”，值越大匹配越严格。
两者语义相反，不能把网上给其中一个组件的参数直接复制到另一个组件。

### 实时切换延迟修正

0.11.0 同时等待 Silero VAD 输出完整语音段和第二次人物确认，导致已登记人物也会
慢一整段：如果用户只播放一次新音频，切换可能永远不发生。0.12.0 改为成熟在线
方案常用的重叠滚动窗口；已有人物使用官方 manager 的搜索结果立即切换，只对真正
未知的人物延迟注册。滚动调度属于应用层编排，声纹提取、相似度搜索仍全部由
sherpa-onnx 完成。

0.13.0 将实时声纹上下文从 1.5 秒提高到 3 秒，以提升短句、音色变化和一般噪声下
embedding 的稳定性；检测步长仍为 0.5 秒，并非每 3 秒才检测一次。

## 后续改进

- 在设置中增加“自动 / 已知 2 人 / 已知 3 人...”人数提示。已知人数直接传
  `num_clusters`，这是 sherpa-onnx 官方最推荐的稳定方式。
- 建立近讲、远讲、手机外放、电视背景声、同声重叠等测试集，记录预期 RTTM。
- 若单阈值仍无法兼顾场景，引入多尺度 embedding 或支持 Android 的端到端在线
  diarization 模型，不把更多启发式逻辑塞进 ASR 模块。
