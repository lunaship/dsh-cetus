/**
 * Summarize XCTest performance CSV files exported by `xcresulttool export metrics`.
 *
 * This records every sample and its median. It intentionally does not compare
 * against a baseline until three valid CI runs have established one.
 */
import { readdirSync, readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"

function cells(line) {
  const values = []
  let current = ""
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (char === '"') {
      if (quoted && line[index + 1] === '"') {
        current += '"'
        index += 1
      } else {
        quoted = !quoted
      }
    } else if (char === "," && !quoted) {
      values.push(current)
      current = ""
    } else {
      current += char
    }
  }
  values.push(current)
  return values
}

function number(value) {
  const parsed = Number(String(value).replace(/\s*s$/, ""))
  return Number.isFinite(parsed) ? parsed : null
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right)
  const middle = Math.floor(sorted.length / 2)
  if (sorted.length % 2 === 1) return sorted[middle]
  return (sorted[middle - 1] + sorted[middle]) / 2
}

export function summarizeMetrics(directory) {
  const manifest = JSON.parse(readFileSync(join(directory, "manifest.json"), "utf8"))
  if (!Array.isArray(manifest)) throw new Error("metrics manifest must be an array")
  return manifest.map((entry) => {
    const filename = String(entry.metricsFileName ?? "").replace(/\.csv$/, "")
    const rows = readFileSync(join(directory, filename), "utf8").trim().split(/\r?\n/)
    const headers = cells(rows[0] ?? "")
    const values = cells(rows[1] ?? "")
    const metrics = []
    for (let index = 0; index < headers.length; index += 1) {
      const match = headers[index].match(/^(.*) \((Average|Iterations|Baseline)\)$/)
      if (!match) continue
      const name = match[1]
      let metric = metrics.find((item) => item.name === name)
      if (!metric) {
        metric = { name, samples: [], average: null, median: null, baseline: null }
        metrics.push(metric)
      }
      if (match[2] === "Iterations") {
        metric.samples = JSON.parse(values[index]).map(number).filter((item) => item !== null)
        metric.median = metric.samples.length > 0 ? median(metric.samples) : null
      } else if (match[2] === "Average") {
        metric.average = number(values[index])
      } else {
        metric.baseline = number(values[index])
      }
    }
    return {
      testIdentifier: entry.testIdentifier,
      testIdentifierURL: entry.testIdentifierURL,
      destination: values[headers.indexOf("Destination")] ?? null,
      configuration: values[headers.indexOf("Configuration")] ?? null,
      metrics,
    }
  })
}

if (import.meta.url === new URL(process.argv[1], "file:").href) {
  const directory = process.argv[2]
  const output = process.argv[3]
  if (!directory || !output) {
    console.error("usage: ios-performance-summary.mjs <metrics-directory> <summary.json>")
    process.exit(2)
  }
  const summary = {
    generatedAt: new Date().toISOString(),
    source: directory,
    baselineEstablished: false,
    tests: summarizeMetrics(directory),
  }
  writeFileSync(output, JSON.stringify(summary, null, 2) + "\n")
  for (const test of summary.tests) {
    for (const metric of test.metrics) {
      console.log(
        test.testIdentifier + " | " + metric.name
        + " | samples=" + metric.samples.length
        + " | median=" + metric.median
        + " | average=" + metric.average,
      )
    }
  }
}
