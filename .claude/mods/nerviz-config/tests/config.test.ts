// What nerviz-config claims, against the engine's own `$` (claude plugin test). `config list`
// and `config set` are stubs here: what the jar prints and refuses is proven by
// .claude/.ci/ProjectConfigTest.java; this file proves what the mod does with it — what it
// draws, which presses write, and that nothing is written before the dialog says so.

import { expect, test } from 'claude-code/testing'

type Seed = { exited?: unknown; budget?: unknown; git?: unknown; available?: boolean; problems?: string[] }

function list(seed: Seed = {}) {
  const many = Array.from({ length: 10 }, (_, i) => ({
    key: 'skills.s' + i, type: 'boolean', default: false, value: null, effect: 'audited', fixed: i === 1 || undefined,
  }))
  many[0] = { ...many[0], value: seed.git ?? null, default: true } as (typeof many)[0]
  return JSON.stringify({
    files: [
      {
        id: 'compose-gate', path: '.claude/compose-gate.json', exists: seed.exited !== undefined, summary: 's',
        doctor_label: 'Compose overrides', available: true, in_force: '', problems: seed.problems ?? [],
        keys: [{ key: 'exited', type: 'enum', values: ['block', 'warn'], default: 'block', value: seed.exited ?? null, effect: 'warn turns a stopped service into a warning' }],
      },
      {
        id: 'plan-limits', path: '.claude/audit-usage/plan-limits.json', exists: false, summary: 's',
        doctor_label: 'Plan limits', available: seed.available ?? true, in_force: '', problems: [],
        keys: [{ key: 'pro.weekly_tokens', type: 'positive_number_or_null', default: 'unset', value: seed.budget ?? null, effect: 'budget' }],
      },
      {
        id: 'audited', path: '.claude/audit-usage/audited.json', exists: false, summary: 's',
        doctor_label: 'Audit overrides', available: true, in_force: '', problems: [], keys: many,
      },
    ],
  })
}

const START = { cwd: '/repo', surface: 'terminal', isInteractive: true } as const
const PANE = {
  plugin: 'nerviz-config', surface: 'terminal', component: 'Pane', requestId: 'nerviz-config',
  props: {},
} as const

type Setup = { seed?: Seed; theme?: string; answer?: string; jar?: boolean; stdout?: string; exitCode?: number; set?: { exitCode: number; stdout: string }; surfaces?: string[] }

function engine(on, o: Setup, calls: string[][], asked: string[], registered: string[]) {
  on('session.root', () => ({ value: '/repo' }))
  on('session.surfaces', () => ({ value: o.surfaces ?? ['terminal'] }))
  on('fs.exists', () => ({ value: o.jar ?? true }))
  on('command.register', (_$, e) => { registered.push(e.name); return { value: undefined } })
  on('config.list', () => ({ value: [{ key: 'theme', value: o.theme ?? 'dark' }, { key: 'language', value: 'default' }] }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  on('session.start', (_$, e) => ({ cwd: e.cwd }))
  on('process.run', (_$, e) => {
    calls.push([...e.argv])
    if (e.argv.includes('list')) return { value: { exitCode: o.exitCode ?? 0, stdout: o.stdout ?? list(o.seed), stderr: '' } }
    return { value: { exitCode: o.set?.exitCode ?? 0, stdout: o.set?.stdout ?? '', stderr: '' } }
  })
  on('tool.call', (_$, e) => {
    if (e.tool !== 'AskUserQuestion') return {}
    const question = String(e.questions[0].question)
    asked.push(question)
    return { result: { questions: e.questions, answers: { [question]: o.answer ?? 'Write' } } }
  })
}

async function open($, on, o: Setup = {}) {
  const calls: string[][] = []
  const asked: string[] = []
  const registered: string[] = []
  engine(on, { ...o, surfaces: o.surfaces }, calls, asked, registered)
  await $.session.start(START)
  const answer = await $.command.run({ command: 'nerviz-config', args: '' })
  const ui = await $.ui.mount(PANE)
  return { calls, asked, registered, answer, ui }
}

const writes = (calls: string[][]) => calls.filter((c) => c.includes('set') || c.includes('unset'))

test('the pane names every file and puts the N of the logo on top', async ($, on) => {
  const { ui } = await open($, on)
  expect(await ui.find({ type: 'Text', text: /Compose overrides/ })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: /Plan limits/ })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: /nerviz config/ })).toBeDefined()
  expect((await ui.find({ type: 'Text', text: /╲/ }))?.props.color).toBe('#8EA2FF')
  await ui.unmount()
})

test('the brand blue marks only what is off its default', async ($, on) => {
  const { ui } = await open($, on, { seed: { exited: 'warn' } })
  expect((await ui.find({ type: 'Text', text: /● warn/ }))?.props.color).toBe('#8EA2FF')
  await ui.unmount()
})

test('a value at its default is drawn without the brand blue', async ($, on) => {
  const { ui } = await open($, on)
  expect((await ui.find({ type: 'Text', text: /● block/ }))?.props.color).toBeUndefined()
  await ui.unmount()
})

test('the light theme gets the light blue', async ($, on) => {
  const { ui } = await open($, on, { theme: 'light' })
  expect((await ui.find({ type: 'Text', text: /╲/ }))?.props.color).toBe('#2747D9')
  await ui.unmount()
})

test('an unknown theme falls back to the theme key', async ($, on) => {
  const { ui } = await open($, on, { theme: 'auto' })
  expect((await ui.find({ type: 'Text', text: /╲/ }))?.props.color).toBe('suggestion')
  await ui.unmount()
})

test('a press asks first and writes through config set only on Write', async ($, on) => {
  const { ui, calls, asked } = await open($, on, { answer: 'Write' })
  expect(writes(calls)).toHaveLength(0)
  await ui.press({ key: (await ui.find({ type: 'Button', text: /○ warn/ }))!.key! })
  expect(asked).toHaveLength(1)
  expect(asked[0]).toContain('block → warn')
  expect(writes(calls)).toEqual([['java', '-jar', '.claude/hooks/ArchHook.jar', 'config', 'set', 'compose-gate', 'exited', 'warn']])
  await ui.unmount()
})

test('Cancel writes nothing', async ($, on) => {
  const { ui, calls, asked } = await open($, on, { answer: 'Cancel' })
  await ui.press({ key: (await ui.find({ type: 'Button', text: /○ warn/ }))!.key! })
  expect(asked).toHaveLength(1)
  expect(writes(calls)).toHaveLength(0)
  await ui.unmount()
})

test('a refusal from config is shown as it came, and nothing else changes', async ($, on) => {
  const refusal = '❌ config: .claude/compose-gate.json would not be valid — nothing written.'
  const { ui } = await open($, on, { set: { exitCode: 2, stdout: refusal } })
  await ui.press({ key: (await ui.find({ type: 'Button', text: /○ warn/ }))!.key! })
  expect(await ui.find({ type: 'Text', text: /config refused|config recusou/ })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: /would not be valid/ })).toBeDefined()
  await ui.unmount()
})

test('a key off its default offers reset, which runs config unset', async ($, on) => {
  const { ui, calls } = await open($, on, { seed: { exited: 'warn' } })
  const reset = await ui.find({ type: 'Button', text: /reset|restaurar/ })
  expect(reset).toBeDefined()
  await ui.press({ key: reset!.key! })
  expect(writes(calls)).toEqual([['java', '-jar', '.claude/hooks/ArchHook.jar', 'config', 'unset', 'compose-gate', 'exited']])
  await ui.unmount()
})

test('a fixed key has no button, and a long file shows only what changed until asked', async ($, on) => {
  const { ui } = await open($, on, { seed: { git: false } })
  expect(await ui.find({ type: 'Text', text: /skills.s1/ })).toBeUndefined()
  await ui.press({ key: (await ui.find({ type: 'Button', text: /show all|mostrar/ }))!.key! })
  const fixed = await ui.find({ type: 'Text', text: /skills.s1/ })
  expect(fixed).toBeDefined()
  // Page 1 holds s0..s7: s0 is off its default (reset), s1 is fixed, the other six turn on.
  expect(await ui.findAll({ type: 'Button', text: /turn on|ligar/ })).toHaveLength(6)
  await ui.unmount()
})

test('a file whose audit trail is off offers nothing to change', async ($, on) => {
  const { ui } = await open($, on, { seed: { available: false } })
  expect(await ui.find({ type: 'Text', text: /audit trail off|trilha de auditoria desligada/ })).toBeDefined()
  expect(await ui.find({ type: 'Button', text: /set|definir/ })).toBeUndefined()
  await ui.unmount()
})

test('a number is typed in a field and an empty field resets it', async ($, on) => {
  const { ui, calls } = await open($, on, { seed: { budget: 5000 } })
  await ui.press({ key: (await ui.find({ type: 'Button', text: /edit|editar/ }))!.key! })
  await ui.input({ key: 'nerviz-config-input', text: '', kind: 'submit' })
  expect(writes(calls)).toEqual([['java', '-jar', '.claude/hooks/ArchHook.jar', 'config', 'unset', 'plan-limits', 'pro.weekly_tokens']])
  await ui.unmount()
})

test('a jar without the config mode says to run /arch-adopt', async ($, on) => {
  const { ui } = await open($, on, { stdout: 'Unknown mode: config\n' })
  expect(await ui.find({ type: 'Text', text: /no config mode|não tem o modo config/ })).toBeDefined()
  expect(await ui.find({ type: 'Button', text: /\/arch-adopt/ })).toBeDefined()
  await ui.unmount()
})

test('without a project jar the command is never registered', async ($, on) => {
  const calls: string[][] = []
  const registered: string[] = []
  engine(on, { jar: false }, calls, [], registered)
  await $.session.start(START)
  expect(registered).toHaveLength(0)
})

test('where nothing draws, /nerviz-config prints the listing and writes nothing', async ($, on) => {
  const { answer, calls } = await open($, on, { surfaces: [], seed: { exited: 'warn' } })
  expect(answer.text).toContain('compose-gate')
  expect(answer.text).toContain('exited = warn')
  expect(answer.text).toContain('config set <id> <key> <value>')
  expect(writes(calls)).toHaveLength(0)
})
