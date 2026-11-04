from luna.opening import DEFAULT_REPLY, plan_opening

CFG = {"wake_words": {
    "hey_luna": {"reply": "Greet warmly."},
    "hey_rulon": {"say": "Rulon is busy right now.", "instructions": "He is a caregiver."}}}


def test_default_opening_without_attributes():
    o = plan_opening(CFG, {})
    assert o.say is None and o.reply == DEFAULT_REPLY and o.extra == ""


def test_wake_word_chooses_reply_or_verbatim_phrase():
    assert plan_opening(CFG, {"luna.wake_word": "hey_luna"}).reply == "Greet warmly."
    o = plan_opening(CFG, {"luna.wake_word": "hey_rulon"})
    assert o.say == "Rulon is busy right now." and o.reply is None and o.extra == "He is a caregiver."


def test_a_call_phrase_beats_the_wake_word_phrase():
    o = plan_opening(CFG, {"luna.wake_word": "hey_rulon", "luna.say": "Time for your pills."})
    assert o.say == "Time for your pills."


def test_unknown_or_malformed_wake_word_falls_back_safely():
    assert plan_opening(CFG, {"luna.wake_word": "nope"}).reply == DEFAULT_REPLY
    assert plan_opening(CFG, {"luna.wake_word": "../../etc"}).wake_word is None
