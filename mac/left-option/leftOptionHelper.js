// Left Option as a dictation key (Typeless-style): tap to start/stop, hold to talk. OpenWhispr's native listener only knows
// right-side modifiers, so a small helper reports Left Option gestures. Launching it from
// here makes macOS attribute it to OpenWhispr, so it uses OpenWhispr's Accessibility grant.
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");
const { app } = require("electron");

const BIN = process.env.OPENWHISPR_LEFT_OPTION_BIN || path.join(require("os").homedir(), ".local/bin/OWLeftOption");

module.exports = function startLeftOption(windowManager) {
  if (process.platform !== "darwin" || !fs.existsSync(BIN)) return;
  const logPath = path.join(app.getPath("userData"), "left-option.log");
  const log = (line) => {
    try { fs.appendFileSync(logPath, `${new Date().toISOString()} ${line}\n`); } catch {}
  };
  const lifecycle = () => windowManager.getAgentDictationPillState().lifecycle;
  let ownsHold = false;
  let child = null;
  let quitting = false;
  let restarts = 0;

  const handle = (gesture) => {
    const state = lifecycle();
    log(`${gesture} state=${state}`);
    switch (gesture) {
      case "TAP":
        ownsHold = false;
        windowManager.sendToggleDictation();
        break;
      case "HOLD_START":
        // Holding while a take is already running ends it, like a tap would.
        ownsHold = state === "idle";
        windowManager.sendToggleDictation();
        break;
      case "HOLD_STOP":
        if (ownsHold && lifecycle() !== "idle") windowManager.sendToggleDictation();
        ownsHold = false;
        break;
    }
  };

  const start = () => {
    child = spawn(BIN, [], { stdio: ["ignore", "pipe", "pipe"] });
    let buffer = "";
    child.stdout.on("data", (chunk) => {
      buffer += chunk.toString();
      let i;
      while ((i = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, i).trim();
        buffer = buffer.slice(i + 1);
        if (line === "READY") { restarts = 0; log("helper ready"); }
        else if (line) { try { handle(line); } catch (e) { log(`gesture failed: ${e.message}`); } }
      }
    });
    child.stderr.on("data", (chunk) => log(`helper: ${chunk.toString().trim()}`));
    child.on("exit", (code) => {
      child = null;
      log(`helper exited code=${code}`);
      if (!quitting && restarts++ < 5) setTimeout(start, 3000 * restarts);
    });
  };

  app.on("will-quit", () => { quitting = true; child?.kill(); });
  start();
};
