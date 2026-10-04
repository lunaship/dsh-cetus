import Foundation
import SnapshotTesting

/// `.all` only when the test host sees `RECORD_SNAPSHOTS` equal to `"1"`.
/// Any other value, including a missing variable, compares existing references.
func snapshotRecordMode() -> SnapshotTestingConfiguration.Record {
    let environment = ProcessInfo.processInfo.environment
    let requested = environment["RECORD_SNAPSHOTS"] ?? environment["TEST_RUNNER_RECORD_SNAPSHOTS"]
    return requested == "1" ? .all : .missing
}
