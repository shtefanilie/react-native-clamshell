const packageManifest = require('../package.json')

describe('published package manifest', () => {
  test('declares the tested runtime stack as peer dependencies', () => {
    expect(packageManifest.name).toBe('react-native-clamshell')
    expect(packageManifest.peerDependencies).toEqual({
      react: '19.2.3',
      'react-native': '0.86.0',
      'react-native-nitro-modules': '0.37.1',
      'react-native-reanimated': '4.6.0',
      'react-native-worklets': '0.12.2',
    })
  })

  test('defines every stable Task 1 command', () => {
    expect(Object.keys(packageManifest.scripts)).toEqual(
      expect.arrayContaining([
        'codegen',
        'test:js',
        'test:android',
        'test:ios',
        'test:worklet:android',
        'test:worklet:ios',
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
      expect(exampleManifest.dependencies[peerName]).toBe(
        packageManifest.peerDependencies[peerName]
      )
    }
  })
})
