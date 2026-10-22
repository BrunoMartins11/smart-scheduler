// shortcut: one locale (pt-PT) and no runtime switch, add an EN dictionary and a locale switch when EN ships
export const messages = {
  'app.name': 'Smart Scheduler',
  'nav.label': 'Principal',
  'nav.schedule': 'Escala',
  'nav.team': 'Equipa',
  'nav.shifts': 'Turnos',
  'nav.rules': 'Regras',
  'auth.login': 'Entrar',
  'auth.logout': 'Sair',
  'auth.error': 'Não foi possível verificar a sessão. Tente novamente mais tarde.',
} as const

export type MessageKey = keyof typeof messages

export const t = (key: MessageKey): string => messages[key]
