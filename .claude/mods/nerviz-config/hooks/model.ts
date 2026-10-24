// What `ArchHook.jar config list` prints, as lines the pane draws. Pure: no `$`, no rule. A
// value is "off default" when the file holds it and it differs from the default `config list`
// printed; whether a value is valid is `config set`'s answer, never this file's.

import type { Words } from './words.ts'

export type Key = {
  key: string
  type: string
  values?: string[]
  default: unknown
  value: unknown
  effect: string
  fixed?: boolean
}

export type ConfigFile = {
  id: string
  path: string
  exists: boolean
  summary: string
  doctor_label: string
  available: boolean
  in_force: string
  problems: string[]
  keys: Key[]
}

export type Action =
  | { kind: 'set'; id: string; key: string; value: string }
  | { kind: 'unset'; id: string; key: string }
  | { kind: 'edit'; id: string; key: string }
  | { kind: 'expand'; id: string }
  | { kind: 'page'; id: string; by: number }
  | { kind: 'refresh' }
  | { kind: 'doctor' }
  | { kind: 'adopt' }
  | { kind: 'close' }

export type Tone = 'plain' | 'bold' | 'dim' | 'brand' | 'error' | 'warning' | 'inactive'

export type Seg = { t: string; tone?: Tone; act?: Action; input?: { id: string; key: string; value: string } }
export type Line = Seg[]

export type Status = 'ok' | 'oldjar' | 'failed'

export type Model = { status: Status; files: ConfigFile[]; detail: string }

export type View = {
  expanded: string[]
  pages: Record<string, number>
  edit: { id: string; key: string; text: string } | null
  refusal: string[]
}

export const PAGE = 8
const COLLAPSE_OVER = 8

export function parseList(stdout: string): ConfigFile[] | null {
  try {
    const data = JSON.parse(stdout)
    return Array.isArray(data?.files) ? (data.files as ConfigFile[]) : null
  } catch {
    return null
  }
}

export function changed(key: Key): boolean {
  return key.value !== null && key.value !== undefined && key.value !== key.default
}

export function effective(key: Key): unknown {
  return key.value === null || key.value === undefined ? key.default : key.value
}

const dim = (t: string): Seg => ({ t, tone: 'dim' })
const brand = (t: string): Seg => ({ t, tone: 'brand' })
const bold = (t: string): Seg => ({ t, tone: 'bold' })
const plain = (t: string): Seg => ({ t })
const button = (t: string, act: Action): Seg => ({ t, act })

export function show(value: unknown): string {
  if (value === null || value === undefined) return '—'
  return typeof value === 'string' ? value : JSON.stringify(value)
}

function header(w: Words, sub: string): Line[] {
  return [
    [{ t: '█', tone: 'bold' }, brand('╲'), { t: '  █', tone: 'bold' }, bold('   nerviz config')],
    [{ t: '█ ', tone: 'bold' }, brand('╲'), { t: ' █', tone: 'bold' }, dim('   ' + w.tagline)],
    [{ t: '█  ', tone: 'bold' }, brand('╲'), { t: '█', tone: 'bold' }, dim('   ' + sub)],
    [],
  ]
}

function seal(w: Words, files: number | null, problems: number): Line[] {
  return [
    [],
    [
      { t: 'nerviz ', tone: 'bold' },
      brand('▸ '),
      bold('config'),
      ...(files === null ? [] : [dim(' · ' + w.seal(files, problems))]),
    ],
    [
      dim('/nerviz-config  '),
      button(w.refresh, { kind: 'refresh' }),
      dim(' · '),
      button('doctor', { kind: 'doctor' }),
      dim('   '),
      button(w.close, { kind: 'close' }),
    ],
  ]
}

function enumRow(w: Words, f: ConfigFile, k: Key): Line[] {
  const now = effective(k)
  const options = (k.values ?? []).map((v): Seg[] => {
    if (v === now) return [changed(k) ? brand('● ' + v) : plain('● ' + v)]
    return [button('○ ' + v, { kind: 'set', id: f.id, key: k.key, value: v })]
  })
  const row: Line = [plain(k.key.padEnd(12))]
  options.forEach((o, i) => row.push(...(i > 0 ? [plain('   ')] : []), ...o))
  if (changed(k)) row.push(dim('  · '), button(w.reset, { kind: 'unset', id: f.id, key: k.key }))
  return [row, [dim('            ' + k.effect)]]
}

function boolRow(w: Words, f: ConfigFile, k: Key): Line {
  const on = effective(k) === true
  const mark: Seg = changed(k) ? brand('● ') : dim('○ ')
  const name = k.key.padEnd(24)
  if (k.fixed) {
    return [dim('○ '), { t: name, tone: 'inactive' }, { t: on ? w.on : w.unset, tone: 'inactive' }, { t: '  ' + w.fixed, tone: 'inactive' }]
  }
  const value = on ? w.on + ' ' : w.unset
  const verb = changed(k)
    ? button(w.reset, { kind: 'unset', id: f.id, key: k.key })
    : button(on ? w.turnOff : w.turnOn, { kind: 'set', id: f.id, key: k.key, value: String(!on) })
  const note = changed(k) ? w.classIs(String(k.default)) : w.defaultIs
  return [mark, plain(name), changed(k) ? brand(value) : dim(value), dim('  ' + note.padEnd(11)), verb]
}

function numberRow(w: Words, f: ConfigFile, k: Key, view: View): Line[] {
  const mark: Seg = changed(k) ? brand('● ') : dim('○ ')
  const name = plain(k.key.padEnd(22))
  if (view.edit && view.edit.id === f.id && view.edit.key === k.key) {
    return [
      [mark, name, { t: '', input: { id: f.id, key: k.key, value: view.edit.text } }],
      [dim('   ' + ' '.repeat(22) + w.emptyHint)],
    ]
  }
  const row: Line = [mark, name, changed(k) ? brand(show(k.value) + '  ') : dim('—  ')]
  row.push(button(changed(k) ? w.edit : w.set, { kind: 'edit', id: f.id, key: k.key }))
  if (changed(k)) row.push(dim(' · '), button(w.reset, { kind: 'unset', id: f.id, key: k.key }))
  return [row]
}

function keyRows(w: Words, f: ConfigFile, k: Key, view: View): Line[] {
  if (k.type === 'boolean') return [boolRow(w, f, k)]
  if (k.type === 'positive_number_or_null') return numberRow(w, f, k, view)
  if (k.values && k.values.length > 0) return enumRow(w, f, k)
  return [[dim('○ '), plain(k.key.padEnd(24)), plain(show(effective(k)))]]
}

function section(w: Words, f: ConfigFile, view: View): { node: Seg; tail: Seg; lines: Line[] } {
  const off = f.keys.filter(changed).length
  if (!f.available) {
    return {
      node: { t: '○ ', tone: 'inactive' },
      tail: { t: w.off, tone: 'inactive' },
      lines: [[{ t: w.trailOff, tone: 'inactive' }], [dim(w.trailOffHint)]],
    }
  }
  const lines: Line[] = []
  const long = f.keys.length > COLLAPSE_OVER
  const open = view.expanded.includes(f.id)
  if (!long || !open) {
    const shown = long ? f.keys.filter(changed) : f.keys
    shown.forEach((k) => lines.push(...keyRows(w, f, k, view)))
    if (long) lines.push([dim('  '), button(w.showAll(f.keys.length), { kind: 'expand', id: f.id })])
  } else {
    const page = Math.min(view.pages[f.id] ?? 0, Math.floor((f.keys.length - 1) / PAGE))
    const slice = f.keys.slice(page * PAGE, page * PAGE + PAGE)
    slice.forEach((k) => lines.push(...keyRows(w, f, k, view)))
    const nav: Line = [dim('  ' + w.range(page * PAGE + 1, page * PAGE + slice.length, f.keys.length) + '  ')]
    if (page > 0) nav.push(button('▲ ' + w.prev, { kind: 'page', id: f.id, by: -1 }), dim('  '))
    if ((page + 1) * PAGE < f.keys.length) nav.push(button('▼ ' + w.next, { kind: 'page', id: f.id, by: 1 }), dim('  '))
    nav.push(button(w.onlyChanged, { kind: 'expand', id: f.id }))
    lines.push(nav)
  }
  const invalid = f.problems.length > 0
  const node: Seg = invalid ? { t: '✗ ', tone: 'error' } : off > 0 ? brand('● ') : dim('○ ')
  const tail: Seg = invalid
    ? { t: w.invalid, tone: 'error' }
    : off > 0
      ? dim(w.offDefault(off))
      : dim(f.exists ? w.asDefault : w.notWritten)
  return { node, tail, lines }
}

export function paneLines(w: Words, model: Model, view: View): Line[] {
  if (model.status === 'oldjar') {
    return [
      ...header(w, w.noFiles),
      [{ t: w.oldJar, tone: 'warning' }],
      [dim('  ' + w.oldJarHint)],
      [plain('  '), button('/arch-adopt', { kind: 'adopt' })],
      ...seal(w, null, 0),
    ]
  }
  if (model.status === 'failed') {
    return [
      ...header(w, w.noFiles),
      [{ t: w.failed, tone: 'warning' }],
      ...(model.detail === '' ? [] : [[dim('  ' + model.detail)] as Line]),
      ...seal(w, null, 0),
    ]
  }
  const problems = model.files.reduce((n, f) => n + f.problems.length, 0)
  const off = model.files.reduce((n, f) => n + f.keys.filter(changed).length, 0)
  const out: Line[] = [...header(w, w.files(model.files.length, off))]
  if (view.refusal.length > 0) {
    out.push([{ t: w.refused, tone: 'error' }])
    view.refusal.forEach((l) => out.push([{ t: '  ' + l, tone: 'error' }]))
    out.push([])
  }
  model.files
    .filter((f) => f.problems.length > 0)
    .forEach((f) => {
      out.push([{ t: w.problems(f.problems.length, f.doctor_label), tone: 'error' }])
      f.problems.forEach((p) => out.push([{ t: '  ' + p, tone: 'error' }]))
      out.push([])
    })
  model.files.forEach((f, i) => {
    const last = i === model.files.length - 1
    const first = i === 0
    const lead = model.files.length === 1 ? '── ' : first ? '╭─ ' : last ? '╰─ ' : '├─ '
    const s = section(w, f, view)
    out.push([dim(lead), s.node, bold(f.doctor_label), dim(' · ' + f.path + ' · '), s.tail])
    s.lines.forEach((l) => out.push([dim(last ? '   ' : '│  '), ...l]))
    if (!last) out.push([dim('│')])
  })
  out.push(...seal(w, model.files.length, problems))
  return out
}

/** The same listing as plain text, for a surface that draws nothing (`claude -p`). */
export function plainText(model: Model): string {
  if (model.status !== 'ok') {
    return 'config list did not answer. ' + (model.status === 'oldjar' ? 'Run /arch-adopt to update this .claude/.' : model.detail)
  }
  const out: string[] = []
  model.files.forEach((f) => {
    if (!f.available) {
      out.push(`${f.id}  ${f.path}  audit trail off`)
      return
    }
    const set = f.keys.filter(changed)
    out.push(`${f.id}  ${f.path}  ${f.exists ? (set.length === 0 ? 'default' : set.length + ' off default') : 'not written'}`)
    set.forEach((k) => out.push(`  ${k.key} = ${show(k.value)} (default ${show(k.default)})`))
    f.problems.forEach((p) => out.push('  ! ' + p))
  })
  out.push('Change: java -jar .claude/hooks/ArchHook.jar config set <id> <key> <value>')
  return out.join('\n')
}
