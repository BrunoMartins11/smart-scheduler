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
  'team.heading': 'Membros da equipa',
  'team.pick': 'Escolher equipa',
  'team.loading': 'A carregar…',
  'team.noTeams': 'Ainda não existem equipas.',
  'team.empty': 'Esta equipa ainda não tem membros.',
  'team.error': 'Não foi possível carregar a equipa. Tente novamente mais tarde.',
  'team.col.name': 'Nome',
  'team.col.email': 'Email',
  'team.col.role': 'Função',
  'team.col.fte': 'FTE (%)',
  'team.col.shifts': 'Faz turnos',
  'team.col.status': 'Estado',
  'team.col.actions': 'Ações',
  'team.yes': 'Sim',
  'team.no': 'Não',
  'team.active': 'Ativo',
  'team.inactive': 'Inativo',
  'team.deactivate': 'Desativar',
  'team.reactivate': 'Reativar',
  'role.MANAGER': 'Gestor',
  'role.MEMBER': 'Membro',
} as const

export type MessageKey = keyof typeof messages

export const t = (key: MessageKey): string => messages[key]
