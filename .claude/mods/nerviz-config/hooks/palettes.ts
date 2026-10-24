// The one place a hex value may live in this mod (decision 0187, option B of the style round).
// Everything else the mod draws is a theme key. These are the blue of brand/nerviz-mark*.svg,
// one per theme the mod knows; any other theme (`auto`, an ANSI or custom one) gets the
// `suggestion` theme key, so the person's own palette decides.

const DARK = '#8EA2FF'
const LIGHT = '#2747D9'
const FALLBACK = 'suggestion'

const BY_THEME: Record<string, string> = {
  dark: DARK,
  'dark-daltonized': DARK,
  light: LIGHT,
  'light-daltonized': LIGHT,
}

/** The brand blue for the `theme` row of `$.config.list()`, or a theme key when it is unknown. */
export function brandOf(theme: unknown): string {
  return typeof theme === 'string' ? (BY_THEME[theme] ?? FALLBACK) : FALLBACK
}
