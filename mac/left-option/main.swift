// Left Option gestures for OpenWhispr (Typeless-style: tap to toggle, hold to talk).
// OpenWhispr launches this helper itself (leftOptionHelper.js), so it runs under
// OpenWhispr's own Accessibility permission and needs no separate grant.
// It only reports gestures on stdout; OpenWhispr decides what to do with them:
//   TAP          Left Option pressed and released alone, quickly   -> toggle dictation
//   HOLD_START   Left Option held alone past the hold threshold     -> start push-to-talk
//   HOLD_STOP    release after HOLD_START                           -> stop push-to-talk
// Option used with any other key, click or modifier (Option+Space, Option+arrow, ...) produces
// no gesture and is passed through untouched.

import Cocoa

let leftOption: Int64 = 58
let holdThreshold = 0.35 // seconds; a quick tap is ~100 ms

var optionDown = false
var spoiled = false     // another key, click or modifier joined this press
var holding = false     // HOLD_START was sent for this press
var holdTimer: DispatchWorkItem?
var tap: CFMachPort?

func emit(_ gesture: String) { print(gesture); fflush(stdout) }
func log(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }

func spoil() {
    spoiled = true
    holdTimer?.cancel()
}

func callback(proxy: CGEventTapProxy, type: CGEventType, event: CGEvent, refcon: UnsafeMutableRawPointer?) -> Unmanaged<CGEvent>? {
    if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
        if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
        return Unmanaged.passUnretained(event)
    }
    let code = event.getIntegerValueField(.keyboardEventKeycode)
    switch type {
    case .flagsChanged:
        if code == leftOption {
            let down = event.flags.contains(.maskAlternate)
            if down && !optionDown {
                optionDown = true; holding = false
                spoiled = !event.flags.intersection([.maskCommand, .maskControl, .maskShift]).isEmpty
                if !spoiled {
                    let work = DispatchWorkItem {
                        guard optionDown, !spoiled else { return }
                        holding = true
                        emit("HOLD_START")
                    }
                    holdTimer = work
                    DispatchQueue.main.asyncAfter(deadline: .now() + holdThreshold, execute: work)
                }
            } else if !down && optionDown {
                optionDown = false
                holdTimer?.cancel()
                if holding { emit("HOLD_STOP") }
                else if !spoiled { emit("TAP") }
                holding = false
            }
        } else if optionDown {
            spoil() // Option+Shift, Option+Command, ...
        }
    case .leftMouseDown, .rightMouseDown, .otherMouseDown:
        if optionDown { spoil() } // Option+click
    case .keyDown, .keyUp:
        guard optionDown else { break }
        if type == .keyDown { spoil() }
    default:
        break
    }
    return Unmanaged.passUnretained(event)
}

let mask = [CGEventType.flagsChanged, .keyDown, .keyUp, .leftMouseDown, .rightMouseDown, .otherMouseDown]
    .reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }

func makeTap(_ options: CGEventTapOptions) -> CFMachPort? {
    CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: options,
                      eventsOfInterest: mask, callback: callback, userInfo: nil)
}

// Listen only: the helper never blocks or changes keys.
let created = makeTap(.listenOnly) ?? makeTap(.defaultTap)
guard let created else {
    log("event tap unavailable: OpenWhispr needs Accessibility permission")
    exit(2)
}
tap = created
CFRunLoopAddSource(CFRunLoopGetCurrent(), CFMachPortCreateRunLoopSource(nil, created, 0), .commonModes)
CGEvent.tapEnable(tap: created, enable: true)
log("ready")
emit("READY")
// Exit when OpenWhispr goes away (stdout closes).
DispatchQueue.global().async {
    while getppid() != 1 { sleep(2) }
    exit(0)
}
CFRunLoopRun()
