import XCTest

@testable import FograinKit

final class LibraryOrganizerTests: XCTestCase {
    private func instance(
        id: String,
        name: String,
        loader: LoaderKind = .fabric,
        minecraft: String = "1.21.4",
        mods: Int? = 3,
        size: Int64 = 1_000_000_000,
        lastPlayed: Date? = nil,
        favorite: Bool = false
    ) -> Instance {
        Instance(
            id: id,
            name: name,
            loader: loader,
            loaderVersion: "0.16.9",
            minecraftVersion: minecraft,
            modCount: mods,
            sizeBytes: size,
            lastPlayed: lastPlayed,
            isFavorite: favorite,
            palette: .forest
        )
    }

    func testGroupsFollowThePenOrderAndSkipEmptyGroups() {
        let instances = [
            instance(id: "vanilla", name: "原版", loader: .vanilla),
            instance(id: "forge", name: "Forge 实例", loader: .forge),
            instance(id: "fabric", name: "Fabric 实例", loader: .fabric),
            instance(id: "optifine", name: "OptiFine 实例", loader: .optifine),
        ]

        let sections = LibraryOrganizer.sections(from: instances, sort: .recent)

        XCTAssertEqual(sections.map(\.group), [.fabric, .forge, .vanilla])
        XCTAssertEqual(sections.last?.instances.map(\.id), ["vanilla", "optifine"])
    }

    func testRecentSortPutsNeverPlayedLast() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let instances = [
            instance(id: "never", name: "Never"),
            instance(id: "old", name: "Old", lastPlayed: now.addingTimeInterval(-7_200)),
            instance(id: "new", name: "New", lastPlayed: now),
        ]

        let sorted = LibrarySort.recent.sorted(instances)

        XCTAssertEqual(sorted.map(\.id), ["new", "old", "never"])
    }

    func testNameAndSizeSorts() {
        let instances = [
            instance(id: "b", name: "Bravo", size: 2),
            instance(id: "a", name: "Alpha", size: 3),
            instance(id: "c", name: "Charlie", size: 1),
        ]

        XCTAssertEqual(LibrarySort.name.sorted(instances).map(\.id), ["a", "b", "c"])
        XCTAssertEqual(LibrarySort.size.sorted(instances).map(\.id), ["a", "b", "c"])
    }

    func testSearchMatchesNameVersionAndLoader() {
        let fabric = instance(id: "f", name: "雾林生存", loader: .fabric, minecraft: "1.21.4")
        let forge = instance(id: "g", name: "机械动力 6", loader: .forge, minecraft: "1.20.1")

        XCTAssertEqual(
            LibraryFilter(query: "雾林").apply(to: [fabric, forge]).map(\.id),
            ["f"]
        )
        XCTAssertEqual(
            LibraryFilter(query: "1.20.1").apply(to: [fabric, forge]).map(\.id),
            ["g"]
        )
        XCTAssertEqual(
            LibraryFilter(query: "forge").apply(to: [fabric, forge]).map(\.id),
            ["g"]
        )
    }

    func testFavoritesAndLoaderScopes() {
        let favorite = instance(id: "a", name: "A", favorite: true)
        let other = instance(id: "b", name: "B", loader: .forge)

        XCTAssertEqual(
            LibraryFilter(scope: .favorites, query: "").apply(to: [favorite, other]).map(\.id),
            ["a"]
        )
        XCTAssertEqual(
            LibraryFilter(scope: .forge, query: "").apply(to: [favorite, other]).map(\.id),
            ["b"]
        )
    }

    func testInstanceDetailAndPlayTimeText() {
        let fabric = instance(id: "f", name: "雾林", mods: 42)
        XCTAssertEqual(fabric.homeDetail, "1.21.4 · Fabric · 42 模组")

        let vanilla = instance(id: "v", name: "原版", loader: .vanilla, mods: nil)
        XCTAssertEqual(vanilla.homeDetail, "1.21.4 · 原版")
        XCTAssertEqual(vanilla.modsText, "—")
    }
}

final class InstanceFormatTests: XCTestCase {
    func testByteSizeUsesFileUnits() {
        let gigabytes = InstanceFormat.byteSize(3_100_000_000)
        XCTAssertTrue(gigabytes.contains("GB"), gigabytes)

        let megabytes = InstanceFormat.byteSize(410_000_000)
        XCTAssertTrue(megabytes.contains("MB"), megabytes)
    }

    func testMissingLastPlayedReadsAsNever() {
        XCTAssertEqual(InstanceFormat.relativeDate(nil), "从未游玩")
    }

    func testPlayTimeFormatting() {
        XCTAssertEqual(InstanceFormat.playTime(nil), "—")
        XCTAssertEqual(InstanceFormat.playTime(45), "45 分钟")
        XCTAssertEqual(InstanceFormat.playTime(60), "1 小时")
        XCTAssertEqual(InstanceFormat.playTime(90), "1 小时 30 分钟")
    }
}
