export type CliAgentIcon = {
  label: string
  src: string
}

const CLI_AGENT_ICONS: Readonly<Record<string, CliAgentIcon>> = {
  agy: { label: 'Antigravity CLI', src: '/cli-agent-icons/agy.png' },
  claude: { label: 'Claude Code', src: '/cli-agent-icons/claude.png' },
  codex: { label: 'Codex', src: '/cli-agent-icons/codex.png' },
  cursor: { label: 'Cursor CLI', src: '/cli-agent-icons/cursor.png' },
  gemini: { label: 'Gemini', src: '/cli-agent-icons/gemini.png' },
  grok: { label: 'Grok Build', src: '/cli-agent-icons/grok.png' },
  hermes: { label: 'Hermes', src: '/cli-agent-icons/hermes.png' },
  opencode: { label: 'OpenCode', src: '/cli-agent-icons/opencode.svg' },
  pi: { label: 'Pi', src: '/cli-agent-icons/pi.svg' },
  qwen: { label: 'Qwen Code', src: '/cli-agent-icons/qwen.png' },
}

export const getCliAgentIcon = (commandPresetId: string | undefined) =>
  commandPresetId ? CLI_AGENT_ICONS[commandPresetId.toLowerCase()] : undefined
