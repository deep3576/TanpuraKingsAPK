import Foundation

struct PianoKey: Hashable {
    let name: String
    let isSharp: Bool
}

let octaveKeys: [PianoKey] = [
    PianoKey(name: "C",  isSharp: false), PianoKey(name: "C#", isSharp: true),
    PianoKey(name: "D",  isSharp: false), PianoKey(name: "D#", isSharp: true),
    PianoKey(name: "E",  isSharp: false), PianoKey(name: "F",  isSharp: false),
    PianoKey(name: "F#", isSharp: true),  PianoKey(name: "G",  isSharp: false),
    PianoKey(name: "G#", isSharp: true),  PianoKey(name: "A",  isSharp: false),
    PianoKey(name: "A#", isSharp: true),  PianoKey(name: "B",  isSharp: false)
]

let MAX_ACTIVE_NOTES = 3
