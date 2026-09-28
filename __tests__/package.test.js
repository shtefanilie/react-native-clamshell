const packageManifest = require('../package.json')
const semver = require('semver')

describe('published package manifest', () => {
  test('declares supported runtime ranges as peer dependencies', () => {
    expect(packageManifest.name).toBe('react-native-clamshell')
    expect(packageManifest.peerDependencies).toEqual({
      'react': '^19.2.0',
      'react-native': '0.83 - 0.87',
      'react-native-nitro-modules': '^0.37.1',
      'react-native-reanimated': '4.6.x',
      'react-native-worklets': '0.12.x',
    })
  })

  test('defines every stable Task 1 command', () => {
    expect(Object.keys(packageManifest.scripts)).toEqual(
      expect.arrayContaining([
        'codegen',
        'test:js',
        'test:android',
        'test:ios',
        'test:worklets:android',
        'test:worklets:ios',
        'typecheck',
        'lint',
        'build:android:debug',
        'build:android:release',
        'build:ios',
      ])
    )
  })

  test('keeps tested peers out of runtime dependencies', () => {
    const exampleManifest = require('../example/package.json')
    const peerNames = Object.keys(packageManifest.peerDependencies)

    expect(packageManifest.dependencies ?? {}).toEqual({})
    for (const peerName of peerNames) {
      expect(
        semver.satisfies(
          exampleManifest.dependencies[peerName],
          packageManifest.peerDependencies[peerName]
        )
      ).toBe(true)
    }
  })
})
