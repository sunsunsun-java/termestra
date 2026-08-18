import assert from 'node:assert/strict'
import test from 'node:test'

import {
  __resetServiceWorkerUpdateStateForTests,
  registerServiceWorkerWithEnv,
  subscribeServiceWorkerUpdate,
} from '../web/src/pwa/service-worker-registration.ts'
import { serviceWorkerCacheGeneration } from '../web/src/pwa/build-sw.ts'
import { cacheNamesToDelete } from '../web/src/pwa/service-worker-cache-policy.ts'
import { probeGlobalTerminalRuns } from '../web/src/pwa/global-terminal-run-safety.ts'
import { isServiceWorkerReloadSafe } from '../web/src/pwa/service-worker-update-policy.ts'

test('a brand revision creates a fresh service-worker cache generation', () => {
  assert.equal(serviceWorkerCacheGeneration('0.1.0'), '0.1.0')
  assert.equal(serviceWorkerCacheGeneration('0.1.0', 'brand-2'), '0.1.0-brand-2')
})

test('an already-installed waiting worker immediately exposes the update action', async () => {
  __resetServiceWorkerUpdateStateForTests()
  const posted = []
  const waiting = {
    state: 'installed',
    addEventListener() {},
    postMessage: (message) => posted.push(message),
  }
  const registration = {
    waiting,
    installing: null,
    addEventListener() {},
  }
  const container = {
    controller: {},
    addEventListener() {},
    register: async () => registration,
  }
  let apply = null
  const unsubscribe = subscribeServiceWorkerUpdate((next) => {
    apply = next
  })

  await registerServiceWorkerWithEnv({ isProd: true, reload() {}, serviceWorker: container })

  assert.equal(typeof apply, 'function')
  apply()
  assert.deepEqual(posted, [{ type: 'SKIP_WAITING' }])
  unsubscribe()
  __resetServiceWorkerUpdateStateForTests()
})

test('controller activation cancels the reload fallback instead of reloading twice', async (t) => {
  __resetServiceWorkerUpdateStateForTests()
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const containerListeners = new Map()
  const waiting = {
    state: 'installed',
    addEventListener() {},
    postMessage() {},
  }
  const registration = {
    waiting,
    installing: null,
    addEventListener() {},
  }
  const container = {
    controller: {},
    addEventListener(type, listener) {
      containerListeners.set(type, listener)
    },
    register: async () => registration,
  }
  let reloads = 0
  let apply = null
  const unsubscribe = subscribeServiceWorkerUpdate((next) => {
    apply = next
  })

  await registerServiceWorkerWithEnv({
    isProd: true,
    reload: () => {
      reloads += 1
    },
    serviceWorker: container,
  })

  apply()
  containerListeners.get('controllerchange')()
  t.mock.timers.tick(2000)

  assert.equal(reloads, 1)
  unsubscribe()
  __resetServiceWorkerUpdateStateForTests()
})

test('the same installing worker is observed only once across updatefound', async () => {
  __resetServiceWorkerUpdateStateForTests()
  let stateListeners = 0
  const installing = {
    state: 'installing',
    addEventListener(type) {
      if (type === 'statechange') stateListeners += 1
    },
    postMessage() {},
  }
  let updateFound
  const registration = {
    waiting: null,
    installing,
    addEventListener(type, listener) {
      if (type === 'updatefound') updateFound = listener
    },
  }
  const container = {
    controller: {},
    addEventListener() {},
    register: async () => registration,
  }

  await registerServiceWorkerWithEnv({ isProd: true, reload() {}, serviceWorker: container })
  updateFound()

  assert.equal(stateListeners, 1)
  __resetServiceWorkerUpdateStateForTests()
})

test('service-worker cache cleanup deletes every non-current Termestra cache', () => {
  assert.deepEqual(
    cacheNamesToDelete(
      [
        'termestra-cache-v1-shell',
        'termestra-cache-v1-assets',
        'termestra-cache-v2-shell',
        'termestra-cache-v2-assets',
        'termestra-cache-v3-shell',
        'termestra-cache-v3-assets',
        'termestra-cache-v3-static',
        'termestra-cache-vstale',
        'unrelated-cache',
      ],
      '3'
    ).sort(),
    [
      'termestra-cache-v1-assets',
      'termestra-cache-v1-shell',
      'termestra-cache-v2-assets',
      'termestra-cache-v2-shell',
      'termestra-cache-vstale',
    ]
  )
})

test('an active member in another workspace blocks an update reload', () => {
  const stoppedRun = {
    agent_id: 'one:orchestrator',
    agent_name: 'Orchestrator',
    run_id: 'run-1',
    status: 'stopped',
    terminal_input_profile: 'default',
  }
  const worker = (status) => ({
    commandPresetId: 'codex',
    id: 'worker-1',
    lastPtyLine: '',
    name: 'Reviewer',
    pendingTaskCount: 0,
    role: 'reviewer',
    status,
  })

  assert.equal(
    isServiceWorkerReloadSafe(
      [stoppedRun],
      true,
      {
        one: [worker('stopped')],
        two: [worker('working')],
      },
      ['one', 'two']
    ),
    false
  )
  assert.equal(
    isServiceWorkerReloadSafe(
      [stoppedRun],
      true,
      {
        one: [worker('stopped')],
        two: [worker('stopped')],
      },
      ['one', 'two']
    ),
    true
  )
  assert.equal(isServiceWorkerReloadSafe([], false, {}, ['one']), false)
  assert.equal(isServiceWorkerReloadSafe([], true, {}, ['one']), false)
})

test('an active terminal in another workspace is included in the update safety snapshot', async () => {
  const requested = []
  const snapshot = await probeGlobalTerminalRuns(
    ['one', 'two'],
    async (workspaceId) => {
      requested.push(workspaceId)
      return workspaceId === 'two'
        ? [
            {
              agent_id: 'two:shell:1',
              agent_name: 'Shell',
              run_id: 'run-two',
              status: 'running',
              terminal_input_profile: 'default',
            },
          ]
        : []
    },
    new AbortController().signal
  )

  assert.deepEqual(requested, ['one', 'two'])
  assert.equal(snapshot.ready, true)
  assert.equal(snapshot.runs.length, 1)
  assert.equal(snapshot.runs[0].run_id, 'run-two')
  assert.equal(
    isServiceWorkerReloadSafe(snapshot.runs, snapshot.ready, { one: [], two: [] }, ['one', 'two']),
    false
  )
})

test('global terminal safety probing is fail-closed', async () => {
  const snapshot = await probeGlobalTerminalRuns(
    ['one', 'two'],
    async (workspaceId) => {
      if (workspaceId === 'two') throw new Error('runtime unavailable')
      return []
    },
    new AbortController().signal
  )

  assert.equal(snapshot.ready, false)
  assert.deepEqual(snapshot.runs, [])
})

test('global terminal safety probing has bounded concurrency', async () => {
  let active = 0
  let peak = 0
  const releases = []
  const probe = probeGlobalTerminalRuns(
    ['one', 'two', 'three', 'four', 'five'],
    async () => {
      active += 1
      peak = Math.max(peak, active)
      await new Promise((resolve) => releases.push(resolve))
      active -= 1
      return []
    },
    new AbortController().signal,
    2
  )

  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(active, 2)
  releases.splice(0).forEach((release) => release())
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(active, 2)
  releases.splice(0).forEach((release) => release())
  await new Promise((resolve) => setImmediate(resolve))
  releases.splice(0).forEach((release) => release())

  const snapshot = await probe
  assert.equal(snapshot.ready, true)
  assert.equal(peak, 2)
})
