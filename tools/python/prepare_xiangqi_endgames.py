"""Prepare, but never publish, curator-reviewed Xiangqi candidate batches."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

from audit_chinese_chess_endgames import AuditError, DIFFICULTIES, audit, parse_pack
from stage_xiangqi_candidates import HEADER, stage_candidates
from verify_xiangqi_forced_win import principal_variation_wins, prove_red_win


REVIEW_FORMAT = "MOCS-XQ-REVIEW|1"
SOURCE_PATH = re.compile(r"config/games/xiangqi/candidates/[A-Za-z0-9._-]+\.txt")
SHA256 = re.compile(r"[0-9a-f]{64}")
REVIEW_FIELDS = {
    "source", "source_sha256", "ordinal", "id", "difficulty", "order",
    "title", "theme", "stars", "score", "review_status", "curator", "review_note",
}
OPTIONAL_REVIEW_FIELDS = {"drop_pieces"}


def _candidate_block(stream: str, ordinal: int) -> str:
    """Select the exact raw block; only renumber its header for one-item staging."""
    blocks: list[list[str]] = []
    for line in stream.splitlines():
        if line.startswith("CANDIDATE "):
            if HEADER.fullmatch(line) is None:
                raise AuditError("candidate source has a malformed header")
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
        elif line.strip():
            raise AuditError("candidate source begins without a header")
    if not 1 <= ordinal <= len(blocks):
        raise AuditError("review ordinal is not present in the source")
    block = blocks[ordinal - 1]
    if not block[0].startswith(f"CANDIDATE {ordinal} "):
        raise AuditError("candidate source ordinals are not contiguous")
    block[0] = re.sub(r"^CANDIDATE [1-9][0-9]* ", "CANDIDATE 1 ", block[0], count=1)
    return "\n".join(block) + "\n"


def _reviewed_piece_lines(staged: dict, row: dict) -> list[str]:
    """Only remove named source pieces; the transformed board is re-proved below."""
    lines = staged["pieces"]
    if "drop_pieces" not in row:
        return lines
    drops = row["drop_pieces"]
    if not isinstance(drops, list) or not 1 <= len(drops) <= 8:
        raise AuditError("piece removal must list 1..8 source pieces")
    original = {
        (int(parts[1]), int(parts[2]), parts[3], parts[4]): line
        for line in lines for parts in [line.split("|")]
    }
    red_sources = {
        tuple(map(int, move.split("|")[1:3]))
        for move in staged["principal_variation"][::2]
    }
    removed: set[tuple[int, int, str, str]] = set()
    for item in drops:
        if (not isinstance(item, list) or len(item) != 4 or
                type(item[0]) is not int or type(item[1]) is not int or
                not isinstance(item[2], str) or not isinstance(item[3], str)):
            raise AuditError("piece removal must use exact source coordinates and type")
        piece = tuple(item)
        if piece in removed:
            raise AuditError("review repeats a repeated removal")
        if piece not in original or item[2] != "RED" or item[3] == "GENERAL":
            raise AuditError("removal must name a declared red source piece")
        if (item[0], item[1]) in red_sources:
            raise AuditError("cannot remove a principal variation mover")
        removed.add(piece)
    return [line for piece, line in original.items() if piece not in removed]


def prepare_pack(base: str, review: dict, repository: Path) -> tuple[str, dict]:
    """Render an auditable preview from explicit curation, leaving all files untouched."""
    if not isinstance(review, dict) or set(review) != {
        "format", "base_sha256", "section_comment", "curated",
    } or review["format"] != REVIEW_FORMAT:
        raise AuditError("review manifest envelope is invalid")
    base_hash = hashlib.sha256(base.encode("utf-8")).hexdigest()
    if review["base_sha256"] != base_hash:
        raise AuditError("review is for a different base pack")
    comment = review["section_comment"]
    if (not isinstance(comment, str) or not comment.startswith("# ") or
            len(comment) > 160 or "\n" in comment or "\r" in comment):
        raise AuditError("review section comment is invalid")
    rows = review["curated"]
    if not isinstance(rows, list) or not rows or len(rows) > 100:
        raise AuditError("review must contain 1..100 curated entries")
    _, existing = parse_pack(base)
    next_orders = {name: max((level.order for level in existing if level.difficulty == index), default=0) + 1
                   for index, name in enumerate(DIFFICULTIES)}
    sources: dict[str, tuple[str, str]] = {}
    seen_source_ordinals: set[tuple[str, int]] = set()
    blocks: list[str] = []
    current = base
    for row in rows:
        if (not isinstance(row, dict) or
                set(row) not in (REVIEW_FIELDS, REVIEW_FIELDS | OPTIONAL_REVIEW_FIELDS)):
            raise AuditError("curated entry fields are incomplete or unknown")
        source = row["source"]
        if not isinstance(source, str) or SOURCE_PATH.fullmatch(source) is None:
            raise AuditError("candidate source must stay in the project candidate directory")
        ordinal = row["ordinal"]
        if type(ordinal) is not int or ordinal < 1 or (source, ordinal) in seen_source_ordinals:
            raise AuditError("review repeats or misnumbers a source candidate")
        seen_source_ordinals.add((source, ordinal))
        source_hash = row["source_sha256"]
        if not isinstance(source_hash, str) or SHA256.fullmatch(source_hash) is None:
            raise AuditError("candidate source hash is invalid")
        path = repository / source
        if source not in sources:
            try:
                data = path.read_bytes()
                stream = data.decode("utf-8")
            except (OSError, UnicodeDecodeError) as error:
                raise AuditError("candidate source cannot be read as UTF-8") from error
            if len(data) > 2 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != source_hash:
                raise AuditError("candidate source hash or size changed")
            sources[source] = stream, source_hash
        elif sources[source][1] != source_hash:
            raise AuditError("one source has conflicting review hashes")
        if (row["review_status"] != "curated" or
                not isinstance(row["curator"], str) or not row["curator"].strip() or
                not isinstance(row["review_note"], str) or not row["review_note"].strip()):
            raise AuditError("candidate lacks explicit curation and rationale")
        difficulty = row["difficulty"]
        order = row["order"]
        if difficulty not in DIFFICULTIES or type(order) is not int or order != next_orders[difficulty]:
            raise AuditError("review difficulty or next chapter order is invalid")
        level_id = f"xq-{difficulty}-{order:03d}"
        if row["id"] != level_id:
            raise AuditError("review ID does not match its chapter order")
        staged = stage_candidates(_candidate_block(sources[source][0], ordinal), current)["candidates"][0]
        pieces = _reviewed_piece_lines(staged, row)
        red_moves = staged["red_moves"]
        theme = "唯一一步杀" if red_moves == 1 else "两步强制胜"
        if row["theme"] != theme:
            raise AuditError("review theme disagrees with the proven solution length")
        title = row["title"]
        if not isinstance(title, str) or not title.strip() or "|" in title or "\n" in title:
            raise AuditError("review title is invalid")
        stars, score = row["stars"], row["score"]
        if (type(stars) is not int or not 1 <= stars <= 5 or
                type(score) is not int or not 1 <= score <= 1000):
            raise AuditError("review reward is outside the v5 schema")
        block = "\n".join([
            f"LEVEL|{level_id}|{DIFFICULTIES.index(difficulty)}|{order}|{title}|{theme}|RED|{red_moves}|{stars}|{score}|{len(pieces)}|BONUS",
            *pieces, *staged["principal_variation"], "END",
        ])
        blocks.append(block)
        # Recheck each following source against all previously accepted boards.
        current = base + "\n" + comment + "\n" + "\n\n".join(blocks) + "\n"
        parsed = parse_pack(current)[1]
        if "drop_pieces" in row:
            transformed = parsed[-1]
            if not principal_variation_wins(transformed, require_check=red_moves == 1):
                raise AuditError("transformed puzzle lacks its declared winning line")
            proof = prove_red_win(transformed, red_moves)
            if proof.status != "complete" or proof.winning_first_moves != (transformed.moves[0],):
                raise AuditError("transformed puzzle lacks a unique proven first move")
            if red_moves == 2:
                shallow = prove_red_win(transformed, 1)
                if shallow.status != "complete" or shallow.winning_first_moves:
                    raise AuditError("transformed puzzle lacks its two-move-only property")
        next_orders[difficulty] += 1
    report = audit(current)
    return current, {
        "status": "prepared-not-published", "base_sha256": base_hash,
        "preview_sha256": report["source_sha256"], "level_count": report["level_count"],
        "curated_ids": [row["id"] for row in rows],
    }


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--preview-output", type=Path, required=True)
    args = parser.parse_args()
    preview_root = (repository / ".build").resolve()
    output = args.preview_output.resolve()
    if not output.is_relative_to(preview_root):
        parser.error("preview output must stay under the repository .build directory")
    try:
        prepared, report = prepare_pack(
            args.base.read_text(encoding="utf-8"),
            json.loads(args.review.read_text(encoding="utf-8")), repository,
        )
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(prepared, encoding="utf-8", newline="\n")
    except (AuditError, OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        parser.exit(1, f"reviewed preview failed: {error}\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
