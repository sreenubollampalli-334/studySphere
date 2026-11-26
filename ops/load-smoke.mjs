import { performance } from 'node:perf_hooks'

const origin = new URL(process.argv[2] ?? 'http://127.0.0.1:8080')
if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password ||
    origin.pathname !== '/' || origin.search || origin.hash) {
  throw new Error('Provide an HTTP(S) origin without credentials or a path.')
}

function boundedInteger(envName, fallback, limit) {
  const value = Number(process.env[envName] ?? fallback)
  if (!Number.isInteger(value) || value < 1 || value > limit) {
    throw new Error(`${envName} must be an integer from 1 to ${limit}.`)
  }
  return value
}

const attempts = boundedInteger('SMOKE_REQUESTS', 60, 500)
const concurrency = boundedInteger('SMOKE_CONCURRENCY', 6, 20)
const latencyBudget = boundedInteger('SMOKE_MAX_P95_MS', 1000, 30000)
const probes = [
  { path: '/actuator/health', status: 200, check: body => body.status === 'UP' },
  { path: '/api/v1/auth/csrf', status: 204 },
  { path: '/api/v1/me', status: 401 },
]

const timings = []
const failures = []
let next = 0

async function worker() {
  while (next < attempts) {
    const probe = probes[next++ % probes.length]
    const start = performance.now()
    try {
      const response = await fetch(new URL(probe.path, origin), {
        redirect: 'error',
        signal: AbortSignal.timeout(10000),
      })
      let validBody = true
      if (probe.check) {
        validBody = probe.check(await response.json())
      } else {
        await response.arrayBuffer()
      }
      if (response.status !== probe.status || !validBody) {
        failures.push(`${probe.path} returned unexpected status/body (${response.status})`)
      }
    } catch (error) {
      failures.push(`${probe.path} failed (${error.name})`)
    } finally {
      timings.push(performance.now() - start)
    }
  }
}

await Promise.all(Array.from({ length: concurrency }, worker))
timings.sort((a, b) => a - b)
const p95 = Math.ceil(timings[Math.ceil(timings.length * 0.95) - 1])
console.log(`requests=${attempts} concurrency=${concurrency} failures=${failures.length} p95_ms=${p95} budget_ms=${latencyBudget}`)
for (const failure of new Set(failures)) console.error(failure)
if (failures.length || p95 > latencyBudget) process.exitCode = 1
