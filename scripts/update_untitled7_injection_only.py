import json
from pathlib import Path


NOTEBOOK = Path(r"C:\Users\Admin\Downloads\Untitled7.ipynb")


def code_cell(source):
    return {
        "cell_type": "code",
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": [line + "\n" for line in source.strip("\n").split("\n")],
    }


def markdown_cell(source):
    return {
        "cell_type": "markdown",
        "metadata": {},
        "source": [line + "\n" for line in source.strip("\n").split("\n")],
    }


with NOTEBOOK.open("r", encoding="utf-8") as f:
    nb = json.load(f)

# Drop trailing empty code cells so the new runnable section is easy to find.
while nb.get("cells") and nb["cells"][-1].get("cell_type") == "code":
    if not "".join(nb["cells"][-1].get("source", [])).strip():
        nb["cells"].pop()
    else:
        break

nb["cells"].extend(
    [
        markdown_cell(
            """
## Injection-only training

This section trains only from the `INJECTION` folder. It uses the subfolder name as the medicine class, so your Drive folder should look like:

`/content/drive/MyDrive/Mobile-Captured Pharmaceutical Medication Packages/INJECTION/Ceftriaxone 1 Vial/*.jpg`

For now your injection data appears to contain one medicine class. That is fine for a quick pipeline test, but accuracy will not be meaningful until you add at least two injection medicine folders.
"""
        ),
        code_cell(
            """
from google.colab import drive
drive.mount('/content/drive')
"""
        ),
        code_cell(
            """
import os
import random
import numpy as np
import pandas as pd
from pathlib import Path

import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers

print("TensorFlow:", tf.__version__)
print("GPU:", tf.config.list_physical_devices("GPU"))

SEED = 42
IMG_SIZE = 160
BATCH_SIZE = 16
EPOCHS = 3

random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)
"""
        ),
        code_cell(
            """
# Change this only if you uploaded the dataset to a different Google Drive path.
DATASET_ROOT = Path("/content/drive/MyDrive/Mobile-Captured Pharmaceutical Medication Packages")
INJECTION_DIR = DATASET_ROOT / "INJECTION"

print("Injection folder:", INJECTION_DIR)
print("Exists:", INJECTION_DIR.exists())

if not INJECTION_DIR.exists():
    raise FileNotFoundError(
        f"Could not find {INJECTION_DIR}. Upload the dataset folder to Google Drive or update DATASET_ROOT."
    )
"""
        ),
        code_cell(
            """
# Build dataframe from INJECTION folder only.
# Each direct child folder under INJECTION is treated as one medicine class.
image_exts = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

rows = []
for class_dir in sorted([p for p in INJECTION_DIR.iterdir() if p.is_dir()]):
    medicine_name = class_dir.name
    for img_path in class_dir.rglob("*"):
        if img_path.suffix.lower() in image_exts:
            rows.append({
                "full_path": str(img_path),
                "medicine_name": medicine_name,
                "group": "injection",
            })

df = pd.DataFrame(rows)

if df.empty:
    raise ValueError(f"No images found inside {INJECTION_DIR}")

class_names = sorted(df["medicine_name"].unique())
class_to_id = {name: idx for idx, name in enumerate(class_names)}
id_to_class = {idx: name for name, idx in class_to_id.items()}
df["label"] = df["medicine_name"].map(class_to_id).astype("int32")

print("Images:", len(df))
print("Injection medicine classes:", len(class_names))
print(class_names)
print(df["medicine_name"].value_counts())
df.head()
"""
        ),
        code_cell(
            """
# Train/validation split.
# For one-class smoke tests, stratify is not useful, so this handles both 1-class and multi-class cases.
from sklearn.model_selection import train_test_split

if len(df) < 2:
    raise ValueError("Need at least 2 images to create a train/validation split.")

stratify = df["label"] if df["label"].value_counts().min() >= 2 and len(class_names) > 1 else None

train_df, val_df = train_test_split(
    df,
    test_size=0.2,
    random_state=SEED,
    shuffle=True,
    stratify=stratify,
)

print("Train images:", len(train_df))
print("Validation images:", len(val_df))
"""
        ),
        code_cell(
            """
def load_image(path, label):
    img = tf.io.read_file(path)
    img = tf.image.decode_image(img, channels=3, expand_animations=False)
    img = tf.image.resize(img, (IMG_SIZE, IMG_SIZE))
    img = tf.cast(img, tf.float32)
    return img, label


def make_ds(dataframe, training=False):
    ds = tf.data.Dataset.from_tensor_slices(
        (
            dataframe["full_path"].values,
            dataframe["label"].values.astype("int32"),
        )
    )
    ds = ds.map(load_image, num_parallel_calls=tf.data.AUTOTUNE)
    if training:
        ds = ds.shuffle(min(len(dataframe), 512), seed=SEED)
    return ds.batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)


train_ds = make_ds(train_df, training=True)
val_ds = make_ds(val_df, training=False)
"""
        ),
        code_cell(
            """
# Fast transfer-learning model.
# If there is only one injection class, this trains a smoke-test binary head.
# Add more injection medicine folders later for real medicine-name classification.
num_classes = len(class_names)
single_class_mode = num_classes == 1

augmentation = keras.Sequential([
    layers.RandomFlip("horizontal"),
    layers.RandomRotation(0.05),
    layers.RandomZoom(0.10),
    layers.RandomContrast(0.10),
])

base = keras.applications.MobileNetV3Small(
    input_shape=(IMG_SIZE, IMG_SIZE, 3),
    include_top=False,
    weights="imagenet",
)
base.trainable = False

inputs = keras.Input(shape=(IMG_SIZE, IMG_SIZE, 3))
x = augmentation(inputs)
x = keras.applications.mobilenet_v3.preprocess_input(x)
x = base(x, training=False)
x = layers.GlobalAveragePooling2D()(x)
x = layers.Dropout(0.2)(x)

if single_class_mode:
    outputs = layers.Dense(1, activation="sigmoid")(x)
    loss = "binary_crossentropy"
else:
    outputs = layers.Dense(num_classes, activation="softmax")(x)
    loss = "sparse_categorical_crossentropy"

model = keras.Model(inputs, outputs)
model.compile(
    optimizer=keras.optimizers.Adam(1e-3),
    loss=loss,
    metrics=["accuracy"],
)

model.summary()
"""
        ),
        code_cell(
            """
history = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS,
)

if single_class_mode:
    print(
        "Only one injection medicine class was found. Training completed as a pipeline smoke test; "
        "add more injection class folders for meaningful classification accuracy."
    )
"""
        ),
        code_cell(
            """
SAVE_DIR = Path("/content/drive/MyDrive/medicine_models")
SAVE_DIR.mkdir(parents=True, exist_ok=True)

model_path = SAVE_DIR / "injection_medicine_classifier.keras"
classes_path = SAVE_DIR / "injection_class_names.csv"

model.save(model_path)
pd.Series(class_names).to_csv(classes_path, index=False, header=False)

print("Saved model:", model_path)
print("Saved classes:", classes_path)
"""
        ),
        code_cell(
            """
def predict_injection_medicine(image_path, top_k=3):
    img = tf.io.read_file(str(image_path))
    img = tf.image.decode_image(img, channels=3, expand_animations=False)
    img = tf.image.resize(img, (IMG_SIZE, IMG_SIZE))
    img = tf.expand_dims(tf.cast(img, tf.float32), axis=0)

    pred = model.predict(img, verbose=0)

    if single_class_mode:
        return [{
            "medicine_name": class_names[0],
            "confidence": float(pred[0][0]),
            "note": "single-class injection smoke test",
        }]

    probs = pred[0]
    top_ids = probs.argsort()[-top_k:][::-1]
    return [
        {
            "medicine_name": id_to_class[int(i)],
            "confidence": float(probs[i]),
        }
        for i in top_ids
    ]


sample_path = val_df.iloc[0]["full_path"]
print(sample_path)
predict_injection_medicine(sample_path)
"""
        ),
    ]
)

with NOTEBOOK.open("w", encoding="utf-8") as f:
    json.dump(nb, f, ensure_ascii=False, indent=1)

print(f"Updated {NOTEBOOK}")
