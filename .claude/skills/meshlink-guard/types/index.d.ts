export type GuardMode = boolean

declare module 'claude-code' {
  interface PluginState {
    'meshlink-guard': { careful: GuardMode }
  }
}
