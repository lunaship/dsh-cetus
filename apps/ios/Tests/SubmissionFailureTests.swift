import DLNet
import Foundation
import Testing

@testable import Cetus

/// C03 要求 4：失败按场景分开处理；超时类归为“结果未知”，绝不当作没发出。
@Suite struct SubmissionFailureTests {
    @Test func timeoutAndConnectionLostAreOutcomeUnknown() {
        #expect(SubmissionFailure.classify(URLError(.timedOut)) == .outcomeUnknown)
        #expect(
            SubmissionFailure.classify(HostClientError.transport(URLError(.networkConnectionLost))) == .outcomeUnknown)
        #expect(SubmissionFailure.classify(HostClientError.decoding("bad")) == .outcomeUnknown)
    }

    @Test func neverSentIsGenericRetry() {
        #expect(SubmissionFailure.classify(HostClientError.transport(URLError(.notConnectedToInternet))) == .generic)
        #expect(SubmissionFailure.classify(HostClientError.transport(URLError(.cannotConnectToHost))) == .generic)
    }

    @Test func hostErrorsMapToScenarios() {
        #expect(SubmissionFailure.classify(HostClientError.unauthorized) == .unauthorized)
        #expect(SubmissionFailure.classify(HostClientError.forbidden(pending: true)) == .pendingApproval)
        #expect(SubmissionFailure.classify(HostClientError.forbidden(pending: false)) == .forbidden)
        #expect(SubmissionFailure.classify(HostClientError.sessionBusy) == .busy)
        #expect(SubmissionFailure.classify(HostClientError.capabilityMissing) == .targetGone)
        #expect(SubmissionFailure.classify(HostClientError.server(status: 413, code: nil)) == .tooLarge)
        #expect(SubmissionFailure.classify(HostClientError.certificateChanged) == .certificateChanged)
        #expect(SubmissionFailure.classify(HostClientError.server(status: 500, code: nil)) == .generic)
    }

    @Test func everyScenarioHasDistinctCopy() {
        let all: [SubmissionFailure] = [
            .outcomeUnknown, .targetGone, .unauthorized, .pendingApproval, .forbidden, .busy, .tooLarge,
            .certificateChanged, .generic,
        ]
        #expect(Set(all.map(\.copyKey)).count == all.count)
    }
}
