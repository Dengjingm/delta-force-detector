#!/usr/bin/env python3
import unittest
from remap_and_prepare import remap_label_text


class RemapLabelTest(unittest.TestCase):
    def test_keeps_head_and_person(self):
        text = "0 0.5 0.5 0.1 0.1\n1 0.4 0.6 0.2 0.4\n"
        body, counts, skipped = remap_label_text(text, merge_enemy=False, src_name="t")
        self.assertEqual(skipped, 0)
        self.assertEqual(counts["0"], 1)
        self.assertEqual(counts["1"], 1)
        lines = body.strip().splitlines()
        self.assertTrue(lines[0].startswith("0 "))
        self.assertTrue(lines[1].startswith("1 "))

    def test_merge_enemy_collapses_both(self):
        text = "0 0.5 0.5 0.1 0.1\n1 0.4 0.6 0.2 0.4\n"
        body, counts, skipped = remap_label_text(text, merge_enemy=True, src_name="t")
        self.assertEqual(skipped, 0)
        self.assertEqual(counts["0"], 2)
        self.assertTrue(all(line.startswith("0 ") for line in body.strip().splitlines()))

    def test_skips_unknown_class(self):
        text = "2 0.5 0.5 0.1 0.1\n0 0.2 0.2 0.1 0.1\n"
        body, counts, skipped = remap_label_text(text, merge_enemy=False, src_name="t")
        self.assertEqual(skipped, 1)
        self.assertEqual(counts["0"], 1)
        self.assertNotIn("1", counts)
        self.assertTrue(body.startswith("0 "))


if __name__ == "__main__":
    unittest.main()
