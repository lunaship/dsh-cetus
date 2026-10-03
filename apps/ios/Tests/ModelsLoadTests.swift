import Testing
@testable import DLModels

struct ModelsLoadTests {
    @Test func modelsModuleLoads() {
        #expect(DLModelsMarker.self == DLModelsMarker.self)
    }
}
