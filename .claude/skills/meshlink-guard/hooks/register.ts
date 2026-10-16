import { atom, read, update } from 'claude-code'
import type { Register } from 'claude-code'

import type { GuardMode } from '../types'

// Always on: these wipe the MeshLink identity on a test phone or touch signing secrets.
const ALWAYS: [RegExp, string][] = [
  [/\badb\b.*\buninstall\b/, 'adb uninstall (wipes app data and node identity on the device)'],
  [/\badb\b.*\bpm\s+clear\b/, 'adb shell pm clear (wipes app data and node identity)'],
  [/\bconnected\w*AndroidTest\b/, 'connectedAndroidTest (uninstalls the app; see CLAUDE.md for the adb instrument flow)'],
]

// Only while /careful is on: destructive but sometimes legitimate.
const CAREFUL: [RegExp, string][] = [
  [/^git\s+push\b.*(\s--force\b|\s--force-with-lease\b|\s-f\b)/, 'git push --force'],
  [/^git\s+reset\s+.*--hard\b/, 'git reset --hard'],
  [/^git\s+clean\s+-[a-z]*f/, 'git clean -f'],
  [/^git\s+(checkout|restore)\s+.*(\s--\s|\s\.$)/, 'git checkout/restore that discards changes'],
  [/^find\b.*\s(-delete\b|-exec\s+rm\b)/, 'find -delete'],
]

const PROTECTED = /(^|\/)(keystore\.properties|google-services\.json|local\.properties|[^/]+\.(jks|keystore))$/

const careful = atom({ plugin: 'meshlink-guard', key: 'careful' } as const, false as GuardMode)

// Each simple command of a shell line, with quoted text removed so `echo "rm -rf"` reads as `echo`.
const segments = (command: string) =>
  command
    .replace(/'[^']*'|"(?:\\.|[^"\\])*"/g, "''")
    .split(/\n|;|&&|\|\||\||\$\(|`/)
    .map(part => part.trim().replace(/^(?:(?:sudo|command|exec|env|nice|time)\s+|\w+=\S*\s+)+/, ''))
    .filter(Boolean)

// `rm` (by any path) with both recursive and force, however the flags are spelled.
const isRmRf = (segment: string) => {
  const [program, ...args] = segment.split(/\s+/)
  if (!/(^|\/)rm$/.test(program)) return false
  const flags = args.filter(arg => arg.startsWith('-') && arg !== '--')
  const has = (short: RegExp, long: string) =>
    flags.some(flag => flag === long || (!flag.startsWith('--') && short.test(flag)))
  return has(/[rR]/, '--recursive') && has(/f/, '--force')
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.command.register({
      name: 'careful',
      description: 'Block rm -rf, force-push, reset --hard and similar for this session (/careful off to stop)',
    })

    return next(e)
  })

  on('command.run', { command: 'careful' }, async ($, e) => {
    const arg = e.args.trim().toLowerCase()
    const isOn = await update($, careful, now => (arg === 'off' ? false : arg === 'on' ? true : !now))
    $.ui.status(isOn ? '🛡 careful' : undefined)

    return {
      text: isOn
        ? '🛡 Careful mode on: rm -rf, git push --force, git reset --hard, git clean -f, find -delete are blocked this session.'
        : 'Careful mode off. adb uninstall / pm clear / connectedAndroidTest and secret files stay protected.',
    }
  })

  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const parts = segments(e.command)
    const always = ALWAYS.find(([pattern]) => pattern.test(e.command))
    const risky = (await read($, careful))
      ? parts.some(isRmRf)
        ? 'rm -rf'
        : CAREFUL.find(([pattern]) => parts.some(part => pattern.test(part)))?.[1]
      : undefined
    const hit = always?.[1] ?? risky

    if (hit) {
      $.ui.toast(`🛡 Blocked: ${hit}`)
      return {
        deny: always
          ? `${$.plugin.name}: "${hit}" is never allowed in this project. Ask the user to run it themselves.`
          : `${$.plugin.name}: careful mode blocks "${hit}". Use a safer alternative (e.g. ./gradlew clean) or ask the user to run /careful off.`,
      }
    }

    return next(e)
  }).catch(($, e, next) =>
    next.called ? next(e) : { deny: `${$.plugin.name}: its guard failed.` },
  )

  for (const tool of ['Edit', 'Write'] as const) {
    on('tool.call', { tool }, ($, e, next) =>
      PROTECTED.test(e.file_path)
        ? { deny: `${$.plugin.name}: ${e.file_path} is protected (signing/secrets). Ask the user to change it.` }
        : next(e),
    ).catch(($, e, next) =>
      next.called ? next(e) : { deny: `${$.plugin.name}: its guard failed.` },
    )
  }
}
