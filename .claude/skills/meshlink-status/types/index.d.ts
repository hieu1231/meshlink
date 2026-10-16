export type Build = { isOk: boolean; seconds: number; task: string }

declare module 'claude-code' {
  interface PluginState {
    'meshlink-status': {
      devices: string[]
      build: Build | null
      contextPercent: number | null
    }
  }
}
