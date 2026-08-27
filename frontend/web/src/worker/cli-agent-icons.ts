import {
  Bot,
  Braces,
  CircleDot,
  Code2,
  Compass,
  Cpu,
  MousePointer2,
  Orbit,
  Sparkles,
  WandSparkles,
  type LucideIcon,
} from 'lucide-react'

type CliAgentIcon = {
  Icon: LucideIcon
  label: string
  tone: string
}

const CLI_AGENT_ICONS: Readonly<Record<string, CliAgentIcon>> = {
  agy: { Icon: Orbit, label: 'Antigravity CLI', tone: 'var(--status-purple)' },
  claude: { Icon: Sparkles, label: 'Claude Code', tone: 'var(--status-orange)' },
  codex: { Icon: Code2, label: 'Codex', tone: 'var(--status-blue)' },
  cursor: { Icon: MousePointer2, label: 'Cursor CLI', tone: 'var(--accent)' },
  gemini: { Icon: Compass, label: 'Gemini', tone: 'var(--status-blue)' },
  grok: { Icon: WandSparkles, label: 'Grok Build', tone: 'var(--status-purple)' },
  hermes: { Icon: Bot, label: 'Hermes', tone: 'var(--status-green)' },
  opencode: { Icon: Braces, label: 'OpenCode', tone: 'var(--text-secondary)' },
  pi: { Icon: CircleDot, label: 'Pi', tone: 'var(--status-orange)' },
  qwen: { Icon: Cpu, label: 'Qwen Code', tone: 'var(--status-green)' },
}

export const getCliAgentIcon = (commandPresetId: string | undefined) =>
  commandPresetId ? CLI_AGENT_ICONS[commandPresetId.toLowerCase()] : undefined
