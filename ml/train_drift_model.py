"""Train a drift classifier; evaluate a held-out seed and lower fault severity."""
from pathlib import Path
import json
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ["missing_ratio", "type_error_ratio", "range_error_ratio", "schema_change", "relative_mean_shift"]


def dataset(seed, count, severity=1.0):
    rng = np.random.default_rng(seed)
    labels = rng.integers(0, 2, count)
    values = np.zeros((count, len(FEATURES)))
    families = rng.integers(0, 5, count)
    for i, (label, family) in enumerate(zip(labels, families)):
        values[i] = [rng.uniform(0, .04), rng.uniform(0, .02), rng.uniform(0, .025), 0, rng.uniform(0, .35)]
        if label:
            if family == 0:
                values[i, 0] = rng.uniform(.2, .9) * severity
            elif family == 1:
                values[i, 1] = rng.uniform(.18, .8) * severity
            elif family == 2:
                values[i, 2] = rng.uniform(.2, .8) * severity
            elif family == 3:
                values[i, 3] = 1
            else:
                values[i, 4] = rng.uniform(.8, 2.0) * severity
        elif family == 4:
            # A legitimate route change can shift distributions without schema corruption.
            values[i, 4] = rng.uniform(.3, .7)
    return values, labels


def main():
    train_x, train_y = dataset(17, 8000)
    test_x, test_y = dataset(97, 2400, .85)
    model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000, random_state=17))
    model.fit(train_x, train_y)
    predicted = model.predict(test_x)
    baseline = ((test_x[:, 0] > .5) | (test_x[:, 1] > .5) | (test_x[:, 2] > .5) | (test_x[:, 3] > 0)).astype(int)
    scaler, classifier = model.steps[0][1], model.steps[1][1]
    exported = {"features": FEATURES, "mean": scaler.mean_.tolist(), "scale": scaler.scale_.tolist(), "coefficients": classifier.coef_[0].tolist(), "intercept": float(classifier.intercept_[0]), "threshold": .5, "trainingSeed": 17, "evaluationSeed": 97}
    path = ROOT / "api/src/main/resources/drift-model.json"
    path.write_text(json.dumps(exported, indent=2) + "\n", encoding="utf-8")
    report = {"dataset": "synthetic fault injection with held-out seed and lower test severity", "trainingSamples": len(train_y), "testSamples": len(test_y), "model": classification_report(test_y, predicted, output_dict=True), "baseline": classification_report(test_y, baseline, output_dict=True), "confusionMatrix": confusion_matrix(test_y, predicted).tolist(), "limitation": "Synthetic evaluation does not establish accuracy on real OEM data. The model is advisory and cannot approve mappings."}
    out = ROOT / "evidence"
    out.mkdir(exist_ok=True)
    (out / "ml-evaluation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"modelF1": report["model"]["1"]["f1-score"], "baselineF1": report["baseline"]["1"]["f1-score"], "testSamples": len(test_y)}))


if __name__ == "__main__":
    main()
