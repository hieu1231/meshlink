import { expect, test } from 'claude-code/testing'

// Stand-ins for the engine beneath the plugin, registered before the test first touches $.
const engine = (on: any) => {
  on('tool.call', () => ({ result: 'ran' }))
  on('session.start', () => ({ cwd: '/work' }))
  on('command.register', (_$: any, e: any) => ({ value: { command: e.name } }))
  on('ui.toast', () => ({ value: undefined }))
  on('ui.status', () => ({ value: undefined }))
}

const bash = ($: any, command: string) => $.tool.call({ tool: 'Bash', command } as any)

const careful = async ($: any) => {
  await $.session.start({ surface: 'terminal', cwd: '/work' } as any)
  await $.command.run({ command: 'careful', args: 'on' } as any)
}

test('always blocks wiping the app on a device', async ($, on) => {
  engine(on)
  for (const command of [
    'adb -s R58 uninstall com.hieu.p2p',
    'adb shell pm clear com.hieu.p2p',
    './gradlew :app:connectedDebugAndroidTest',
  ]) {
    expect((await bash($, command)).deny).toBeDefined()
  }
})

test('lets rm -rf through while careful mode is off', async ($, on) => {
  engine(on)
  expect((await bash($, 'rm -rf app/build')).deny).toBeUndefined()
})

test('careful mode blocks every spelling of rm -rf', async ($, on) => {
  engine(on)
  await careful($)
  for (const command of [
    'rm -rf app/build',
    'rm -fr x',
    'rm -Rf x',
    'rm -r -f x',
    'rm --recursive --force x',
    '/bin/rm -rf x',
    'cd app && rm -rf build',
    'FOO=1 rm -rf x',
    'git push -f origin main',
    'git reset --hard HEAD~1',
  ]) {
    expect((await bash($, command)).deny).toBeDefined()
  }
})

test('careful mode ignores look-alikes', async ($, on) => {
  engine(on)
  await careful($)
  for (const command of ['git rm -r --cached x', 'echo "rm -rf"', 'grep -rn "rm -rf" .', 'rm -f x', 'rm -r x', './gradlew clean']) {
    expect((await bash($, command)).deny).toBeUndefined()
  }
})

test('/careful off turns it back off', async ($, on) => {
  engine(on)
  await careful($)
  await $.command.run({ command: 'careful', args: 'off' } as any)
  expect((await bash($, 'rm -rf app/build')).deny).toBeUndefined()
})

test('protects keystore.properties', async ($, on) => {
  engine(on)
  const ran = await $.tool.call({ tool: 'Write', file_path: '/x/keystore.properties', content: 'a' } as any)
  expect(ran.deny).toBeDefined()
})
