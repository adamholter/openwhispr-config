// Reports whether the focused element of an app accepts typed text, even when it
// holds a selection. OpenWhispr's own probe answers NOT_EDITABLE for any field with
// a live selection (it was built for caret pastes), which sent every selection edit
// to the assistant popup. Output: EDITABLE, NOT_EDITABLE or UNKNOWN.
import ApplicationServices
import Foundation

guard CommandLine.arguments.count == 2, let pid = pid_t(CommandLine.arguments[1]) else {
  print("UNKNOWN"); exit(2)
}
let app = AXUIElementCreateApplication(pid)
AXUIElementSetMessagingTimeout(app, 0.5)
// Chromium and Electron keep their tree dormant until asked.
AXUIElementSetAttributeValue(app, "AXManualAccessibility" as CFString, kCFBooleanTrue)

func attr(_ el: AXUIElement, _ name: String) -> CFTypeRef? {
  var v: CFTypeRef?
  return AXUIElementCopyAttributeValue(el, name as CFString, &v) == .success ? v : nil
}
func settable(_ el: AXUIElement, _ name: String) -> Bool {
  var ok: DarwinBoolean = false
  return AXUIElementIsAttributeSettable(el, name as CFString, &ok) == .success && ok.boolValue
}

var focused: AXUIElement?
for _ in 0..<3 {
  if let v = attr(app, kAXFocusedUIElementAttribute) { focused = (v as! AXUIElement); break }
  usleep(50_000)
}
guard var el = focused else { print("UNKNOWN"); exit(0) }

let textRoles: Set<String> = ["AXTextField", "AXTextArea", "AXComboBox", "AXSearchField"]
// Walk up a few levels: web views often focus a child of the editable element.
for _ in 0..<6 {
  let role = attr(el, kAXRoleAttribute) as? String ?? ""
  if (attr(el, "AXEditableAncestor") != nil) { print("EDITABLE"); exit(0) }
  if textRoles.contains(role) || settable(el, kAXSelectedTextAttribute) || settable(el, kAXValueAttribute) && role != "AXSlider" {
    print("EDITABLE"); exit(0)
  }
  if role == "AXWebArea" || role == "AXWindow" || role == "AXApplication" { break }
  guard let parent = attr(el, kAXParentAttribute) else { break }
  el = parent as! AXUIElement
}
print("NOT_EDITABLE")
