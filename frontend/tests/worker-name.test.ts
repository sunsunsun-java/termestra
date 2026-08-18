import { describe, expect, test } from 'vitest'

import { generateWorkerName } from '../web/src/worker/randomWorkerName.js'

describe('team member name suggestions', () => {
  test('uses Termestra call signs that match the selected role and language', () => {
    expect(generateWorkerName({ language: 'en', role: 'coder', nextUint32: () => 0 })).toBe(
      'lattice'
    )
    expect(generateWorkerName({ language: 'zh', role: 'reviewer', nextUint32: () => 0 })).toBe(
      '明镜'
    )
  })

  test('skips names already used by the current workspace', () => {
    expect(
      generateWorkerName({
        language: 'en',
        role: 'tester',
        usedNames: new Set(['probe']),
        nextUint32: () => 0,
      })
    ).toBe('beacon')
  })

  test('keeps returning a valid suggestion after a small role pool is exhausted', () => {
    const usedNames = new Set([
      'lattice',
      'forge',
      'vector',
      'radix',
      'mosaic',
      'relay',
      'kernel',
      'prism',
      'syntax',
      'lumen',
      'ember',
      'weaver',
    ])

    expect(
      generateWorkerName({ language: 'en', role: 'coder', usedNames, nextUint32: () => 1 })
    ).toBe('forge')
  })
})
