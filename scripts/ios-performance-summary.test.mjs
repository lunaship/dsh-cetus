import assert from "node:assert/strict"
import test from "node:test"
import { mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { summarizeMetrics } from "./ios-performance-summary.mjs"

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
    assert.equal(summary.baselineEstablished, undefined)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
