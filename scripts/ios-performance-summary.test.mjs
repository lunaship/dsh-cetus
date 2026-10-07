import assert from "node:assert/strict"
import test from "node:test"
import { mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { compareWithBaseline, loadBaseline, summarizeMetrics } from "./ios-performance-summary.mjs"

test("保留全部采样并计算中位数", () => {
  const directory = mkdtempSync(join(tmpdir(), "dsh-performance-summary-"))
  try {
    writeFileSync(join(directory, "manifest.json"), JSON.stringify([
      {
        metricsFileName: "sample.csv.csv",
        testIdentifier: "PerformanceLaunchTests/testThreeThousandMessageAppend()",
        testIdentifierURL: "test://append",
      },
    ]))
    writeFileSync(
      join(directory, "sample.csv"),
      [
        '"Destination","Configuration","Clock Monotonic Time (Average)","Clock Monotonic Time (Iterations)","Clock Monotonic Time (Baseline)"',
        '"iPhone 17 Pro","Test Scheme Action","1.062 s","[1.1, 1.0, 1.3, 1.2, 1.4]","0"',
      ].join("\n"),
    )
    const [summary] = summarizeMetrics(directory)
    assert.equal(summary.metrics[0].samples.length, 5)
    assert.equal(summary.metrics[0].median, 1.2)
    assert.equal(summary.metrics[0].average, 1.062)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test("中位数超过基线 20% 才失败", () => {
  const baseline = loadBaseline()
  const tests = []
  for (const metric of baseline.metrics) {
    let test = tests.find((item) => item.testIdentifier === metric.testIdentifier)
    if (!test) {
      test = { testIdentifier: metric.testIdentifier, metrics: [] }
      tests.push(test)
    }
    test.metrics.push({ name: metric.name, median: metric.baseline * 1.2 })
  }
  assert.deepEqual(compareWithBaseline(tests, baseline), [])
  tests[0].metrics[0].median = baseline.metrics[0].baseline * 1.2000001
  const regressions = compareWithBaseline(tests, baseline)
  assert.equal(regressions.length, 1)
  assert.equal(regressions[0].name, baseline.metrics[0].name)
})
