import type { EngineInterface, Register } from 'claude-code'

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
const APK = 'app/build/outputs/apk/debug/app-debug.apk'

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.command.register({
      name: 'deploy-all',
      description: 'Install the debug APK on every connected ADB device',
    })

    return next(e)
  })

  on('command.run', { command: 'deploy-all' }, async $ => {
    const ADB = await adbPath($)
    const listed = await $.process.run([ADB, 'devices'], { timeoutMs: 5000 })
    const serials = listed.stdout
      .split('\n')
      .slice(1)
      .map(line => line.split(/\s+/))
      .filter(([, state]) => state === 'device')
      .map(([serial]) => serial)

    if (serials.length === 0) return { text: '📱 No ADB device connected.' }

    try {
      await $.fs.stat(APK)
    } catch {
      return { text: `No APK at ${APK}. Run ./gradlew assembleDebug first.` }
    }

    $.ui.status(`Installing on ${serials.length} device(s)…`)
    const results = await Promise.all(
      serials.map(async serial => {
        const ran = await $.process.run([ADB, '-s', serial, 'install', '-r', APK], { timeoutMs: 120_000 })
        const isOk = ran.exitCode === 0 && /Success/.test(ran.stdout)
        return `${isOk ? '✓' : '✗'} ${serial}${isOk ? '' : `: ${(ran.stderr || ran.stdout).trim().split('\n').pop()}`}`
      }),
    )
    $.ui.status(undefined)

    return { text: results.join('\n') }
  })
}
