import Foundation

struct DemoFixtures: Decodable, Equatable {
    struct Inbox: Decodable, Equatable {
        var title: String
        var workspace: String
        var time: String
        var status: String
        var preview: String
    }

    struct Status: Decodable, Equatable {
        var title: String
        var meta: String
    }

    struct Chip: Decodable, Equatable {
        var title: String
    }

    struct Line: Decodable, Equatable {
        var text: String
    }

    struct EmptyState: Decodable, Equatable {
        var title: String
        var systemImage: String
        var message: String
    }

    struct Decision: Decodable, Equatable {
        var status: String
        var question: String
        var secondaryTitle: String
        var primaryTitle: String
    }

    var longText: String
    var inbox: Inbox
    var status: Status
    var chip: Chip
    var process: Line
    var code: Line
    var empty: EmptyState
    var banner: Line
    var composer: Line
    var decision: Decision

    static func load(from url: URL) throws -> DemoFixtures {
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(DemoFixtures.self, from: data)
    }

    static func loadFromBundle(_ bundle: Bundle = .main) throws -> DemoFixtures {
        guard let url = bundle.url(forResource: "components", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try load(from: url)
    }
}
