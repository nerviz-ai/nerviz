// nerviz-config — a pane over `ArchHook.jar config`. Nothing here decides anything.
//
//   /nerviz-config   opens a pane with every setting the project owns in its own .claude/
//                    (`config list`), in the nerviz identity: the brand blue marks only what
//                    is off its default. A change is a button, then a dialog, then
//                    `config set` or `config unset` — never a write of this mod's own.
//
// Why this is a mod (Form 9 of claude-code-architect-designer): axis 1 — a project owner did
// not know the settings existed, and one edited a file by hand with a wrong key. The closest
// rejected form was the `/project-config` skill alone: it spends a turn and a model to list
// and change what one command already answers, and the pane spends neither. The guarantee
// beneath is `config` itself — it validates every value before it writes, `guard` refuses a
// direct write of a registered file, and `doctor` gates a malformed one; this mod does not
// load in `claude -p`, VS Code, a cloud session or under `--safe-mode`, and every setting is
// still reachable through the skill and the command. Tested against Claude Code 2.1.293 and 2.1.308.
//
// The one write a mod here makes is `config set|unset` through `$.process.run`: it is no file
// write of the mod's (`$.fs.write` stays forbidden), it is the registry's own writer, and it
// refuses before it touches anything. `mods.$comment_calls` names this exception.
//
// Design: .claude/decisions/0187-project-config-registry-skill-and-mod.md. No hook on a
// `gating_events` event: the mod holds no refusal, so it needs no `.catch`.
//
// The engine reads `on(...)` and `$.noun.method(...)` from source, so they are spelled
// literally, and helpers that take `$` are top-level functions in this file. The pure parts
// — what to draw, in which words, in which blue — live beside it and never see `$`.

import type { EngineInterface, Register } from 'claude-code'
import { changed, effective, paneLines, parseList, plainText, show } from './model.ts'
import type { Action, ConfigFile, Key, Line, Model, Seg, View } from './model.ts'
import { brandOf } from './palettes.ts'
import { langOf, wordsOf } from './words.ts'
import type { Lang } from './words.ts'

const JAR = '.claude/hooks/ArchHook.jar'
const PANE = 'nerviz-config'
const INPUT = 'nerviz-config-input'

let model: Model = { status: 'failed', files: [], detail: '' }
let view: View = { expanded: [], pages: {}, edit: null, refusal: [] }
let theme: unknown = null
let lang: Lang = 'en'

export const register: Register = (on) => {
  on('session.start', async ($, e, next) => {
    const result = await next(e)
    const root = await $.session.root()
    // No project jar, no command: the mod stays silent where `config` does not exist.
    if (await $.fs.exists(root + '/' + JAR)) {
      try {
        await $.command.register({
          name: 'nerviz-config',
          description: 'The project settings in a pane, without spending a turn',
          immediate: true,
        })
      } catch {
        // The name is taken; /project-config still lists and changes the same settings.
      }
    }
    return result
  })

  on('command.run', { command: 'nerviz-config' }, async ($, e, next) => {
    view = { expanded: [], pages: {}, edit: null, refusal: [] }
    await load($)
    if ((await $.session.surfaces()).length > 0) {
      const opened = await $.ui.open({ id: PANE, title: 'nerviz config', focus: true, closeOnEscape: true })
      if (opened.isPlaced) return {}
    }
    return { text: plainText(model) }
  })

  on('ui.render', { component: 'Pane' }, ($, e, next) => {
    if (e.requestId !== PANE) return next(e)
    const els = $.ui.resolve(e)
    const { Box } = els
    const lines = paneLines(wordsOf(lang), model, view)
    return Box({
      flexDirection: 'column',
      paddingX: 1,
      children: lines.map((line, i) => row($, els, line, i)),
    })
  })
}

// ---- What the pane draws ---------------------------------------------------

function row($: EngineInterface, els: any, line: Line, index: number) {
  const { Box, Text } = els
  const children = line.length === 0 ? [Text({ children: ' ' })] : line.map((seg, j) => piece($, els, seg, index + '-' + j))
  return Box({ key: 'l' + index, flexDirection: 'row', children })
}

function piece($: EngineInterface, els: any, seg: Seg, key: string) {
  const { Text, Button, Input } = els
  if (seg.input !== undefined) {
    const at = seg.input
    return Input({
      key: INPUT,
      value: at.value,
      submitLabel: wordsOf(lang).save,
      autoFocus: true,
      onInput: (value: string) => typing(at.id, at.key, value),
      onSubmit: (value: string) => submit($, at.id, at.key, value),
    })
  }
  if (seg.act !== undefined) {
    const act = seg.act
    return Button({ key: 'b' + key, label: seg.t, plain: true, onPress: () => press($, act) })
  }
  return Text({ ...tone(seg), wrap: 'truncate-end', children: seg.t })
}

function tone(seg: Seg): Record<string, unknown> {
  switch (seg.tone) {
    case 'bold':
      return { bold: true }
    case 'dim':
      return { dimColor: true }
    case 'brand':
      return { color: brandOf(theme) }
    case 'error':
      return { color: 'error' }
    case 'warning':
      return { color: 'warning' }
    case 'inactive':
      return { color: 'inactive' }
    default:
      return {}
  }
}

// ---- What `config` says ----------------------------------------------------

async function load($: EngineInterface) {
  try {
    const rows = await $.config.list()
    theme = rows.find((r) => r.key === 'theme')?.value ?? null
    lang = langOf(rows.find((r) => r.key === 'language')?.value, Intl.DateTimeFormat().resolvedOptions().locale)
  } catch {
    theme = null
  }
  await refresh($)
}

async function refresh($: EngineInterface) {
  try {
    const run = await $.process.run(['java', '-jar', JAR, 'config', 'list'], {
      cwd: await $.session.root(),
      timeoutMs: 20000,
    })
    const out = (run.stdout + run.stderr).trim()
    const files = run.exitCode === 0 ? parseList(run.stdout) : null
    if (files !== null) model = { status: 'ok', files, detail: '' }
    else if (out.includes('Unknown mode')) model = { status: 'oldjar', files: [], detail: '' }
    else model = { status: 'failed', files: [], detail: out.split('\n')[0] ?? '' }
  } catch {
    model = { status: 'failed', files: [], detail: 'java did not start' }
  }
  $.ui.invalidate('ui.render')
}

// ---- What a press does -----------------------------------------------------

function find(id: string, key: string): { file: ConfigFile; key: Key } | null {
  const file = model.files.find((f) => f.id === id)
  const k = file?.keys.find((x) => x.key === key)
  return file && k ? { file, key: k } : null
}

async function press($: EngineInterface, act: Action) {
  const w = wordsOf(lang)
  view.refusal = []
  try {
    if (act.kind === 'set') {
      const at = find(act.id, act.key)
      if (at === null) return
      const question = w.askSet(at.file.path, at.key.key, show(effective(at.key)), act.value) + '\n' + at.key.effect
      await change($, question, ['set', act.id, act.key, act.value], w.written(act.id, act.key, act.value))
    } else if (act.kind === 'unset') {
      const at = find(act.id, act.key)
      if (at === null) return
      const question = w.askUnset(act.key, show(at.key.default)) + '\n' + w.askUnsetHint
      await change($, question, ['unset', act.id, act.key], w.restored(act.id, act.key))
    } else if (act.kind === 'edit') {
      const at = find(act.id, act.key)
      view.edit = { id: act.id, key: act.key, text: at !== null && changed(at.key) ? show(at.key.value) : '' }
    } else if (act.kind === 'expand') {
      view.expanded = view.expanded.includes(act.id) ? view.expanded.filter((x) => x !== act.id) : [...view.expanded, act.id]
      view.pages[act.id] = 0
    } else if (act.kind === 'page') {
      view.pages[act.id] = Math.max(0, (view.pages[act.id] ?? 0) + act.by)
    } else if (act.kind === 'refresh') {
      view.edit = null
      await refresh($)
    } else if (act.kind === 'doctor') {
      await $.prompt.fill({ text: '/arch-doctor' })
    } else if (act.kind === 'adopt') {
      await $.prompt.fill({ text: '/arch-adopt' })
    } else if (act.kind === 'close') {
      await $.ui.close({ id: PANE })
      return
    }
  } catch {
    // A dialog the person dismissed, or a pane that is already gone: nothing was written.
  }
  $.ui.invalidate('ui.render')
}

function typing(id: string, key: string, text: string) {
  view.edit = { id, key, text }
}

async function submit($: EngineInterface, id: string, key: string, raw: string) {
  const w = wordsOf(lang)
  const at = find(id, key)
  if (at === null) return
  const text = raw.trim()
  view.refusal = []
  try {
    if (text === '') {
      await change($, w.askUnset(key, show(at.key.default)) + '\n' + w.askUnsetHint, ['unset', id, key], w.restored(id, key))
    } else {
      await change($, w.askSet(at.file.path, key, show(effective(at.key)), text) + '\n' + at.key.effect, ['set', id, key, text], w.written(id, key, text))
    }
  } catch {
    // Dismissed dialog.
  }
  $.ui.invalidate('ui.render')
}

// The dialog first, then the one writer. A refusal (exit 2) is shown as `config` printed it.
async function change($: EngineInterface, question: string, args: string[], done: string) {
  const w = wordsOf(lang)
  const answer = await $.ui.ask(question, { header: 'config', options: [w.write, w.cancel] })
  if (answer !== w.write) return
  try {
    const run = await $.process.run(['java', '-jar', JAR, 'config', ...args], {
      cwd: await $.session.root(),
      timeoutMs: 20000,
    })
    if (run.exitCode === 0) {
      view.edit = null
      await refresh($)
      $.ui.toast(done)
    } else {
      view.refusal = (run.stdout + run.stderr).split('\n').filter((l) => l.trim() !== '')
    }
  } catch {
    view.refusal = [w.failed]
  }
}
