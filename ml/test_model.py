import json
from pathlib import Path
import numpy as np
from train_drift_model import dataset

ROOT = Path(__file__).resolve().parents[1]


def test_exported_model_beats_declared_baseline_on_holdout():
    model = json.loads((ROOT / "api/src/main/resources/drift-model.json").read_text())
    x, y = dataset(97, 2400, .85)
    logits = ((x - np.array(model["mean"])) / np.array(model["scale"])) @ np.array(model["coefficients"]) + model["intercept"]
    probability = 1 / (1 + np.exp(-logits))
    prediction = probability >= .5
    tp = np.sum(prediction & (y == 1))
    f1 = 2 * tp / (np.sum(prediction) + np.sum(y))
    report = json.loads((ROOT / "evidence/ml-evaluation.json").read_text())
    assert abs(f1 - report["model"]["1"]["f1-score"]) < 1e-12
    assert f1 > report["baseline"]["1"]["f1-score"]


def test_training_and_evaluation_seeds_are_distinct():
    x1, _ = dataset(17, 100)
    x2, _ = dataset(97, 100, .85)
    assert not np.array_equal(x1, x2)
