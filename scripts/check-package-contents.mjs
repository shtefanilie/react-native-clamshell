import { execFileSync } from 'node:child_process'
import { rmSync } from 'node:fs'

const requiredEntries = [
  'README.md',
  'docs/api.md',
  'docs/assets/clamshell-demo.gif',
  'lib/commonjs/index.js',
  'lib/module/index.js',
  'lib/typescript/src/index.d.ts',
  'src/index.ts',
  'cpp/AngleRuntimeBridge.cpp',
  'android/src/main/java/com/clamshell/HybridClamshell.kt',
  'ios/HybridClamshell.swift',
  'Clamshell.podspec',
  'nitro.json',
]

const forbiddenPrefixes = [
  'example/',
  'node_modules/',
  '.github/',
  '.superpowers/',
]

let tarball

try {
  tarball = execFileSync('npm', ['pack', '--json'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit'],
  })

  const [packResult] = JSON.parse(tarball)
  const files = packResult.files.map((entry) => entry.path).sort()
  const fileSet = new Set(files)
  const missing = requiredEntries.filter((entry) => !fileSet.has(entry))
  const forbidden = files.filter((entry) =>
    forbiddenPrefixes.some((prefix) => entry.startsWith(prefix))
  )

  if (missing.length > 0 || forbidden.length > 0) {
    if (missing.length > 0) {
      console.error('Package is missing required entries:')
      for (const entry of missing) console.error(`- ${entry}`)
    }
    if (forbidden.length > 0) {
      console.error('Package includes forbidden entries:')
      for (const entry of forbidden) console.error(`- ${entry}`)
    }
    process.exit(1)
  }

  console.log(
    `Package contents OK: ${packResult.filename} (${files.length} files)`
  )
} finally {
  if (tarball != null) {
    try {
      const [packResult] = JSON.parse(tarball)
      if (packResult?.filename) rmSync(packResult.filename, { force: true })
    } catch {
      // Ignore cleanup parsing errors; npm already printed the root failure.
    }
  }
}
