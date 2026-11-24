"""Synthesizes planeMode's original chime sound effects (mono 16-bit 22.05 kHz WAV).
Usage: python3 tools/make_sounds.py app/src/main/res/raw"""
import math, struct, sys, wave, os

SR = 22050

def tone(freq, dur, amp=0.5, attack=0.01, decay=None, partials=((1, 1.0), (2, 0.25), (3, 0.08))):
    n = int(SR * dur); decay = decay or dur / 3.5
    out = []
    for i in range(n):
        t = i / SR
        env = min(1.0, t / attack) * math.exp(-max(0.0, t - attack) / decay)
        s = sum(a * math.sin(2 * math.pi * freq * k * t) for k, a in partials)
        out.append(amp * env * s)
    return out

def sweep(f0, f1, dur, amp=0.35):
    n = int(SR * dur); out = []; ph = 0.0
    for i in range(n):
        t = i / SR; f = f0 + (f1 - f0) * (t / dur)
        ph += 2 * math.pi * f / SR
        env = math.sin(math.pi * t / dur) ** 1.5
        out.append(amp * env * (math.sin(ph) + 0.2 * math.sin(2 * ph)))
    return out

def mix(total, *parts):
    """parts: (start_seconds, samples)"""
    buf = [0.0] * int(SR * total)
    for start, s in parts:
        o = int(start * SR)
        for i, v in enumerate(s):
            if o + i < len(buf): buf[o + i] += v
    peak = max(1e-9, max(abs(v) for v in buf))
    g = min(1.0, 0.85 / peak)
    return [v * g for v in buf]

def save(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in samples))

N = {"C5": 523.25, "D5": 587.33, "E5": 659.25, "F5": 698.46, "G5": 783.99, "A5": 880.0, "B5": 987.77,
     "C6": 1046.5, "E6": 1318.5, "G4": 392.0, "A4": 440.0, "E4": 329.63, "C4": 261.63, "F4": 349.23, "D4": 293.66}
out = sys.argv[1]
S = {
    "snd_gps_acquired": mix(1.0, (0, tone(N["E5"], .5, .4)), (.14, tone(N["A5"], .8, .4))),
    "snd_gps_lost": mix(1.1, (0, tone(N["A5"], .5, .4)), (.18, tone(N["E5"], .5, .4)), (.36, tone(N["C5"], .7, .35))),
    "snd_takeoff_speed": mix(1.3, (0, sweep(300, 900, .7)), (.55, tone(N["G5"], .7, .35))),
    "snd_ten_thousand": mix(1.4, (0, tone(N["C5"], 1.2, .4)), (0, tone(N["G5"], 1.2, .25))),
    "snd_cruise": mix(2.0, (0, tone(N["C5"], 1.2, .35)), (.25, tone(N["E5"], 1.2, .35)), (.5, tone(N["G5"], 1.4, .35))),
    "snd_max_speed": mix(1.0, (0, tone(N["B5"], .35, .35)), (.18, tone(N["B5"], .35, .35)), (.36, tone(N["E6"], .6, .3))),
    "snd_top_of_descent": mix(1.8, (0, tone(N["G5"], 1.0, .35)), (.3, tone(N["E5"], 1.0, .35)), (.6, tone(N["C5"], 1.2, .35))),
    "snd_landing": mix(2.0, (0, tone(N["C4"], 1.5, .35, decay=.6)), (.2, tone(N["G4"], 1.5, .3, decay=.6)), (.45, tone(N["E5"], 1.4, .25))),
    "snd_flight_complete": mix(2.6, (0, tone(N["C5"], 1.0, .3)), (.2, tone(N["E5"], 1.0, .3)), (.4, tone(N["G5"], 1.0, .3)), (.6, tone(N["C6"], 1.8, .3))),
}
# Soft UI "tock" for button and page clicks: very short, low level, gentle attack.
def click():
    n = int(SR * 0.035); out = []
    for i in range(n):
        t = i / SR
        env = min(1.0, t / 0.0015) * math.exp(-t / 0.006)
        out.append(0.5 * env * (math.sin(2 * math.pi * 1650 * t) + 0.35 * math.sin(2 * math.pi * 3300 * t)))
    return out
S["snd_click"] = click()

for name, s in S.items():
    save(os.path.join(out, name + ".wav"), s)
    print(name)
