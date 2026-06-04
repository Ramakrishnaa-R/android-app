import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from numeric_ocr_correction import correct_numeric_ocr


class NumericOcrCorrectionTest(unittest.TestCase):
    def test_separated_strength_and_unit(self):
        self.assertEqual(correct_numeric_ocr("DOLO GSO MG"), "DOLO 650 MG")

    def test_standalone_strength_after_brand(self):
        self.assertEqual(correct_numeric_ocr("DOLO GSO"), "DOLO 650")

    def test_joined_strength_and_unit(self):
        self.assertEqual(correct_numeric_ocr("PARACETAMOL SOOMG"), "PARACETAMOL 500MG")

    def test_joined_volume_and_unit(self):
        self.assertEqual(correct_numeric_ocr("SYRUP IOOML"), "SYRUP 100ML")

    def test_common_ocr_number_shapes(self):
        self.assertEqual(correct_numeric_ocr("8SO MG"), "850 MG")
        self.assertEqual(correct_numeric_ocr("I5O MG"), "150 MG")
        self.assertEqual(correct_numeric_ocr("GSOMG"), "650MG")

    def test_lowercase_g_maps_to_9_only_in_numeric_context(self):
        self.assertEqual(correct_numeric_ocr("DROPS go ML"), "DROPS 90 ML")
        self.assertEqual(correct_numeric_ocr("gel"), "gel")

    def test_invalid_name_tokens_are_preserved(self):
        self.assertEqual(correct_numeric_ocr("ABC"), "ABC")
        self.assertEqual(correct_numeric_ocr("AUGMENTIN CLAV"), "AUGMENTIN CLAV")

    def test_valid_numbers_are_preserved_when_dosage_like(self):
        self.assertEqual(correct_numeric_ocr("AUGMENTIN 625 MG"), "AUGMENTIN 625 MG")
        self.assertEqual(correct_numeric_ocr("BOTTLE 100ML"), "BOTTLE 100ML")

    def test_decimal_whitelist_strength(self):
        self.assertEqual(correct_numeric_ocr("NATRILAM I2.5 MG"), "NATRILAM 12.5 MG")
        self.assertEqual(correct_numeric_ocr("DOSE 2.5MG"), "DOSE 2.5MG")

    def test_percentage_unit(self):
        self.assertEqual(correct_numeric_ocr("LOTION S%"), "LOTION 5%")

    def test_punctuation_is_preserved(self):
        self.assertEqual(correct_numeric_ocr("DOLO GSO-MG, TAB"), "DOLO 650-MG, TAB")


if __name__ == "__main__":
    unittest.main()
