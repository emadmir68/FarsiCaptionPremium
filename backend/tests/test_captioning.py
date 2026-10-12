from app.captioning import Word, filter_words, group_words, to_srt, to_ass


def test_silence_gap_breaks_caption_and_non_speech_is_removed():
    words = [
        Word("سلام", 0.10, 0.35),
        Word("دوستان", 0.38, 0.70),
        Word("[موسیقی]", 0.80, 1.10),
        Word("امروز", 2.00, 2.25),
        Word("اینجاییم", 2.28, 2.70),
    ]
    speech = [(0.0, 0.9), (1.9, 2.8)]
    filtered = filter_words(words, speech)
    assert [w.text for w in filtered] == ["سلام", "دوستان", "امروز", "اینجاییم"]
    caps = group_words(filtered, max_words=5)
    assert len(caps) == 2
    assert caps[0].text == "سلام دوستان"
    assert caps[0].end < 1.0
    assert caps[1].start >= 1.9


def test_max_words_for_zeemo_style_chunks():
    words = [Word(f"کلمه{i}", i * 0.25, i * 0.25 + 0.2) for i in range(11)]
    caps = group_words(words, max_words=5, max_chars=200, max_duration=10)
    assert [len(c.text.split()) for c in caps] == [5, 5, 1]


def test_subtitle_formats_are_valid_enough():
    caps = group_words([Word("سلام", 0.1, 0.4), Word("دنیا", 0.45, 0.8)])
    srt = to_srt(caps)
    ass = to_ass(caps)
    assert "00:00:00,100" in srt
    assert "سلام دنیا" in srt
    assert "[Events]" in ass
    assert "Dialogue:" in ass
