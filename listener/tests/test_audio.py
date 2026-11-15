import numpy as np

from luna_listener.audio import PREBUFFER, AudioIO


def pull(a, n):
    out = np.zeros((n, 1), dtype=np.int16)
    a._on_speaker(out, n, None, None)
    return out[:, 0]


def test_waits_for_a_cushion_then_plays():
    a = AudioIO()
    a.play(np.ones(160, dtype=np.int16))
    assert not pull(a, 320).any()                          # 10 ms is not enough: silence, nothing consumed
    a.play(np.ones(PREBUFFER, dtype=np.int16))
    assert pull(a, 320).all()


def test_rebuffers_after_running_dry():
    a = AudioIO()
    a.play(np.ones(PREBUFFER, dtype=np.int16))
    assert pull(a, PREBUFFER).all()
    assert not pull(a, 320).any()                          # dry -> primed False
    a.play(np.ones(320, dtype=np.int16))
    assert not pull(a, 320).any()                          # not enough again: waits


def test_output_gain_scales_and_clips():
    a = AudioIO(output_gain=2.0)
    a.play(np.full(PREBUFFER, 1000, dtype=np.int16))
    assert (pull(a, 100) == 2000).all()
    b = AudioIO(output_gain=4.0)
    b.play(np.full(PREBUFFER, 20000, dtype=np.int16))
    assert (pull(b, 100) == 32767).all()                     # clipped, not wrapped around
