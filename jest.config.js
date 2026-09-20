module.exports = {
  testEnvironment: 'node',
  testMatch: [
    '<rootDir>/__tests__/**/*.test.js',
    '<rootDir>/src/__tests__/**/*.test.ts',
    '<rootDir>/src/__tests__/**/*.test.tsx',
  ],
  transform: { '^.+\\.[jt]sx?$': 'babel-jest' },
  testPathIgnorePatterns: ['/node_modules/', '/example/', '/lib/'],
}
