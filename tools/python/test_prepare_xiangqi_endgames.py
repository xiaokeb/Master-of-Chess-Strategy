"""Curated preview generation must preserve old levels and reject unsafe inputs."""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from audit_chinese_chess_endgames import AuditError, parse_pack
from prepare_xiangqi_endgames import prepare_pack


ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
REVIEW = ROOT / "config/games/xiangqi/reviews/2026-10-02-rook-horse.json"
HORSE_REVIEW = ROOT / "config/games/xiangqi/reviews/2026-10-03-horse-cannon-curated.json"
TRIM_REVIEW = ROOT / "config/games/xiangqi/reviews/2026-10-04-horse-cannon-trimmed.json"
SECTION = "\n# Project-generated rook/cannon and horse/cannon revision."
HORSE_SECTION = "\n# Project-generated horse/cannon review batch."
TRIM_SECTION = "\n# Project-generated horse/cannon trimmed review batch."


class PrepareXiangqiEndgamesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.current = PACK.read_text(encoding="utf-8")
        cls.previous = cls.current.split(TRIM_SECTION, 1)[0]
        cls.older = cls.previous.split(HORSE_SECTION, 1)[0]
        cls.base = cls.current.split(SECTION, 1)[0]
        cls.review = json.loads(REVIEW.read_text(encoding="utf-8"))
        cls.horse_review = json.loads(HORSE_REVIEW.read_text(encoding="utf-8"))
        cls.trim_review = json.loads(TRIM_REVIEW.read_text(encoding="utf-8"))

    def test_reviewed_preview_reproduces_current_pack_byte_for_byte(self) -> None:
        before = PACK.read_bytes()
        prepared, report = prepare_pack(self.base, deepcopy(self.review), ROOT)

        self.assertEqual(self.older, prepared)
        self.assertEqual(before, PACK.read_bytes())
        self.assertEqual("prepared-not-published", report["status"])
        self.assertEqual(31, report["level_count"])
        self.assertEqual(
            "523522889b77a27b6d98e809b9da12da2b170cbd294e6f7952fbb63136ed0875",
            report["preview_sha256"],
        )
        self.assertEqual(
            "d43cc9c44a6099d0a5e439fc4fb51ea34ba0055b199d1ea26607754d24dccedc",
            hashlib.sha256(self.base.encode("utf-8")).hexdigest(),
        )

    def test_horse_cannon_preview_reproduces_current_pack_byte_for_byte(self) -> None:
        prepared, report = prepare_pack(self.older, deepcopy(self.horse_review), ROOT)
        self.assertEqual(self.previous, prepared)
        self.assertEqual(33, report["level_count"])
        self.assertEqual(
            "4a352daed89ef6968c14bbb98aea9b71eb6c5dc8353ab94d1bd462ce48164d93",
            report["preview_sha256"],
        )

    def test_trimmed_preview_reproduces_current_pack_byte_for_byte(self) -> None:
        prepared, report = prepare_pack(self.previous, deepcopy(self.trim_review), ROOT)
        self.assertEqual(self.current, prepared)
        self.assertEqual(35, report["level_count"])
        self.assertEqual(
            "1551ce2a61002326edf15b17f5c3ab1c3e1740b03aedd15148749f0630db6f95",
            report["preview_sha256"],
        )

    def test_explicit_piece_removal_reproves_the_transformed_puzzle(self) -> None:
        review = deepcopy(self.horse_review)
        review["base_sha256"] = hashlib.sha256(self.previous.encode("utf-8")).hexdigest()
        row = review["curated"][0]
        review["curated"] = [row]
        row.update({
            "ordinal": 23, "id": "xq-medium-012", "order": 12,
            "title": "双马夹宫", "drop_pieces": [[7, 4, "RED", "SOLDIER"]],
        })

        prepared, report = prepare_pack(self.previous, review, ROOT)
        level = parse_pack(prepared)[1][-1]
        self.assertEqual("xq-medium-012", level.id)
        self.assertNotIn((7, 4, "RED", "SOLDIER"), level.pieces)
        self.assertEqual(34, report["level_count"])
        self.assertEqual(self.current, PACK.read_text(encoding="utf-8"))

    def test_piece_removal_rejects_missing_moving_and_necessary_pieces(self) -> None:
        for removed, message in (
            ([[8, 8, "RED", "SOLDIER"]], "declared red source piece"),
            ([[6, 3, "RED", "HORSE"]], "principal variation mover"),
            ([[5, 1, "RED", "CANNON"]], "transformed puzzle lacks"),
            ([[7, 4, "RED", "SOLDIER"], [7, 4, "RED", "SOLDIER"]], "repeated removal"),
        ):
            with self.subTest(removed=removed):
                review = deepcopy(self.horse_review)
                review["base_sha256"] = hashlib.sha256(self.previous.encode("utf-8")).hexdigest()
                row = review["curated"][0]
                review["curated"] = [row]
                row.update({
                    "ordinal": 23, "id": "xq-medium-012", "order": 12,
                    "title": "双马夹宫", "drop_pieces": removed,
                })
                with self.assertRaisesRegex(AuditError, message):
                    prepare_pack(self.previous, review, ROOT)

    def test_stale_base_source_and_uncurated_entry_are_rejected(self) -> None:
        for field, value, message in (
            ("base_sha256", "0" * 64, "different base pack"),
            ("source_sha256", "0" * 64, "source hash or size changed"),
            ("review_status", "candidate", "explicit curation"),
            ("review_note", "", "explicit curation"),
        ):
            with self.subTest(field=field):
                review = deepcopy(self.review)
                target = review if field == "base_sha256" else review["curated"][0]
                target[field] = value
                with self.assertRaisesRegex(AuditError, message):
                    prepare_pack(self.base, review, ROOT)

    def test_path_traversal_duplicate_and_wrong_chapter_order_are_rejected(self) -> None:
        for field, value, message in (
            ("source", "../private-source.txt", "project candidate directory"),
            ("ordinal", 43, "repeats or misnumbers"),
            ("order", 25, "chapter order"),
            ("id", "xq-easy-099", "ID does not match"),
            ("theme", "多解胜局", "theme disagrees"),
            ("stars", 0, "reward is outside"),
            ("score", 1001, "reward is outside"),
        ):
            with self.subTest(field=field):
                review = deepcopy(self.review)
                review["curated"][0 if field != "ordinal" else 1][field] = value
                with self.assertRaisesRegex(AuditError, message):
                    prepare_pack(self.base, review, ROOT)

    def test_already_bundled_position_cannot_be_prepared_again(self) -> None:
        review = deepcopy(self.review)
        review["base_sha256"] = hashlib.sha256(self.current.encode("utf-8")).hexdigest()
        review["curated"] = [review["curated"][0]]
        review["curated"][0]["order"] = 19
        review["curated"][0]["id"] = "xq-easy-019"
        with self.assertRaisesRegex(AuditError, "candidate duplicates a released board"):
            prepare_pack(self.current, review, ROOT)

    def test_cli_writes_only_a_preview_under_build_directory(self) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT / ".build") as directory:
            preview_dir = Path(directory)
            base = preview_dir / "base-v5.txt"
            review = preview_dir / "review.json"
            output = preview_dir / "preview-v5.txt"
            base.write_text(self.base, encoding="utf-8", newline="\n")
            review.write_text(json.dumps(self.review, ensure_ascii=False), encoding="utf-8")
            command = [
                sys.executable, str(ROOT / "tools/python/prepare_xiangqi_endgames.py"),
                "--base", str(base), "--review", str(review),
                "--preview-output", str(output),
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(self.older, output.read_text(encoding="utf-8"))
            self.assertEqual("prepared-not-published", json.loads(result.stdout)["status"])

            command[-1] = str(PACK)
            rejected = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("preview output must stay", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
