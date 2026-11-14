// Every string the mod draws, in pt-BR and en. The language is the `/config` Language row when
// it names one, else the locale of the process.

export type Lang = 'pt' | 'en'

const WORDS = {
  pt: {
    tagline: 'o que este projeto configura no próprio .claude/',
    files: (n: number, off: number) => `${n} arquivos · ${off} fora do padrão`,
    noFiles: 'config indisponível',
    seal: (n: number, problems: number) => `${n} arquivos · ${problems} problemas`,
    offDefault: (n: number) => `${n} fora do padrão`,
    notWritten: 'não existe',
    asDefault: 'no padrão',
    off: 'desligada',
    invalid: 'inválido',
    on: 'on',
    unset: 'off',
    classIs: (v: string) => `classe: ${v}`,
    defaultIs: 'padrão',
    fixed: 'fixo',
    set: 'definir',
    edit: 'editar',
    turnOn: 'ligar',
    turnOff: 'desligar',
    reset: 'restaurar',
    save: 'gravar',
    showAll: (n: number) => `mostrar as ${n}`,
    onlyChanged: 'só os fora do padrão',
    prev: 'anteriores',
    next: 'próximos',
    range: (from: number, to: number, n: number) => `${from}–${to} de ${n}`,
    refresh: 'atualizar',
    close: 'fechar',
    trailOff: 'trilha de auditoria desligada',
    trailOffHint: 'gravar aqui ligaria a trilha: nada a mudar',
    emptyHint: 'tokens faturáveis da janela; vazio = sem projeção',
    problems: (n: number, label: string) => `✗ ${n} problema${n > 1 ? 's' : ''} · doctor › ${label} falha o gate`,
    refused: '✗ config recusou · nada foi gravado',
    oldJar: '! O ArchHook.jar deste projeto não tem o modo config.',
    oldJarHint: 'Ele é anterior à decisão 0187. Atualize o .claude/ do projeto:',
    failed: '! config list não rodou.',
    write: 'Gravar',
    cancel: 'Cancelar',
    askSet: (path: string, key: string, from: string, to: string) => `Gravar ${path}: ${key} ${from} → ${to}?`,
    askUnset: (key: string, to: string) => `Restaurar ${key} ao padrão (${to})?`,
    askUnsetHint: 'Remove a chave do arquivo; um arquivo que fica vazio é apagado.',
    written: (id: string, key: string, to: string) => `${id} · ${key} = ${to} · gravado`,
    restored: (id: string, key: string) => `${id} · ${key} restaurado`,
    change: 'Mudar',
    default: 'padrão',
  },
  en: {
    tagline: 'what this project sets in its own .claude/',
    files: (n: number, off: number) => `${n} files · ${off} off default`,
    noFiles: 'config unavailable',
    seal: (n: number, problems: number) => `${n} files · ${problems} problems`,
    offDefault: (n: number) => `${n} off default`,
    notWritten: 'not written',
    asDefault: 'default',
    off: 'off',
    invalid: 'invalid',
    on: 'on',
    unset: 'off',
    classIs: (v: string) => `class: ${v}`,
    defaultIs: 'default',
    fixed: 'fixed',
    set: 'set',
    edit: 'edit',
    turnOn: 'turn on',
    turnOff: 'turn off',
    reset: 'reset',
    save: 'save',
    showAll: (n: number) => `show all ${n}`,
    onlyChanged: 'only off default',
    prev: 'previous',
    next: 'next',
    range: (from: number, to: number, n: number) => `${from}–${to} of ${n}`,
    refresh: 'refresh',
    close: 'close',
    trailOff: 'audit trail off',
    trailOffHint: 'a write here would switch the trail on: nothing to change',
    emptyHint: 'billable tokens of the window; empty = no projection',
    problems: (n: number, label: string) => `✗ ${n} problem${n > 1 ? 's' : ''} · doctor › ${label} fails the gate`,
    refused: '✗ config refused · nothing written',
    oldJar: "! This project's ArchHook.jar has no config mode.",
    oldJarHint: "It predates decision 0187. Update the project's .claude/:",
    failed: '! config list did not run.',
    write: 'Write',
    cancel: 'Cancel',
    askSet: (path: string, key: string, from: string, to: string) => `Write ${path}: ${key} ${from} → ${to}?`,
    askUnset: (key: string, to: string) => `Reset ${key} to its default (${to})?`,
    askUnsetHint: 'Removes the key from the file; a file left empty is deleted.',
    written: (id: string, key: string, to: string) => `${id} · ${key} = ${to} · written`,
    restored: (id: string, key: string) => `${id} · ${key} reset`,
    change: 'Change',
    default: 'default',
  },
}

export type Words = (typeof WORDS)['pt']

export function wordsOf(lang: Lang): Words {
  return WORDS[lang]
}

/** `language` is the `/config` Language row's value (or undefined), `locale` the process's. */
export function langOf(language: unknown, locale: string): Lang {
  const named = typeof language === 'string' ? language.toLowerCase() : ''
  if (named.startsWith('portugu') || named.startsWith('pt')) return 'pt'
  if (named !== '' && named !== 'default' && named !== 'auto') return 'en'
  return locale.toLowerCase().startsWith('pt') ? 'pt' : 'en'
}
