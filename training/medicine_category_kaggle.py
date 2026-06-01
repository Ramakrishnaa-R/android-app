# Medicine category classifier — 8 classes (accuracy-focused)
#
# Expected folder layout under your Kaggle dataset:
#   Cream/
#   Drops/
#   Injection/
#   Lotion/
#   Other/
#   Pill/
#   Powder/
#   Tonic/
#
# Outputs (download from /kaggle/working):
#   medicine_category.tflite
#   medicine_category_labels.txt
#
# Copy both into app/src/main/assets/ then rebuild the Android app.

import json
from pathlib import Path

import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers

print("TensorFlow:", tf.__version__)
print("GPUs:", tf.config.list_physical_devices("GPU"))

# --- Config ---
IMG_SIZE = (224, 224)
BATCH_SIZE = 32
EPOCHS = 12
FINE_TUNE_EPOCHS = 6
SEED = 42
MIN_IMAGES_WARN = 80

# Local run:  python training/medicine_category_kaggle.py
#   Put images in training/dataset/Cream/, Pill/, etc. and set DATA_DIR below.
OUT_DIR = Path(__file__).resolve().parent / "output"
KAGGLE_INPUT = Path("/kaggle/input")
_default_local = Path(__file__).resolve().parent / "dataset"
DATA_DIR = _default_local if _default_local.is_dir() else None

# Label order must match Android medicine_category_labels.txt
EXPECTED_CLASSES = [
    "Cream",
    "Drops",
    "Injection",
    "Lotion",
    "Other",
    "Pill",
    "Powder",
    "Tonic",
]

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def count_images(folder: Path) -> int:
    return sum(
        1
        for file in folder.rglob("*")
        if file.is_file() and file.suffix.lower() in IMAGE_EXTENSIONS
    )


def looks_like_imagefolder(folder: Path) -> bool:
    if not folder.is_dir():
        return False
    class_dirs = [child for child in folder.iterdir() if child.is_dir()]
    usable = [child for child in class_dirs if count_images(child) > 0]
    return len(usable) >= 2


def find_imagefolder_root(input_root: Path) -> Path:
    candidates = []
    for folder in [input_root, *input_root.rglob("*")]:
        if looks_like_imagefolder(folder):
            candidates.append((count_images(folder), folder))
    if not candidates:
        raise FileNotFoundError(
            "No ImageFolder dataset found. Use class folders: " + ", ".join(EXPECTED_CLASSES)
        )
    candidates.sort(reverse=True, key=lambda item: item[0])
    return candidates[0][1]


def warn_class_counts(data_dir: Path) -> None:
    print("\nPer-class image counts:")
    for name in sorted([p.name for p in data_dir.iterdir() if p.is_dir()]):
        count = count_images(data_dir / name)
        flag = "  ⚠ low" if count < MIN_IMAGES_WARN else ""
        print(f"  {name}: {count}{flag}")
    print()


if DATA_DIR and Path(DATA_DIR).is_dir():
    data_dir = Path(DATA_DIR)
elif KAGGLE_INPUT.is_dir():
    data_dir = find_imagefolder_root(KAGGLE_INPUT)
else:
    raise FileNotFoundError(
        f"No dataset found. Create folders under {_default_local}/Pill/, /Tonic/, etc. "
        "or set DATA_DIR in this script."
    )
print("DATA_DIR:", data_dir)
print("Total images:", count_images(data_dir))
warn_class_counts(data_dir)

train_ds = tf.keras.utils.image_dataset_from_directory(
    data_dir,
    validation_split=0.2,
    subset="training",
    seed=SEED,
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
)

val_ds = tf.keras.utils.image_dataset_from_directory(
    data_dir,
    validation_split=0.2,
    subset="validation",
    seed=SEED,
    image_size=IMG_SIZE,
    batch_size=BATCH_SIZE,
)

class_names = train_ds.class_names
num_classes = len(class_names)
print("Detected classes:", class_names)

labels_path = OUT_DIR / "medicine_category_labels.txt"
labels_path.write_text("\n".join(class_names), encoding="utf-8")

AUTOTUNE = tf.data.AUTOTUNE
train_ds = train_ds.cache().shuffle(1000, seed=SEED).prefetch(AUTOTUNE)
val_ds = val_ds.cache().prefetch(AUTOTUNE)

# Class weights for imbalance
train_labels = np.concatenate([labels.numpy() for _, labels in train_ds])
class_counts = np.bincount(train_labels, minlength=num_classes)
total = class_counts.sum()
class_weight = {
    int(i): float(total / (num_classes * max(1, class_counts[i])))
    for i in range(num_classes)
}
print("Class weights:", class_weight)

data_augmentation = keras.Sequential(
    [
        layers.RandomFlip("horizontal_and_vertical"),
        layers.RandomRotation(0.12),
        layers.RandomZoom(0.15),
        layers.RandomTranslation(0.06, 0.06),
        layers.RandomContrast(0.15),
        layers.RandomBrightness(0.15),
    ],
    name="package_augmentation",
)

base_model = keras.applications.EfficientNetB0(
    input_shape=IMG_SIZE + (3,),
    include_top=False,
    weights="imagenet",
)
base_model.trainable = False

inputs = keras.Input(shape=IMG_SIZE + (3,), name="image")
x = data_augmentation(inputs)
x = keras.applications.efficientnet.preprocess_input(x)
x = base_model(x, training=False)
x = layers.GlobalAveragePooling2D()(x)
x = layers.Dropout(0.35)(x)
outputs = layers.Dense(num_classes, activation="softmax", name="category")(x)

model = keras.Model(inputs, outputs)
model.compile(
    optimizer=keras.optimizers.AdamW(learning_rate=1e-3, weight_decay=1e-5),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)
model.summary()

callbacks = [
    keras.callbacks.ModelCheckpoint(
        filepath=str(OUT_DIR / "best_medicine_category.keras"),
        monitor="val_accuracy",
        mode="max",
        save_best_only=True,
    ),
    keras.callbacks.EarlyStopping(
        monitor="val_accuracy",
        mode="max",
        patience=4,
        restore_best_weights=True,
    ),
    keras.callbacks.ReduceLROnPlateau(
        monitor="val_loss",
        factor=0.5,
        patience=2,
        min_lr=1e-6,
    ),
]

history = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS,
    class_weight=class_weight,
    callbacks=callbacks,
)

# Fine-tune top layers
base_model.trainable = True
for layer in base_model.layers[:-40]:
    layer.trainable = False

model.compile(
    optimizer=keras.optimizers.AdamW(learning_rate=1e-5, weight_decay=1e-5),
    loss="sparse_categorical_crossentropy",
    metrics=["accuracy"],
)

model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=FINE_TUNE_EPOCHS,
    class_weight=class_weight,
    callbacks=callbacks,
)

# Validation report
val_probs = model.predict(val_ds, verbose=0)
val_true = np.concatenate([y for _, y in val_ds], axis=0)
val_pred = np.argmax(val_probs, axis=1)
val_acc = (val_true == val_pred).mean()
print(f"\nValidation accuracy: {val_acc:.3f}")

print("\nPer-class recall:")
for i, name in enumerate(class_names):
    mask = val_true == i
    if mask.sum() == 0:
        print(f"  {name}: no val samples")
        continue
    recall = (val_pred[mask] == i).mean()
    print(f"  {name}: {recall:.2f} ({mask.sum()} val images)")

confusion = tf.math.confusion_matrix(val_true, val_pred, num_classes=num_classes)
print("\nConfusion matrix (rows=true, cols=pred):")
print("Labels:", class_names)
print(confusion.numpy())

# Export TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()
tflite_path = OUT_DIR / "medicine_category.tflite"
tflite_path.write_bytes(tflite_model)

meta = {
    "image_size": IMG_SIZE,
    "classes": class_names,
    "val_accuracy": float(val_acc),
    "note": "EfficientNetB0, 8-class medicine package classifier",
}
(OUT_DIR / "medicine_category_meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

print("\nSaved:", tflite_path)
print("Saved:", labels_path)
print("TFLite MB:", round(tflite_path.stat().st_size / (1024 * 1024), 2))
