// Runs xterm's parser without opening a browser, canvas, or GUI.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { Terminal } = require(process.argv[2]);
const { Unicode11Addon } = require(process.argv[3]);
const write = (terminal, text) => new Promise(resolve => terminal.write(text, resolve));
const create = (cols, rows) => {
  const terminal = new Terminal({ cols, rows, allowProposedApi: true });
  terminal.loadAddon(new Unicode11Addon());
  terminal.unicode.activeVersion = '11';
  return terminal;
};
const view = terminal => ({
  lines: Array.from({ length: terminal.rows }, (_, row) =>
    terminal.buffer.active.getLine(terminal.buffer.active.baseY + row).translateToString(true)),
  row: terminal.buffer.active.cursorY,
  column: terminal.buffer.active.cursorX,
});
(async () => {
  for (const scenario of JSON.parse(fs.readFileSync(0, 'utf8'))) {
    const live = create(scenario.cols, scenario.rows);
    let restored;
    try {
      await write(live, scenario.before);
      for (const size of scenario.resizes) live.resize(size[0], size[1]);
      restored = create(live.cols, live.rows);
      await write(restored, scenario.snapshot);
      await write(live, scenario.after);
      await write(restored, scenario.after);
      assert.deepEqual(view(restored), view(live), scenario.name);
    } finally {
      live.dispose();
      restored?.dispose();
    }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
