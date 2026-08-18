"""Real-model smoke test. Run with: python test/test_lseend_streaming_model.py"""

from pathlib import Path

import librosa
import numpy as np
import onnxruntime as ort
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/assets/models/lseend-streaming-1-8spk.onnx"
FIXTURE = ROOT / "test/fixtures/lseend_four_speakers_40s.wav"


def initial_state():
    return {
        "enc_kv": np.zeros((4, 1, 4, 64, 64), np.float32),
        "enc_scale": np.zeros((4, 4), np.float32),
        "enc_conv_cache": np.zeros((4, 1, 256, 15), np.float32),
        "cnn_window": np.zeros((1, 256, 18), np.float32),
        "cnn_count": np.zeros((1,), np.float32),
        "dec_kv": np.zeros((2, 10, 4, 64, 64), np.float32),
        "dec_scale": np.zeros((2, 4), np.float32),
    }


def features():
    audio, sample_rate = sf.read(FIXTURE, dtype="float32")
    assert sample_rate == 8_000
    spectrum = np.abs(
        librosa.stft(audio, n_fft=1024, win_length=200, hop_length=80).T[:-1]
    )
    mel = librosa.filters.mel(sr=8_000, n_fft=1024, n_mels=23)
    values = np.log10(np.maximum(np.dot(spectrum**2, mel.T), 1e-10))
    values -= np.cumsum(values, axis=0) / np.arange(1, len(values) + 1)[:, None]
    padded = np.pad(values, ((7, 7), (0, 0)))
    spliced = np.stack([padded[index : index + 15].reshape(-1) for index in range(len(values))])
    result = spliced[::10].astype(np.float32)
    return result[: len(result) // 5 * 5]


def update_state(outputs):
    names = (
        "probabilities",
        "valid_frames",
        "enc_kv",
        "enc_scale",
        "enc_conv_cache",
        "cnn_window",
        "cnn_count",
        "dec_kv",
        "dec_scale",
    )
    return {
        name: value
        for name, value in zip(names, outputs)
        if name not in ("probabilities", "valid_frames")
    }


def main():
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    model_features = features()
    state = initial_state()
    predictions = []
    for offset in range(0, len(model_features), 5):
        outputs = session.run(
            None,
            {"features": model_features[offset : offset + 5][None], **state},
        )
        predictions.append(outputs[0])
        state = update_state(outputs)

    probabilities = np.concatenate(predictions, axis=1)[0]
    probabilities = probabilities[np.max(probabilities, axis=1) > 0]
    active_tracks = np.sum(probabilities > 0.5, axis=0)

    assert len(probabilities) == 391, "Streaming warm-up/timing changed"
    assert np.flatnonzero(active_tracks).tolist() == [0, 1, 2, 3]
    assert np.all(active_tracks[:4] >= 70), active_tracks
    assert float(np.max(state["enc_kv"])) != 0.0
    assert float(np.max(state["dec_kv"])) != 0.0

    # Resetting recurrent state every 500 ms never exits the 900 ms CNN warm-up.
    reset_outputs = session.run(
        None,
        {"features": model_features[:5][None], **initial_state()},
    )[0]
    assert np.count_nonzero(reset_outputs) == 0
    print("PASS: stateful LS-EEND detected four changing speaker tracks over 40 seconds")


if __name__ == "__main__":
    main()
