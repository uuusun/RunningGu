#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import normalize_graph_metadata as normalizer


class GraphMetadataNormalizerTest(unittest.TestCase):
    def test_normalizes_binary_and_text_properties_without_changing_size(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            graph = Path(temporary)
            binary = (
                b"\x00\x02GH\x00\x00"
                b"datareader.import.date=2026-09-03T14:15:42Z\n"
                b"prepare.lm.date.run=2026-09-03T14:17:48Z\n"
            )
            text = (
                b"datareader.import.date=2026-09-03T14:15:42Z\n"
                b"datareader.data.date=2026-09-01T20:20:50Z\n"
                b"prepare.lm.date.run=2026-09-03T14:17:48Z\n"
            )
            (graph / "properties").write_bytes(binary)
            (graph / "properties.txt").write_bytes(text)

            normalizer.normalize_graph(graph)

            normalized_binary = (graph / "properties").read_bytes()
            normalized_text = (graph / "properties.txt").read_bytes()
            self.assertEqual(len(normalized_binary), len(binary))
            self.assertEqual(len(normalized_text), len(text))
            self.assertEqual(normalized_binary.count(normalizer.FIXED_TIMESTAMP), 2)
            self.assertEqual(normalized_text.count(normalizer.FIXED_TIMESTAMP), 2)
            self.assertIn(b"datareader.data.date=2026-09-01T20:20:50Z", normalized_text)

    def test_fails_when_required_metadata_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            graph = Path(temporary)
            (graph / "properties").write_bytes(
                b"datareader.import.date=2026-09-03T14:15:42Z\n"
            )
            (graph / "properties.txt").write_bytes(b"")

            with self.assertRaisesRegex(ValueError, "prepare.lm.date.run"):
                normalizer.normalize_graph(graph)


if __name__ == "__main__":
    unittest.main()
