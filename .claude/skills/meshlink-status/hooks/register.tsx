import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Build } from '../types'

const adbPath = async ($: EngineInterface) => {
  try {
    const props = await $.fs.read('local.properties')
    const sdk = props.match(/^sdk\.dir=(.*)$/m)?.[1]?.trim()
    if (sdk) return `${sdk}/platform-tools/adb`
  } catch {
    // no local.properties: fall back to PATH
  }
  return 'adb'
}

const devices = atom({ plugin: 'meshlink-status', key: 'devices' } as const, [])
const build = atom({ plugin: 'meshlink-status', key: 'build' } as const, null)
const contextPercent = atom({ plugin: 'meshlink-status', key: 'contextPercent' } as const, null)

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    const ADB = await adbPath($)
    const poll = async () => {
      try {
        const { exitCode, stdout } = await $.process.run([ADB, 'devices', '-l'], { timeoutMs: 5000 })
        const list =
          exitCode !== 0
            ? []
            : stdout
                .split('\n')
                .slice(1)
                .filter(line => /\sdevice\s/.test(line))
                .map(line => line.match(/model:(\S+)/)?.[1] ?? line.split(/\s+/)[0])
        const before = await read($, devices)
        if (before.join() !== list.join()) await update($, devices, () => list)
      } catch {
        // adb missing or hung: keep the last reading
      }
    }
    void poll()
    $.clock.every(10_000, poll)

    return next(e)
  })

  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const task = e.command.match(/gradlew\S*\s+(?:\S+\s+)*?(:?[\w:]*(?:assemble|build|install|test|lint|bundle)\w*)/i)?.[1]
    if (!task) return next(e)

    const startedAt = await $.clock.now()
    const ran = await next(e)
    if (ran.deny !== undefined) return ran

    const result: Build = {
      isOk: ran.isError !== true,
      seconds: Math.round(((await $.clock.now()) - startedAt) / 1000),
      task,
    }
    await update($, build, () => result)
    if (!result.isOk) $.ui.toast(`✗ Gradle ${task} failed`)

    return ran
  }).catch(($, e, next) => next(e))

  on('turn.complete', async ($, e, next) => {
    const { context } = await $.session.usage()
    if (context.percent !== undefined) await update($, contextPercent, () => Math.round(context.percent!))

    return next(e)
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (e.props.hasSurvey) return next(e)

    const { Box, Text } = $.ui.resolve(e)
    const list = await read($, devices)
    const last = await read($, build)
    const percent = await read($, contextPercent)

    return (
      <Box gap={2}>
        <Text color={list.length > 0 ? 'green' : 'gray'}>
          📱 {list.length === 0 ? 'no device' : `${list.length}: ${list.join(', ')}`}
        </Text>
        {last && (
          <Text color={last.isOk ? 'green' : 'red'}>
            {last.isOk ? '✓' : '✗'} {last.task} {last.seconds}s
          </Text>
        )}
        {percent !== null && (
          <Text color={percent >= 75 ? 'red' : percent >= 50 ? 'yellow' : 'gray'}>ctx {percent}%</Text>
        )}
      </Box>
    )
  })
}
