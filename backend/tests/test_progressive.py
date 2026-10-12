from app.captioning import Word, group_words, to_ass


def test_progressive_reveal_never_shows_future_word():
    words = [
        Word("سلام", 0.10, 0.35),
        Word("این", 0.40, 0.55),
        Word("یک", 0.60, 0.72),
        Word("تست", 0.77, 1.00),
    ]
    ass = to_ass(group_words(words, max_words=5), progressive=True)
    lines = [line for line in ass.splitlines() if line.startswith("Dialogue:")]
    assert len(lines) == 4
    assert "سلام" in lines[0]
    assert "این" not in lines[0]
    assert "سلام این" in lines[1]
    assert "تست" not in lines[2]
    assert "سلام این یک تست" in lines[3]
    assert r"\c&H00D7FF&" not in "\n".join(lines)
