import { execFileSync } from 'node:child_process'
import { readFileSync, statSync } from 'node:fs'

const patterns = [
  ['OpenAI key', /\bsk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{35,}\b/],
  ['Gemini key', /\b(?:AIza[A-Za-z0-9_-]{35}|AQ\.[A-Za-z0-9_-]{35,})\b/],
  ['AWS access key', /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/],
  ['GitHub token', /\b(?:gh[pousr]_[A-Za-z0-9_]{36,}|github_pat_[A-Za-z0-9_]{50,})\b/],
  ['private key', /-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----/],
]

const paths = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'])
  .toString().split('\0').filter(Boolean)
let failures = 0
let scanned = 0
for (const path of new Set(paths)) {
  const metadata = statSync(path, { throwIfNoEntry: false })
  if (!metadata?.isFile()) continue
  if (/(?:^|\/)\.env(?:$|\.(?!example$))/.test(path) || /\.(?:pem|key)$/i.test(path)) {
    console.error(`${path} is a private configuration or key file`)
    failures++
    continue
  }
  const content = readFileSync(path)
  if (content.includes(0)) continue
  scanned++
  for (const [label, pattern] of patterns) {
    if (pattern.test(content.toString('utf8'))) {
      console.error(`${path} matches ${label}`)
      failures++
    }
  }
}

console.log(`Scanned ${scanned} text files. Matches: ${failures}.`)
if (failures) process.exitCode = 1
